import os
import shutil
import hashlib
import tempfile
import pytest
from fastapi.testclient import TestClient

# Configure test environment paths before importing app
TEST_DIR = tempfile.mkdtemp(prefix="resumable_test_")
os.environ["TRANSFER_DB_PATH"] = os.path.join(TEST_DIR, "test_transfers.db")
os.environ["TRANSFER_UPLOAD_DIR"] = os.path.join(TEST_DIR, "uploads")

from server.main import app
from server.db import init_db

@pytest.fixture(autouse=True)
def setup_and_teardown():
    init_db()
    yield
    # Cleanup after tests
    # (Leaving directory intact if needed for inspection, but can remove on finish)

@pytest.fixture
def client():
    return TestClient(app)

def test_health_check(client):
    res = client.get("/health")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "online"
    assert "local_ips" in data

def test_small_file_transfer(client):
    content = b"Hello, this is a resumable transfer test payload!"
    file_size = len(content)
    sha256_hash = hashlib.sha256(content).hexdigest()
    
    # 1. Create transfer
    create_res = client.post("/transfer", json={
        "filename": "hello.txt",
        "filesize": file_size,
        "checksum": sha256_hash,
        "chunk_size": 16
    })
    assert create_res.status_code == 201
    transfer_id = create_res.json()["transfer_id"]
    
    # 2. Upload in chunks of 16 bytes
    chunk_size = 16
    for start in range(0, file_size, chunk_size):
        end = min(start + chunk_size - 1, file_size - 1)
        chunk_data = content[start:end+1]
        
        chunk_res = client.post(
            f"/transfer/{transfer_id}/chunk",
            headers={
                "X-Start-Byte": str(start),
                "X-End-Byte": str(end),
                "X-Total-Size": str(file_size)
            },
            content=chunk_data
        )
        assert chunk_res.status_code == 200
        
    # 3. Check final status
    status_res = client.get(f"/transfer/{transfer_id}/status")
    assert status_res.status_code == 200
    status_data = status_res.json()
    assert status_data["status"] == "COMPLETED"
    assert status_data["received_bytes"] == file_size
    assert status_data["calculated_sha256"] == sha256_hash

def test_interrupted_transfer_and_resume(client):
    # Create 5MB payload
    chunk_size = 1024 * 1024 # 1 MB
    num_chunks = 5
    file_size = num_chunks * chunk_size
    content = os.urandom(file_size)
    sha256_hash = hashlib.sha256(content).hexdigest()
    
    # 1. Create transfer
    create_res = client.post("/transfer", json={
        "filename": "large_test.bin",
        "filesize": file_size,
        "checksum": sha256_hash,
        "chunk_size": chunk_size
    })
    assert create_res.status_code == 201
    transfer_id = create_res.json()["transfer_id"]
    
    # 2. Transfer only first 2 chunks (simulate interruption at 40%)
    for i in range(2):
        start = i * chunk_size
        end = start + chunk_size - 1
        chunk_res = client.post(
            f"/transfer/{transfer_id}/chunk",
            headers={
                "X-Start-Byte": str(start),
                "X-End-Byte": str(end),
                "X-Total-Size": str(file_size)
            },
            content=content[start:end+1]
        )
        assert chunk_res.status_code == 200
        
    # 3. Query status to verify stored offset
    status_res = client.get(f"/transfer/{transfer_id}/status")
    status_data = status_res.json()
    assert status_data["status"] == "TRANSFERRING"
    assert status_data["received_bytes"] == 2 * chunk_size
    
    # 4. Resume transfer from received_bytes (do not re-send chunks 0 and 1)
    resume_offset = status_data["received_bytes"]
    assert resume_offset == 2 * 1024 * 1024
    
    for start in range(resume_offset, file_size, chunk_size):
        end = min(start + chunk_size - 1, file_size - 1)
        chunk_res = client.post(
            f"/transfer/{transfer_id}/chunk",
            headers={
                "X-Start-Byte": str(start),
                "X-End-Byte": str(end),
                "X-Total-Size": str(file_size)
            },
            content=content[start:end+1]
        )
        assert chunk_res.status_code == 200
        
    # 5. Verify final status and SHA-256
    final_status = client.get(f"/transfer/{transfer_id}/status").json()
    assert final_status["status"] == "COMPLETED"
    assert final_status["received_bytes"] == file_size
    assert final_status["calculated_sha256"] == sha256_hash

