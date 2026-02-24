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
    const charName = document.getElementById("char-name");
    const charInfo = document.getElementById("char-info");
    const invList = document.getElementById("inv-list");
    const equipList = document.getElementById("equip-list");
    const playersList = document.getElementById("players-list");

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

    function updateCharName(data) {
        charName.textContent = data.name ?? "—";
        const race = data.race ?? "";
        const cls = data["class"] ?? "";
        const level = data.level ?? "";
        charInfo.textContent = level ? `Level ${level} ${race} ${cls}` : "—";
    }

    function updateInventory(data) {
        const inv = data.inventory ?? [];
        const eq = data.equipment ?? {};

        // Inventory panel
        invList.innerHTML = "";
        if (inv.length === 0) {
            invList.innerHTML = '<span class="empty-hint">Nothing carried</span>';
        } else {
            for (const item of inv) {
                invList.appendChild(createInvElement(item));
            }
        }

        // Equipment panel
        equipList.innerHTML = "";
        const slots = ["head", "body", "hand"];
        let hasEquipped = false;
        for (const slot of slots) {
            const item = eq[slot];
            if (!item) continue;
            hasEquipped = true;
            const el = document.createElement("div");
            el.className = "equip-item";
            const slotSpan = document.createElement("span");
            slotSpan.className = "equip-slot";
            slotSpan.textContent = slot;
            const nameSpan = document.createElement("span");
            nameSpan.textContent = item.name;
            el.appendChild(slotSpan);
            el.appendChild(nameSpan);
            equipList.appendChild(el);
        }
        if (!hasEquipped) {
            equipList.innerHTML = '<span class="empty-hint">Nothing equipped</span>';
        }
    }

    function createInvElement(item) {
        const el = document.createElement("div");
        el.className = "inv-item";
        el.dataset.itemId = item.id;
        el.textContent = item.name;
        return el;
    }

    function addInventoryItem(data) {
        const hint = invList.querySelector(".empty-hint");
        if (hint) hint.remove();
        invList.appendChild(createInvElement(data));
    }

    function removeInventoryItem(data) {
        const el = invList.querySelector(`[data-item-id="${CSS.escape(data.id)}"]`);
        if (el) el.remove();
        if (invList.children.length === 0) {
            invList.innerHTML = '<span class="empty-hint">Nothing carried</span>';
        }
    }

    function updateRoomPlayers(data) {
        playersList.innerHTML = "";
        if (!Array.isArray(data) || data.length === 0) {
            playersList.innerHTML = '<span class="empty-hint">Nobody else here</span>';
            return;
        }
        for (const p of data) {
            const el = document.createElement("div");
            el.className = "player-item";
            el.dataset.playerName = p.name;
            const nameSpan = document.createElement("span");
            nameSpan.textContent = p.name;
            const lvlSpan = document.createElement("span");
            lvlSpan.className = "player-level";
            lvlSpan.textContent = `Lv${p.level}`;
            el.appendChild(nameSpan);
            el.appendChild(lvlSpan);
            playersList.appendChild(el);
        }
    }

    function addRoomPlayer(data) {
        const hint = playersList.querySelector(".empty-hint");
        if (hint) hint.remove();
        const el = document.createElement("div");
        el.className = "player-item";
        el.dataset.playerName = data.name;
        const nameSpan = document.createElement("span");
        nameSpan.textContent = data.name;
        const lvlSpan = document.createElement("span");
        lvlSpan.className = "player-level";
        lvlSpan.textContent = `Lv${data.level}`;
        el.appendChild(nameSpan);
        el.appendChild(lvlSpan);
        playersList.appendChild(el);
    }

    function removeRoomPlayer(data) {
        const el = playersList.querySelector(`[data-player-name="${CSS.escape(data.name)}"]`);
        if (el) el.remove();
        if (playersList.children.length === 0) {
            playersList.innerHTML = '<span class="empty-hint">Nobody else here</span>';
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
                break;
            case "Char.Name":
                updateCharName(data);
                break;
            case "Char.Items.List":
                updateInventory(data);
                break;
            case "Char.Items.Add":
                addInventoryItem(data);
                break;
            case "Char.Items.Remove":
                removeInventoryItem(data);
                break;
            case "Room.Players":
                updateRoomPlayers(data);
                break;
            case "Room.AddPlayer":
                addRoomPlayer(data);
                break;
            case "Room.RemovePlayer":
                removeRoomPlayer(data);
                break;
            case "Char.Skills":
            case "Comm.Channel":
            case "Core.Ping":
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
