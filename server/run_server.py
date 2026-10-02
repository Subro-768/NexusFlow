#!/usr/bin/env python3
import sys
import os
import uvicorn
import socket

# Add root directory to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    host = "0.0.0.0"
    
    print("=" * 60)
    print("  Resumable File Transfer Server")
    print("=" * 60)
    print(f"  Listening on: http://{host}:{port}")
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
        print(f"  Local Network Address: http://{local_ip}:{port}")
    except Exception:
        pass
    print("=" * 60)
    
    uvicorn.run("server.main:app", host=host, port=port, log_level="info")

if __name__ == "__main__":
    main()