def test_duplicate_chunk_handling(client):
    content = b"ABCDEFGHIJ" * 100 # 1000 bytes
    file_size = len(content)
    sha256_hash = hashlib.sha256(content).hexdigest()
    
    create_res = client.post("/transfer", json={
        "filename": "dup_test.bin",
        "filesize": file_size,
        "checksum": sha256_hash,
        "chunk_size": 250
    })
    transfer_id = create_res.json()["transfer_id"]
    
    # Send chunk 0 (0-249)
    client.post(
        f"/transfer/{transfer_id}/chunk",
        headers={"X-Start-Byte": "0", "X-End-Byte": "249", "X-Total-Size": str(file_size)},
        content=content[0:250]
    )
    
    # Send chunk 0 AGAIN (duplicate)
    dup_res = client.post(
        f"/transfer/{transfer_id}/chunk",
        headers={"X-Start-Byte": "0", "X-End-Byte": "249", "X-Total-Size": str(file_size)},
        content=content[0:250]
    )
    assert dup_res.status_code == 200
    
    # Complete the rest of the chunks
    for start in range(250, file_size, 250):
        end = min(start + 250 - 1, file_size - 1)
        client.post(
            f"/transfer/{transfer_id}/chunk",
            headers={"X-Start-Byte": str(start), "X-End-Byte": str(end), "X-Total-Size": str(file_size)},
            content=content[start:end+1]
        )
        
    final_status = client.get(f"/transfer/{transfer_id}/status").json()
    assert final_status["status"] == "COMPLETED"
    assert final_status["calculated_sha256"] == sha256_hash

def test_invalid_ranges_rejected(client):
    create_res = client.post("/transfer", json={
        "filename": "invalid_test.bin",
        "filesize": 1000,
        "chunk_size": 100
    })
    transfer_id = create_res.json()["transfer_id"]
    
    # 1. Negative start byte
    res1 = client.post(
        f"/transfer/{transfer_id}/chunk",
        headers={"X-Start-Byte": "-10", "X-End-Byte": "50"},
        content=b"x" * 61
    )
    assert res1.status_code == 400
    
    # 2. End byte exceeds total size
    res2 = client.post(
        f"/transfer/{transfer_id}/chunk",
        headers={"X-Start-Byte": "900", "X-End-Byte": "1500"},
        content=b"x" * 601
    )
    assert res2.status_code == 400
    
    # 3. Payload size mismatch with headers
    res3 = client.post(
        f"/transfer/{transfer_id}/chunk",
        headers={"X-Start-Byte": "0", "X-End-Byte": "99"},
        content=b"x" * 50 # expected 100 bytes
    )
    assert res3.status_code == 400

def test_sha256_mismatch_detected(client):
    content = b"Correct file content payload."
    file_size = len(content)
    wrong_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    
    create_res = client.post("/transfer", json={
        "filename": "mismatch.txt",
        "filesize": file_size,
        "checksum": wrong_hash,
        "chunk_size": file_size
    })
    transfer_id = create_res.json()["transfer_id"]
    
    chunk_res = client.post(
        f"/transfer/{transfer_id}/chunk",
        headers={"X-Start-Byte": "0", "X-End-Byte": str(file_size - 1)},
        content=content
    )
    assert chunk_res.status_code == 200
    data = chunk_res.json()
    assert data["status"] == "FAILED"
    assert data["sha256_verified"] is False

def test_path_traversal_sanitization(client):
    dangerous_filename = "../../../etc/cron.d/hack_attempt.sh"
    create_res = client.post("/transfer", json={
        "filename": dangerous_filename,
        "filesize": 100,
        "chunk_size": 100
    })
    assert create_res.status_code == 201
    transfer_id = create_res.json()["transfer_id"]
    
    # Verify file is contained inside uploads directory and doesn't escape
    transfer = client.get(f"/transfer/{transfer_id}/status").json()
    file_path = transfer_id + "_hack_attempt.sh"
    upload_dir = os.path.abspath(os.environ["TRANSFER_UPLOAD_DIR"])
    
    from server.db import get_transfer
    db_rec = get_transfer(transfer_id)
    assert db_rec["file_path"].startswith(upload_dir)
    assert ".." not in db_rec["sanitized_filename"]

