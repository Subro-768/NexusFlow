import os
import uuid
import logging
import socket
from typing import Optional, List
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request, Header, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from server.db import (
    init_db, create_transfer, get_transfer,
    update_transfer_progress, update_transfer_status, list_transfers
)
from server.storage import (
    get_file_paths, allocate_file, write_chunk, calculate_sha256, get_upload_dir
)

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("transfer-server")

def get_local_ips() -> List[str]:
    ips = []
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    # Fallback probe
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip not in ips and not ip.startswith("127."):
            ips.append(ip)
    except Exception:
        pass
    return ips if ips else ["127.0.0.1"]

@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    upload_dir = get_upload_dir()
    logger.info("==================================================")
    logger.info("Resumable File Transfer Server Started")
    logger.info(f"Upload Directory: {upload_dir}")
    logger.info(f"Local IP Addresses: {', '.join(get_local_ips())}")
    logger.info("==================================================")
    yield

app = FastAPI(
    title="Resumable File Transfer Server",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class CreateTransferRequest(BaseModel):
    filename: str = Field(..., min_length=1, description="Original filename")
    filesize: int = Field(..., gt=0, description="Total size in bytes")
    checksum: Optional[str] = Field(None, description="Expected SHA-256 hash (optional)")
    chunk_size: Optional[int] = Field(1048576, description="Recommended chunk size in bytes")

class TransferResponse(BaseModel):
    transfer_id: str
    filename: str
    total_size: int
    received_bytes: int
    chunk_size: int
    status: str
    expected_sha256: Optional[str] = None
    calculated_sha256: Optional[str] = None

@app.get("/")
@app.get("/health")
def health_check():
    return {
        "status": "online",
        "service": "resumable-file-transfer",
        "local_ips": get_local_ips(),
        "upload_dir": get_upload_dir()
    }

@app.post("/transfer", response_model=TransferResponse, status_code=status.HTTP_201_CREATED)
def init_transfer(req: CreateTransferRequest):
    transfer_id = uuid.uuid4().hex[:12]
    clean_name, full_path = get_file_paths(transfer_id, req.filename)
    
    # Pre-allocate or prepare file structure
    allocate_file(full_path, req.filesize)
    
    transfer = create_transfer(
        transfer_id=transfer_id,
        filename=req.filename,
        sanitized_filename=clean_name,
        file_path=full_path,
        total_size=req.filesize,
        chunk_size=req.chunk_size or 1048576,
        expected_sha256=req.checksum.lower() if req.checksum else None
    )
    
    logger.info(f"[CREATE] Transfer {transfer_id} created for '{req.filename}' ({req.filesize:,} bytes)")
    return transfer

@app.get("/transfer/{transfer_id}/status", response_model=TransferResponse)
def get_status(transfer_id: str):
    transfer = get_transfer(transfer_id)
    if not transfer:
        raise HTTPException(status_code=404, detail="Transfer not found")
    return transfer

@app.post("/transfer/{transfer_id}/chunk")
async def upload_chunk(
    transfer_id: str,
    request: Request,
    x_start_byte: int = Header(..., alias="X-Start-Byte"),
    x_end_byte: int = Header(..., alias="X-End-Byte"),
    x_total_size: Optional[int] = Header(None, alias="X-Total-Size")
):
    transfer = get_transfer(transfer_id)
    if not transfer:
        logger.warning(f"[REJECT] Transfer {transfer_id} not found")
        raise HTTPException(status_code=404, detail="Transfer session not found")
        
    if transfer["status"] == "CANCELLED":
        logger.warning(f"[REJECT] Transfer {transfer_id} has been cancelled")
        raise HTTPException(status_code=400, detail="Transfer has been cancelled")
        
    total_size = transfer["total_size"]
    if x_total_size is not None and x_total_size != total_size:
        raise HTTPException(status_code=400, detail="Total size mismatch with transfer session")
        
    expected_chunk_len = x_end_byte - x_start_byte + 1
    if x_start_byte < 0 or x_end_byte >= total_size or x_start_byte > x_end_byte:
        logger.warning(f"[REJECT] Invalid byte range {x_start_byte}-{x_end_byte} for total size {total_size}")
        raise HTTPException(status_code=400, detail="Invalid byte range")
        
    # Read chunk binary data directly from stream
    body = await request.body()
    body_len = len(body)
    
    if body_len != expected_chunk_len:
        logger.warning(f"[REJECT] Payload size ({body_len}) does not match header range length ({expected_chunk_len})")
        raise HTTPException(
            status_code=400,
            detail=f"Payload size ({body_len}) does not match range length ({expected_chunk_len})"
        )
        
    file_path = transfer["file_path"]
    if not os.path.exists(file_path):
        allocate_file(file_path, total_size)
        
    # Write at seek position and flush
    write_chunk(file_path, x_start_byte, body)
    
    new_confirmed_received = max(transfer["received_bytes"], x_end_byte + 1)
    
    # Check if transfer complete
    if new_confirmed_received >= total_size:
        logger.info(f"[COMPLETE] Transfer {transfer_id} received all {total_size:,} bytes. Verifying SHA-256...")
        calc_sha = calculate_sha256(file_path)
        expected_sha = transfer["expected_sha256"]
        
        if expected_sha:
            if calc_sha.lower() == expected_sha.lower():
                logger.info(f"[VERIFIED] SHA-256 matches: {calc_sha}")
                update_transfer_progress(transfer_id, x_start_byte, x_end_byte, new_confirmed_received, "COMPLETED")
                update_transfer_status(transfer_id, "COMPLETED", calc_sha)
            else:
                logger.error(f"[INTEGRITY ERROR] SHA-256 mismatch! Expected {expected_sha}, Got {calc_sha}")
                update_transfer_progress(transfer_id, x_start_byte, x_end_byte, new_confirmed_received, "FAILED")
                update_transfer_status(transfer_id, "FAILED", calc_sha)
        else:
            logger.info(f"[VERIFIED] Transfer complete without prior checksum. Calculated SHA-256: {calc_sha}")
            update_transfer_progress(transfer_id, x_start_byte, x_end_byte, new_confirmed_received, "COMPLETED")
            update_transfer_status(transfer_id, "COMPLETED", calc_sha)
    else:
        update_transfer_progress(transfer_id, x_start_byte, x_end_byte, new_confirmed_received, "TRANSFERRING")
        logger.info(
            f"[CHUNK] Transfer {transfer_id}: bytes {x_start_byte:,}-{x_end_byte:,} "
            f"({(new_confirmed_received / total_size)*100:.1f}%)"
        )
        
    updated = get_transfer(transfer_id)
    return {
        "transfer_id": transfer_id,
        "received_bytes": updated["received_bytes"],
        "total_size": updated["total_size"],
        "status": updated["status"],
        "calculated_sha256": updated["calculated_sha256"],
        "sha256_verified": (updated["status"] == "COMPLETED")
    }

@app.post("/transfer/{transfer_id}/cancel")
def cancel_transfer(transfer_id: str):
    transfer = get_transfer(transfer_id)
    if not transfer:
        raise HTTPException(status_code=404, detail="Transfer not found")
    update_transfer_status(transfer_id, "CANCELLED")
    logger.info(f"[CANCEL] Transfer {transfer_id} cancelled by user")
    return {"transfer_id": transfer_id, "status": "CANCELLED"}

@app.get("/transfers", response_model=List[TransferResponse])
def get_all_transfers(limit: int = 50):
    return list_transfers(limit)
