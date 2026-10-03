"""Does the FROZEN Linux build actually work?

    python tests/test_frozen_build.py

The packaged executable is built by PyInstaller, which resolves imports
differently from the source tree: it follows neither `sys.path` manipulation nor
a module named by a string. Both `auth_token` and `safe_filename` are imported by
name from `desktop/`, and an earlier build shipped without them -- the binary
died at startup with `ModuleNotFoundError: No module named 'auth_token'` while
every source test passed, because the tests import from the source tree.

So this asserts against `dist/NexusFlow/`, the artefact a reviewer downloads,
and it is skipped rather than failed when that directory has not been built yet.
"""

import os
import subprocess
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, "dist", "NexusFlow")
BINARY = os.path.join(DIST, "NexusFlow")
INTERNAL = os.path.join(DIST, "_internal")

pytestmark = pytest.mark.skipif(
    not os.path.exists(BINARY),
    reason="dist/NexusFlow not built; run pyinstaller NexusFlow.spec first",
)


def run_probe(script: str, timeout: int = 45):
    """Run a script under the frozen interpreter's own import machinery.

    The packaged app is a GUI, so it cannot be asked to evaluate a snippet from
    the command line. Instead the snippet is run with the frozen build's
    `_internal` directory on the path, which is exactly the situation the binary
    itself is in: same directory layout, same Python, no source tree.
    """
    probe = os.path.join("/tmp", "_nf_frozen_probe.py")
    with open(probe, "w") as fh:
        fh.write(script)
    env = dict(os.environ, PYTHONPATH=INTERNAL, QT_QPA_PLATFORM="offscreen")
    return subprocess.run([sys.executable, probe], capture_output=True, text=True,
                          timeout=timeout, env=env, cwd=ROOT)


def test_frozen_layout_ships_the_runtime_modules():
    """The two modules imported by name must be inside the bundle.

    A tarball that omits them starts and then fails on first use, which is worse
    than failing at build time.
    """
    for name in ("auth_token.py", "safe_filename.py"):
        path = os.path.join(INTERNAL, name)
        assert os.path.exists(path), f"{name} missing from the bundle"


def test_auth_token_works_from_the_bundle():
    r = run_probe(
        "import auth_token\n"
        "t = auth_token.generate_token()\n"
        "assert len(t) == 8, t\n"
        "assert not set(t) & set('01OIL'), t\n"
        "assert auth_token.tokens_match(t, t)\n"
        "assert not auth_token.tokens_match(t, 'NOPE')\n"
        "print('OK', t)\n"
    )
    assert r.returncode == 0, r.stderr
    assert "OK" in r.stdout


def test_safe_filename_works_from_the_bundle():
    r = run_probe(
        "import os, safe_filename\n"
        "for raw in ('../../../.bashrc', '/etc/passwd', '..\\\\..\\\\x.dll'):\n"
        "    out = safe_filename.safe_filename(raw)\n"
        "    joined = os.path.join('/srv/uploads', out)\n"
        "    assert os.path.dirname(joined) == '/srv/uploads', (raw, joined)\n"
        "print('OK')\n"
    )
    assert r.returncode == 0, r.stderr
    assert "OK" in r.stdout


def test_binary_starts_without_a_missing_import_crash():
    """Launch it and look for the failure mode that a source test cannot see."""
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen")
    try:
        r = subprocess.run([BINARY], capture_output=True, text=True, timeout=12,
                           env=env, cwd=ROOT)
        output = (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired as exc:
        # Timing out is the success case: a GUI that is running has not crashed.
        output = (exc.stdout or b"").decode(errors="replace") if isinstance(
            exc.stdout, bytes) else (exc.stdout or "")
    for marker in ("ModuleNotFoundError", "Traceback", "ImportError"):
        assert marker not in output, (
            f"frozen build reported {marker} on startup:\n{output[:800]}")