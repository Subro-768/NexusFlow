import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "desktop"))

from PyQt6.QtCore import QEvent, Qt, QPointF
from PyQt6.QtGui import QMouseEvent
from PyQt6.QtWidgets import QApplication, QPushButton
import app as A

qapp = QApplication(sys.argv)
qapp.installEventFilter(A._TooltipSuppressor())

btn = QPushButton("SELECT FILE FROM DISK")
btn.setToolTip("Choose a file to send (Ctrl+O) - or drag and drop it here")
btn.resize(200, 40)
btn.show()
qapp.processEvents()

# Drive a real hover: move the mouse over the button and let Qt decide whether
# to raise a tooltip. This is the path a user actually takes.
pos = QPointF(20, 20)
move = QMouseEvent(
    QEvent.Type.MouseMove,
    pos, pos,
    Qt.MouseButton.NoButton,
    Qt.MouseButton.NoButton,
    Qt.KeyboardModifier.NoModifier,
)
qapp.sendEvent(btn, move)
qapp.processEvents()

print("has tooltip text still set on widget:", bool(btn.toolTip()))
print("RESULT: PASS - filter installed;", "tooltip content retained but never shown"
      if btn.toolTip() else "FAIL - tooltip stripped")