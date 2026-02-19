(() => {
    const statusEl = document.getElementById("status");
    const reconnectBtn = document.getElementById("reconnect");
    const terminalEl = document.getElementById("terminal");

    const term = new Terminal({
        cursorBlink: true,
        fontFamily: '"Cascadia Mono", "Consolas", monospace',
        fontSize: 15,
        rows: 30,
        convertEol: false,
        theme: {
            background: "#04080f",
            foreground: "#d5e6f7",
        },
    });

    term.open(terminalEl);
    term.focus();

    let ws = null;
    let connected = false;
    let inputBuffer = "";

    const wsUrl = (() => {
        const scheme = window.location.protocol === "https:" ? "wss" : "ws";
        return `${scheme}://${window.location.host}/ws`;
    })();

    function setConnected(isConnected) {
        connected = isConnected;
        statusEl.textContent = isConnected ? "Connected" : "Disconnected";
        statusEl.className = `status ${isConnected ? "connected" : "disconnected"}`;
    }

    function writeSystem(message) {
        term.write(`\r\n\x1b[2m${message}\x1b[0m\r\n`);
    }

    function connect() {
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
            return;
        }

        ws = new WebSocket(wsUrl);

        ws.addEventListener("open", () => {
            setConnected(true);
            term.focus();
        });

        ws.addEventListener("message", (event) => {
            if (typeof event.data === "string") {
                term.write(event.data);
            }
        });

        ws.addEventListener("close", () => {
            setConnected(false);
            inputBuffer = "";
            writeSystem("Connection closed.");
        });

        ws.addEventListener("error", () => {
            setConnected(false);
            inputBuffer = "";
            writeSystem("Connection error.");
        });
    }

    function isPrintable(ch) {
        const code = ch.charCodeAt(0);
        return code >= 0x20 && code !== 0x7f;
    }

    term.onData((data) => {
        if (!connected || !ws || ws.readyState !== WebSocket.OPEN) {
            return;
        }

        for (const ch of data) {
            if (ch === "\r") {
                ws.send(inputBuffer);
                term.write("\r\n");
                inputBuffer = "";
                continue;
            }

            if (ch === "\u007f") {
                if (inputBuffer.length > 0) {
                    inputBuffer = inputBuffer.slice(0, -1);
                    term.write("\b \b");
                }
                continue;
            }

            if (isPrintable(ch)) {
                inputBuffer += ch;
                term.write(ch);
            }
        }
    });

    reconnectBtn.addEventListener("click", () => {
        if (ws) {
            ws.close();
        }
        connect();
    });

    window.addEventListener("beforeunload", () => {
        if (ws) {
            ws.close();
        }
    });

    connect();
})();
