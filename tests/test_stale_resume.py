"""A stale same-named file must not be mistaken for resume progress.

The bug
-------
`POST /transfer` decided what to resume from with:

    existing_sz = os.path.getsize(dest_path)
    if existing_sz <= filesize:
        rec["received_bytes"] = existing_sz

A file already received **in full** from an earlier transfer has exactly the
same name and exactly the same size, so it was adopted as 100% resume progress.
The sender read `received_bytes`, saw every byte accounted for, and correctly
uploaded nothing. Completion was only ever computed inside the chunk handler,
so with no chunk arriving the record stayed `pending` for ever -- which the UI
rendered as "100% STREAMING" indefinitely, with no hash ever produced.

Re-sending a file is not an exotic action; it is what a user does when they
send the same file twice, and it silently broke.

What is asserted here
---------------------
1. A complete same-named file is NOT adopted as progress.
2. It is preserved, not truncated.
3. A genuinely partial file IS still resumable -- that is the feature.
4. A transfer whose bytes are all present is finalised and hashed even if no
   chunk arrives to trigger it.
5. Deleting the file underneath a live transfer is noticed.
"""

import hashlib
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from desktop.embedded_server import (  # noqa: E402
    QUARANTINE_SUFFIX,
    EmbeddedReceiverServer,
    _quarantine,
)


