"""A sender must not be able to choose where the receiver writes.

The ``filename`` field arrives over HTTP from another machine. Handing it
straight to ``os.path.join`` turns ``../../../.bashrc`` into a write outside the
upload directory, because join does not stop at the first component.

These tests attack the running receiver rather than trusting the sanitizer, and
they cover the Android implementation's rule too -- it was already safe, and the
point here is that it stays that way.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from safe_filename import safe_filename  # noqa: E402
from desktop.embedded_server import EmbeddedReceiverServer  # noqa: E402

TOKEN_HEADER = "X-NexusFlow-Token"

# Names a hostile or merely careless sender could send.
HOSTILE = [
    "../../../.bashrc",
    "../../etc/passwd",
    "..\\..\\windows\\system32\\evil.dll",
    "/etc/cron.d/nexusflow",
    "....//....//escape.txt",
    "./../sibling.txt",
    "sub/dir/legit.txt",
    "",
    "   ",
    ".",
    "..",
    "con",
    "nul.txt",
    "with\x00nul.bin",
    "sp ace.png",
    "emoji-\U0001f4e6.bin",
    "a" * 400,
]


# ── the sanitizer in isolation ────────────────────────────────────────────────

@pytest.mark.parametrize("raw", HOSTILE)
def test_result_never_escapes_the_upload_directory(raw):
    out = safe_filename(raw)
    base = os.path.join("/srv/uploads", out)
    assert os.path.dirname(base) == "/srv/uploads", (
        f"{raw!r} escaped to {base!r}")


@pytest.mark.parametrize("raw", HOSTILE)
def test_result_is_always_usable(raw):
    out = safe_filename(raw)
    assert out, "must never be empty: an empty name is not a filename"
    assert "/" not in out and "\\" not in out
    assert "\x00" not in out
    assert not out.startswith(".")


def test_ordinary_names_survive_intact():
    # The sanitizer must not mangle the names real transfers use.
    for name in ("report.pdf", "IMG_20240115_104233.jpg", "data-2024_v1.2.csv",
                 "archive.tar.gz", "file_with-many.dots.in.name.txt"):
        assert safe_filename(name) == name


def test_traversal_reduces_to_the_leaf():
    # Keeping the leaf is the useful behaviour, not just a safe one.
    assert safe_filename("../../../etc/passwd") == "passwd"
    assert safe_filename("/home/user/report.pdf") == "report.pdf"


def test_windows_reserved_names_are_prefixed():
    assert safe_filename("con") == "_con"
    assert safe_filename("COM1.log") == "_COM1.log"


def test_null_byte_is_stripped_not_preserved():
    assert "\x00" not in safe_filename("with\x00nul.bin")


# ── the running receiver ──────────────────────────────────────────────────────

@pytest.fixture
def receiver(tmp_path):
    srv = EmbeddedReceiverServer(host="127.0.0.1", port=0,
                                 upload_dir=str(tmp_path / "uploads"))
    assert srv.start() is True
    for _ in range(100):
        if srv.server and srv.server.server_address[1]:
            break
        import time as _t
        _t.sleep(0.02)
    yield srv
    srv.stop()


def _base(srv):
    host, port = srv.server.server_address[0], srv.server.server_address[1]
    return f"http://{host}:{port}"


def _create(base, token, filename, size=4):
    import json
    import urllib.request
    req = urllib.request.Request(
        f"{base}/transfer",
        data=json.dumps({"filename": filename, "filesize": size,
                         "offset": 0}).encode(),
        method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header(TOKEN_HEADER, token)
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.loads(resp.read().decode())


@pytest.mark.parametrize("hostile", [
    "../../../.bashrc",
    "../../etc/passwd",
    "..\\..\\windows\\evil.dll",
    "/etc/cron.d/nexusflow",
    "con",
])
def test_receiver_writes_only_inside_its_upload_dir(receiver, hostile):
    import os as _os
    base = _base(receiver)
    upload_dir = _os.path.realpath(str(receiver.upload_dir))

    rec = _create(base, receiver.auth_token, hostile)
    assert rec.get("error") != "unauthorised"

    dest = _os.path.realpath(rec["dest_path"] if "dest_path" in rec
                             else _os.path.join(str(receiver.upload_dir),
                                                safe_filename(hostile)))
    assert _os.path.commonpath([upload_dir, dest]) == upload_dir, (
        f"{hostile!r} resolved to {dest!r}, outside {upload_dir!r}")

    # And nothing appeared above the upload directory.
    parent = _os.path.dirname(upload_dir)
    assert not _os.path.exists(_os.path.join(parent, safe_filename(hostile)))


def test_receiver_records_the_sanitised_name(receiver):
    rec = _create(receiver and _base(receiver), receiver.auth_token,
                  "../../../escape.bin", size=10)
    assert rec["filename"] == "escape.bin"
    assert ".." not in rec["filename"]


def test_the_sanitised_name_is_what_lands_on_disk(receiver):
    import os as _os
    base = _base(receiver)
    rec = _create(base, receiver.auth_token, "/tmp/should-not-appear.txt", size=3)

    listed = _os.listdir(str(receiver.upload_dir))
    assert listed == ["should-not-appear.txt"], listed
    assert rec["filename"] == "should-not-appear.txt"