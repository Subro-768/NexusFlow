"""Drive the Linux Hub's delete control against real files.

The row used to offer only Open and Show-in-folder, so a received file could not
be removed from the app at all. This clicks the real button, answers the real
confirmation dialog, and checks what is on disk afterwards -- including the
refusal path, where the user clicks Cancel and the file must survive.

Qt dialogs are answered by monkeypatching QMessageBox.exec, so no input is
injected into the GUI.
"""

import os
import sys
import tempfile

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
for candidate in (os.path.join(ROOT, "desktop"), ROOT):
    if candidate not in sys.path:
        sys.path.insert(0, candidate)

import pytest  # noqa: E402
from PyQt6.QtWidgets import QApplication, QMessageBox  # noqa: E402

import app as A  # noqa: E402


@pytest.fixture(scope="module")
def qapp():
    return QApplication.instance() or QApplication([])


@pytest.fixture
def hub(qapp, tmp_path):
    screen = A.ReceiverScreen()
    upload_dir = tmp_path / "rx"
    upload_dir.mkdir()
    screen.server.upload_dir = str(upload_dir)
    screen.refresh_received_files()
    yield screen, upload_dir
    screen.refresh_received_files()


def _rows(screen):
    """The file rows currently rendered in the Received Files list."""
    out = []
    layout = screen.files_layout
    for i in range(layout.count()):
        card = layout.itemAt(i).widget()
        if card is None:
            continue
        buttons = card.findChildren(QPushButton)
        out.append(buttons)
    return out


from PyQt6.QtWidgets import QPushButton  # noqa: E402


def test_delete_button_exists_on_each_row(qapp, hub):
    screen, upload_dir = hub
    (upload_dir / "one.bin").write_bytes(b"x" * 1000)
    (upload_dir / "two.bin").write_bytes(b"y" * 2000)
    screen.refresh_received_files()

    rows = _rows(screen)
    assert len(rows) == 2, f"expected two rows, got {len(rows)}"
    for buttons in rows:
        labels = [b.text() for b in buttons]
        assert any("\u2716" in t for t in labels), f"no delete button in row: {labels}"


def test_confirming_delete_removes_the_file(qapp, hub, monkeypatch):
    screen, upload_dir = hub
    target = upload_dir / "doomed.bin"
    target.write_bytes(b"z" * 4096)
    screen.refresh_received_files()

    seen = {}

    def fake_exec(self):
        # Confirm what the dialog would have said before answering it.
        seen["title"] = self.windowTitle()
        seen["text"] = self.text()
        seen["informative"] = self.informativeText()
        seen["default"] = self.defaultButton().text()
        return QMessageBox.StandardButton.Yes

    monkeypatch.setattr(QMessageBox, "exec", fake_exec)

    screen.delete_received_file(str(target), "doomed.bin")

    assert not target.exists(), "file survived a confirmed delete"
    assert "doomed.bin" in seen["text"], seen["text"]
    assert "cannot be undone" in seen["informative"], seen["informative"]
    # Cancel must be the default so Enter does not delete by accident.
    assert "Cancel" in seen["default"], seen["default"]
    # The row list rebuilt without it.
    assert all("doomed.bin" not in b.toolTip()
               for btns in _rows(screen) for b in btns)


def test_cancelling_the_dialog_keeps_the_file(qapp, hub, monkeypatch):
    screen, upload_dir = hub
    target = upload_dir / "safe.bin"
    target.write_bytes(b"k" * 500)
    screen.refresh_received_files()

    monkeypatch.setattr(QMessageBox, "exec", lambda self: QMessageBox.StandardButton.Cancel)

    screen.delete_received_file(str(target), "safe.bin")

    assert target.exists(), "Cancel deleted the file anyway"
    assert target.stat().st_size == 500


def test_missing_file_is_not_an_error(qapp, hub, monkeypatch):
    """A file removed behind the app's back must not raise a dialog."""
    screen, upload_dir = hub
    target = upload_dir / "ghost.bin"
    target.write_bytes(b"g" * 10)
    screen.refresh_received_files()
    os.remove(target)

    monkeypatch.setattr(QMessageBox, "exec", lambda self: QMessageBox.StandardButton.Yes)
    crashed = []

    def boom(self, *a, **k):
        crashed.append(a)

    monkeypatch.setattr(QMessageBox, "critical", boom)

    screen.delete_received_file(str(target), "ghost.bin")
    assert not crashed, "showed an error dialog for a file that was already gone"