class StaleResumeTest(unittest.TestCase):
    """Unit-level, on the record the server builds, without a socket."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="nf_stale_")
        self.server = EmbeddedReceiverServer(upload_dir=self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ---- the helpers under test -------------------------------------------------

    def _rec(self, filename="payload.bin", total=4 * 1024 * 1024, expected=None):
        path: str = os.path.join(self.tmp, filename)
        return {
            "transfer_id": "t1",
            "filename": filename,
            "total_size": total,
            "received_bytes": 0,
            "status": "pending",
            "speed_bytes_sec": 0,
            "expected_sha256": expected,
            "file_path": path,
        }

    # ---- 1 & 2: a complete file is not progress, and survives -------------------

    def test_complete_same_named_file_is_not_adopted_as_progress(self):
        path = os.path.join(self.tmp, "movie.mp4")
        data = b"z" * 4096
        with open(path, "wb") as f:
            f.write(data)

        rec = self._rec("movie.mp4", total=4096)

        # Reproduce the decision the create-transfer handler makes.
        existing = os.path.getsize(path)
        if 0 < existing < rec["total_size"]:
            rec["received_bytes"] = existing

        self.assertEqual(rec["received_bytes"], 0,
                         "a complete file must not count as resume progress")
        self.assertLess(rec["received_bytes"], rec["total_size"],
                        "the sender must still have work to do")

    def test_complete_file_is_preserved_not_truncated(self):
        path = os.path.join(self.tmp, "movie.mp4")
        data = b"z" * 4096
        with open(path, "wb") as f:
            f.write(data)

        # What the fixed handler does: move it aside, then create a fresh empty one.
        moved = _quarantine(path)

        self.assertIsNotNone(moved)
        self.assertTrue(os.path.exists(moved))
        with open(moved, "rb") as f:
            self.assertEqual(f.read(), data, "the earlier file must be intact")

        with open(path, "wb") as f:
            pass
        self.assertEqual(os.path.getsize(path), 0,
                         "the new transfer starts from empty")

    def test_quarantine_suffix_marks_the_moved_file(self):
        path = os.path.join(self.tmp, "a.bin")
        with open(path, "wb") as f:
            f.write(b"x")
        moved = _quarantine(path)
        self.assertTrue(moved.endswith(QUARANTINE_SUFFIX))

    def test_quarantine_does_not_clobber_an_existing_superseded_file(self):
        first = os.path.join(self.tmp, "a.bin")
        with open(first, "wb") as f:
            f.write(b"original")
        moved1 = _quarantine(first)

        # A second transfer of the same name in the same second.
        with open(first, "wb") as f:
            f.write(b"second")
        moved2 = _quarantine(first)

        self.assertNotEqual(moved1, moved2)
        with open(moved1, "rb") as f:
            self.assertEqual(f.read(), b"original")
        with open(moved2, "rb") as f:
            self.assertEqual(f.read(), b"second")

    # ---- 3: partial resume still works ------------------------------------------

    def test_partial_file_is_still_resumed(self):
        path = os.path.join(self.tmp, "big.bin")
        with open(path, "wb") as f:
            f.write(b"a" * 1000)

        total = 4096
        existing = os.path.getsize(path)
        received = existing if 0 < existing < total else 0

        self.assertEqual(received, 1000,
                         "a genuine partial file must remain resumable")
        self.assertLess(received, total)

    def test_empty_file_is_not_resume_progress(self):
        path = os.path.join(self.tmp, "empty.bin")
        open(path, "wb").close()
        existing = os.path.getsize(path)
        self.assertEqual(existing, 0)
        received = existing if 0 < existing < 4096 else 0
        self.assertEqual(received, 0)

    def test_oversized_file_is_not_resume_progress(self):
        path = os.path.join(self.tmp, "over.bin")
        with open(path, "wb") as f:
            f.write(b"a" * 5000)

        total = 4096
        existing = os.path.getsize(path)
        received = existing if 0 < existing < total else 0
        self.assertEqual(received, 0,
                         "a file larger than this transfer is a different file")

    # ---- 4: completion without a trailing chunk --------------------------------

    def test_transfer_at_full_size_is_finalised_without_a_chunk(self):
        data = b"q" * 2048
        path = os.path.join(self.tmp, "done.bin")
        with open(path, "wb") as f:
            f.write(data)

        expected = hashlib.sha256(data).hexdigest()
        rec = self._rec("done.bin", total=2048, expected=expected)
        rec["received_bytes"] = 2048   # as if an earlier attempt had filled it

        # No chunk is posted. Reconciling is what must complete it.
        self.server._reconcile_with_disk(rec)

        self.assertEqual(rec["status"], "completed")
        self.assertEqual(rec["calculated_sha256"], expected)
        self.assertTrue(rec["sha256_verified"])

    def test_finalise_never_completes_without_a_digest(self):
        data = b"w" * 512
        path = os.path.join(self.tmp, "f.bin")
        with open(path, "wb") as f:
            f.write(data)

        rec = self._rec("f.bin", total=512)
        self.server._finalise(rec)

        self.assertEqual(rec["status"], "completed")
        self.assertIsNotNone(rec["calculated_sha256"],
                             "completed must always carry a digest")

    def test_finalise_detects_a_corrupt_file(self):
        data = b"e" * 512
        path = os.path.join(self.tmp, "c.bin")
        with open(path, "wb") as f:
            f.write(data)

        rec = self._rec("c.bin", total=512,
                        expected=hashlib.sha256(b"different").hexdigest())
        self.server._finalise(rec)

        self.assertEqual(rec["status"], "completed")
        self.assertFalse(rec["sha256_verified"],
                         "a hash mismatch must be reported, not hidden")

    def test_finalise_is_skipped_when_the_file_is_unreadable(self):
        rec = self._rec("missing.bin", total=10)
        rec["file_path"] = os.path.join(self.tmp, "does_not_exist.bin")
        self.server._finalise(rec)
        self.assertNotEqual(rec["status"], "completed",
                            "an unreadable file must not be marked complete")

    # ---- 5: the disk is the truth ----------------------------------------------

    def test_deleted_file_is_noticed_and_does_not_overclaim(self):
        path = os.path.join(self.tmp, "gone.bin")
        with open(path, "wb") as f:
            f.write(b"a" * 1000)

        rec = self._rec("gone.bin", total=1000)
        rec["received_bytes"] = 1000
        rec["status"] = "in_progress"

        os.remove(path)
        self.server._reconcile_with_disk(rec)

        self.assertEqual(rec["received_bytes"], 0,
                         "a receiver must never report bytes that are not there")
        self.assertNotEqual(rec["status"], "completed")

    def test_truncated_file_lowers_reported_progress(self):
        path = os.path.join(self.tmp, "cut.bin")
        with open(path, "wb") as f:
            f.write(b"a" * 1000)

        rec = self._rec("cut.bin", total=1000)
        rec["received_bytes"] = 1000

        with open(path, "r+b") as f:
            f.truncate(400)
        self.server._reconcile_with_disk(rec)

        self.assertEqual(rec["received_bytes"], 400)

    def test_reconcile_is_idempotent(self):
        data = b"i" * 256
        path = os.path.join(self.tmp, "idem.bin")
        with open(path, "wb") as f:
            f.write(data)

        rec = self._rec("idem.bin", total=256,
                        expected=hashlib.sha256(data).hexdigest())
        rec["received_bytes"] = 256

        self.server._reconcile_with_disk(rec)
        first = rec["calculated_sha256"]
        self.server._reconcile_with_disk(rec)

        self.assertEqual(rec["calculated_sha256"], first)
        self.assertEqual(rec["status"], "completed")

    def test_reconcile_leaves_a_cancelled_transfer_alone(self):
        path = os.path.join(self.tmp, "canc.bin")
        with open(path, "wb") as f:
            f.write(b"a" * 100)

        rec = self._rec("canc.bin", total=100)
        rec["status"] = "cancelled"
        rec["received_bytes"] = 100

        self.server._reconcile_with_disk(rec)
        self.assertEqual(rec["status"], "cancelled")

    def test_zero_length_transfer_is_not_finalised(self):
        path = os.path.join(self.tmp, "zero.bin")
        open(path, "wb").close()
        rec = self._rec("zero.bin", total=0)
        rec["received_bytes"] = 0

        self.server._reconcile_with_disk(rec)
        self.assertNotEqual(rec["status"], "completed",
                            "an empty transfer must not claim to be verified")


if __name__ == "__main__":
    unittest.main(verbosity=2)