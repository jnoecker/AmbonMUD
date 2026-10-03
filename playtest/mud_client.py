"""Persistent telnet driver for playtesting AmbonMUD demo.

Connects to host:port, strips telnet IAC + ANSI, appends output to out.log.
Polls cmd.txt for new lines and sends them. Line '##QUIT##' closes the client.
"""
import re
import socket
import sys
import threading
import time
import os

HOST = "mud.ambon.dev"
PORT = 4000
BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "out.log")
CMD = os.path.join(BASE, "cmd.txt")

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")

IAC = 255
DONT, DO, WONT, WILL, SB, SE = 254, 253, 252, 251, 250, 240


def strip_telnet(data: bytes, sock: socket.socket) -> bytes:
    """Strip telnet negotiation, refusing all options."""
    out = bytearray()
    i = 0
    while i < len(data):
        b = data[i]
        if b == IAC:
            if i + 1 >= len(data):
                break
            cmd = data[i + 1]
            if cmd in (DO, DONT, WILL, WONT):
                if i + 2 < len(data):
                    opt = data[i + 2]
                    try:
                        if cmd == DO:
                            sock.sendall(bytes([IAC, WONT, opt]))
                        elif cmd == WILL:
                            sock.sendall(bytes([IAC, DONT, opt]))
                    except OSError:
                        pass
                i += 3
            elif cmd == SB:
                # skip until IAC SE
                j = i + 2
                while j + 1 < len(data) and not (data[j] == IAC and data[j + 1] == SE):
                    j += 1
                i = j + 2
            else:
                i += 2
        else:
            out.append(b)
            i += 1
    return bytes(out)


def main():
    sock = socket.create_connection((HOST, PORT), timeout=15)
    sock.settimeout(0.5)
    stop = threading.Event()

    def log(text: str):
        with open(OUT, "a", encoding="utf-8", errors="replace") as f:
            f.write(text)
            f.flush()

    def reader():
        buf = b""
        while not stop.is_set():
            try:
                data = sock.recv(4096)
                if not data:
                    log("\n<<< CONNECTION CLOSED BY SERVER >>>\n")
                    stop.set()
                    return
                buf += strip_telnet(data, sock)
                text = buf.decode("utf-8", errors="replace")
                buf = b""
                text = ANSI.sub("", text).replace("\r", "")
                log(text)
            except socket.timeout:
                continue
            except OSError:
                log("\n<<< SOCKET ERROR >>>\n")
                stop.set()
                return

    t = threading.Thread(target=reader, daemon=True)
    t.start()

    # ensure files exist
    open(OUT, "a").close()
    open(CMD, "a").close()

    offset = 0
    log(f"<<< CONNECTED to {HOST}:{PORT} >>>\n")
    while not stop.is_set():
        try:
            with open(CMD, "r", encoding="utf-8") as f:
                f.seek(offset)
                new = f.read()
                offset = f.tell()
        except OSError:
            new = ""
        for line in new.splitlines():
            line = line.strip("\n")
            if line == "##QUIT##":
                log("\n<<< CLIENT QUIT >>>\n")
                stop.set()
                break
            log(f"\n>>> SENT: {line}\n")
            try:
                sock.sendall((line + "\r\n").encode("utf-8"))
            except OSError:
                stop.set()
                break
            time.sleep(0.3)
        time.sleep(0.25)

    try:
        sock.close()
    except OSError:
        pass


if __name__ == "__main__":
    main()
