"""The Receiver Hub's endpoint disclosure must be collapsed by default.

The address was removed from this screen on privacy grounds, so anything that
puts it on screen without being asked for is a regression. These tests drive the
real button and check what lands in the panel.
"""

import os
import sys

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
def screen(qapp):
    return A.ReceiverScreen()


def test_hidden_until_asked(screen):
    assert screen.endpoint_box.isVisibleTo(screen) is False, \
        "the address is on screen without the user opening the dropdown"
    assert "IP" not in screen.btn_endpoint.text().split("(")[0], \
        "collapsed button label already leaks the address"


def test_expanding_shows_address_and_port(screen, monkeypatch):
    monkeypatch.setattr(sys.modules[type(screen).__module__], "get_local_ips",
                        lambda: ["10.3.162.60"])
    screen.btn_endpoint.setChecked(True)

    assert screen.endpoint_box.isVisibleTo(screen) is True
    text = screen.lbl_endpoints.text()
    assert "10.3.162.60" in text
    assert ":8000" in text or ":%d" % screen.server.port in text


def test_loopback_is_not_offered_as_a_reachable_address(screen, monkeypatch):
    """127.0.0.1 is not a way to reach this device from another machine."""
    # Patch the module object the screen's class actually came from: app.py is
    # importable as either "app" or "desktop.app", and patching the other one
    # silently does nothing.
    monkeypatch.setattr(sys.modules[type(screen).__module__], "get_local_ips",
                        lambda: ["127.0.0.1"])
    screen.btn_endpoint.setChecked(True)
    text = screen.lbl_endpoints.text()
    assert "127.0.0.1" not in text, f"loopback offered as an endpoint: {text!r}"
    assert "No network address" in text


def test_collapsing_hides_it_again(screen):
    screen.btn_endpoint.setChecked(True)
    screen.btn_endpoint.setChecked(False)
    assert screen.endpoint_box.isVisibleTo(screen) is False
    assert screen.btn_endpoint.text().startswith("\u25be"), screen.btn_endpoint.text()