def test_server_restart_persistence(client):
    # Simulate partial transfer, server restart, and continuation
    file_size = 3 * 1024 * 1024 # 3 MB
    chunk_size = 1024 * 1024 # 1 MB
    content = os.urandom(file_size)
    sha256_hash = hashlib.sha256(content).hexdigest()
    
    # Phase 1: Upload chunk 0 (0-1MB)
    create_res = client.post("/transfer", json={
        "filename": "restart_test.bin",
        "filesize": file_size,
        "checksum": sha256_hash,
        "chunk_size": chunk_size
    })
    transfer_id = create_res.json()["transfer_id"]
    
    client.post(
        f"/transfer/{transfer_id}/chunk",
        headers={"X-Start-Byte": "0", "X-End-Byte": str(chunk_size - 1), "X-Total-Size": str(file_size)},
        content=content[0:chunk_size]
    )
    
    # Phase 2: Simulate complete server reboot by creating a brand new TestClient instance
    from server.main import app
    new_client = TestClient(app)
    
    # Query status after restart
    status_res = new_client.get(f"/transfer/{transfer_id}/status")
    assert status_res.status_code == 200
    status_data = status_res.json()
    assert status_data["received_bytes"] == chunk_size
    assert status_data["status"] == "TRANSFERRING"
    
    # Phase 3: Resume remaining chunks (1MB to 3MB)
    for start in range(chunk_size, file_size, chunk_size):
        end = min(start + chunk_size - 1, file_size - 1)
        res = new_client.post(
            f"/transfer/{transfer_id}/chunk",
            headers={"X-Start-Byte": str(start), "X-End-Byte": str(end), "X-Total-Size": str(file_size)},
            content=content[start:end+1]
        )
        assert res.status_code == 200
        
    final_status = new_client.get(f"/transfer/{transfer_id}/status").json()
    assert final_status["status"] == "COMPLETED"
    assert final_status["received_bytes"] == file_size
    assert final_status["calculated_sha256"] == sha256_hash

def test_multiple_interruptions_and_restarts(client):
    file_size = 10 * 1024 * 1024 # 10 MB
    chunk_size = 1024 * 1024 # 1 MB
    content = os.urandom(file_size)
    sha256_hash = hashlib.sha256(content).hexdigest()
    
    # 1. Start transfer
    create_res = client.post("/transfer", json={
        "filename": "multi_interrupt.bin",
        "filesize": file_size,
        "checksum": sha256_hash,
        "chunk_size": chunk_size
    })
    transfer_id = create_res.json()["transfer_id"]
    
    # Interruption 1 at 30% (3 chunks)
    for i in range(3):
        start = i * chunk_size
        end = start + chunk_size - 1
        client.post(
            f"/transfer/{transfer_id}/chunk",
            headers={"X-Start-Byte": str(start), "X-End-Byte": str(end), "X-Total-Size": str(file_size)},
            content=content[start:end+1]
        )
        
    status1 = client.get(f"/transfer/{transfer_id}/status").json()
    assert status1["received_bytes"] == 3 * chunk_size
    
    # Simulate restart 1 & resume from 30% to 60% (chunks 3..5)
    from server.main import app
    client2 = TestClient(app)
    resume1 = client2.get(f"/transfer/{transfer_id}/status").json()["received_bytes"]
    assert resume1 == 3 * 1024 * 1024
    
    for start in range(resume1, 6 * chunk_size, chunk_size):
        end = start + chunk_size - 1
        client2.post(
            f"/transfer/{transfer_id}/chunk",
            headers={"X-Start-Byte": str(start), "X-End-Byte": str(end), "X-Total-Size": str(file_size)},
            content=content[start:end+1]
        )
        
    status2 = client2.get(f"/transfer/{transfer_id}/status").json()
    assert status2["received_bytes"] == 6 * chunk_size
    
    # Simulate restart 2 & resume from 60% to 100% (chunks 6..9)
    client3 = TestClient(app)
    resume2 = client3.get(f"/transfer/{transfer_id}/status").json()["received_bytes"]
    assert resume2 == 6 * 1024 * 1024
    
    for start in range(resume2, file_size, chunk_size):
        end = min(start + chunk_size - 1, file_size - 1)
        client3.post(
            f"/transfer/{transfer_id}/chunk",
            headers={"X-Start-Byte": str(start), "X-End-Byte": str(end), "X-Total-Size": str(file_size)},
            content=content[start:end+1]
        )
        
    final_status = client3.get(f"/transfer/{transfer_id}/status").json()
    assert final_status["status"] == "COMPLETED"
    assert final_status["received_bytes"] == file_size
    assert final_status["calculated_sha256"] == sha256_hash
