"""The Linux sender must be able to un-pick a file.

Android has a NEW control that clears the selection; the Linux sender had none,
so once a file was chosen the only ways out were to send it or restart the app.
These tests drive the real buttons and assert the card returns to its empty
state -- and that the control is disabled when there is nothing to remove, so it
cannot sit there looking active.
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
from PyQt6.QtWidgets import QApplication  # noqa: E402

import app as A  # noqa: E402


@pytest.fixture(scope="module")
def qapp():
    return QApplication.instance() or QApplication([])


@pytest.fixture
def sender(qapp, tmp_path):
    s = A.SenderScreen()
    payload = tmp_path / "chosen.bin"
    payload.write_bytes(b"x" * 4096)
    yield s, str(payload)
    s.clear_file()


def test_remove_is_disabled_until_a_file_is_chosen(sender):
    screen, _ = sender
    assert screen.btn_clear.isEnabled() is False, \
        "REMOVE is active with no file selected"


def test_choosing_a_file_enables_remove_and_remove_clears_it(sender):
    screen, payload = sender

    screen.set_file(payload)
    assert screen.btn_clear.isEnabled() is True, \
        "REMOVE stayed disabled after a file was chosen"
    assert screen.lbl_file.text() != "NO FILE SELECTED"

    screen.clear_file()

    assert screen.lbl_file.text() == "NO FILE SELECTED"
    assert screen.selected_file is None
    assert screen.active_file is None
    assert screen.lbl_vol.text() == "0 B / 0 B (0%)"
    assert screen.lbl_badge.text() == "STANDBY"
    assert screen.prog_bar.value() == 0

    # Nothing to send, nothing to remove, nothing to control.
    assert screen.btn_start.isEnabled() is False
    assert screen.btn_clear.isEnabled() is False
    assert screen.btn_pause.isEnabled() is False
    assert screen.btn_cancel.isEnabled() is False


def test_remove_then_choose_again_works(sender):
    """Clearing must not wedge the picker."""
    screen, payload = sender
    screen.set_file(payload)
    screen.clear_file()
    screen.set_file(payload)

    assert screen.btn_clear.isEnabled() is True
    assert screen.btn_start.isEnabled() is True
    assert os.path.basename(payload) in screen.lbl_file.text() or \
        screen.lbl_file.toolTip() == payload


def test_clearing_without_a_file_is_harmless(sender):
    screen, _ = sender
    screen.clear_file()
    screen.clear_file()
    assert screen.lbl_file.text() == "NO FILE SELECTED"

def test_escape_clears_the_selection_when_nothing_is_running(sender):
    """Esc is advertised on the REMOVE tooltip, so it has to do this."""
    screen, payload = sender
    screen.set_file(payload)
    screen._escape_action()
    assert screen.lbl_file.text() == "NO FILE SELECTED"


def test_escape_is_safe_with_nothing_selected(sender):
    screen, _ = sender
    screen._escape_action()          # must not raise
    assert screen.lbl_file.text() == "NO FILE SELECTED"


def test_escape_prefers_cancelling_a_running_transfer(sender, monkeypatch):
    """With a transfer in flight, Esc must stop the transfer, not clear the card."""
    screen, payload = sender
    screen.set_file(payload)
    cancelled = []
    monkeypatch.setattr(screen, "cancel_transfer", lambda: cancelled.append(True))

    class FakeWorker:
        def isRunning(self):
            return True

    screen.active_worker = FakeWorker()
    screen._escape_action()

    assert cancelled == [True], "Esc cleared the card instead of cancelling"
    screen.active_worker = None
