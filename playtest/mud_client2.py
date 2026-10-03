"""Second telnet client instance (demo guest). Same protocol as mud_client.py."""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mud_client

mud_client.OUT = os.path.join(mud_client.BASE, "out2.log")
mud_client.CMD = os.path.join(mud_client.BASE, "cmd2.txt")

if __name__ == "__main__":
    mud_client.main()
