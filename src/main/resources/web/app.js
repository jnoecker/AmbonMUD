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

    // Sidebar elements
    const hpBar = document.getElementById("hp-bar");
    const hpText = document.getElementById("hp-text");
    const manaBar = document.getElementById("mana-bar");
    const manaText = document.getElementById("mana-text");
    const levelVal = document.getElementById("level-val");
    const xpVal = document.getElementById("xp-val");
    const roomTitle = document.getElementById("room-title");
    const roomDesc = document.getElementById("room-desc");
    const exitsWrap = document.getElementById("exits-wrap");

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

    function updateVitals(data) {
        const hp = data.hp ?? 0;
        const maxHp = data.maxHp ?? 1;
        const mana = data.mana ?? 0;
        const maxMana = data.maxMana ?? 1;
        hpBar.style.width = `${Math.round((hp / Math.max(maxHp, 1)) * 100)}%`;
        hpText.textContent = `${hp} / ${maxHp}`;
        manaBar.style.width = `${Math.round((mana / Math.max(maxMana, 1)) * 100)}%`;
        manaText.textContent = `${mana} / ${maxMana}`;
        levelVal.textContent = data.level ?? "—";
        if (data.xp !== undefined) {
            xpVal.textContent = data.xp.toLocaleString();
        }
    }

    function updateRoomInfo(data) {
        roomTitle.textContent = data.title ?? "—";
        roomDesc.textContent = data.description ?? "";
        exitsWrap.innerHTML = "";
        const exits = data.exits ?? {};
        for (const [dir, roomId] of Object.entries(exits)) {
            const btn = document.createElement("button");
            btn.className = "exit-btn";
            btn.textContent = dir;
            btn.title = roomId;
            btn.addEventListener("click", () => {
                if (ws && ws.readyState === WebSocket.OPEN) {
                    ws.send(dir);
                }
            });
            exitsWrap.appendChild(btn);
        }
    }

    function handleGmcp(pkg, data) {
        switch (pkg) {
            case "Char.Vitals":
                updateVitals(data);
                break;
            case "Room.Info":
                updateRoomInfo(data);
                break;
            case "Char.StatusVars":
                // Field label definitions — no UI update needed for now
                break;
        }
    }

    function tryParseGmcp(text) {
        if (!text.trimStart().startsWith("{")) return null;
        try {
            const obj = JSON.parse(text);
            if (typeof obj.gmcp === "string") {
                return { pkg: obj.gmcp, data: obj.data ?? {} };
            }
        } catch (_) {
            // not JSON
        }
        return null;
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
                const gmcp = tryParseGmcp(event.data);
                if (gmcp) {
                    handleGmcp(gmcp.pkg, gmcp.data);
                } else {
                    term.write(event.data);
                }
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
