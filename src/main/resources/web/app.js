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

    // ── Sidebar elements ──

    const hpBar = document.getElementById("hp-bar");
    const hpText = document.getElementById("hp-text");
    const manaBar = document.getElementById("mana-bar");
    const manaText = document.getElementById("mana-text");
    const xpBar = document.getElementById("xp-bar");
    const xpBarText = document.getElementById("xp-bar-text");
    const levelVal = document.getElementById("level-val");
    const xpVal = document.getElementById("xp-val");
    const roomTitle = document.getElementById("room-title");
    const roomDesc = document.getElementById("room-desc");
    const exitsWrap = document.getElementById("exits-wrap");
    const navExits = document.getElementById("nav-exits");
    const charName = document.getElementById("char-name");
    const charInfo = document.getElementById("char-info");
    const invList = document.getElementById("inv-list");
    const equipList = document.getElementById("equip-list");
    const playersList = document.getElementById("players-list");
    const mobsList = document.getElementById("mobs-list");
    const mapCanvas = document.getElementById("map-canvas");
    const mapCtx = mapCanvas.getContext("2d");

    // ── Command history ──

    const MAX_HISTORY = 100;
    let commandHistory = [];
    let historyIndex = -1;
    let savedInput = "";

    try {
        const stored = localStorage.getItem("ambonmud_history");
        if (stored) commandHistory = JSON.parse(stored);
    } catch (_) { /* ignore */ }

    function pushHistory(cmd) {
        if (!cmd.trim()) return;
        if (commandHistory.length > 0 && commandHistory[commandHistory.length - 1] === cmd) return;
        commandHistory.push(cmd);
        if (commandHistory.length > MAX_HISTORY) commandHistory.shift();
        historyIndex = -1;
        try {
            localStorage.setItem("ambonmud_history", JSON.stringify(commandHistory));
        } catch (_) { /* ignore */ }
    }

    // ── Tab completion ──

    const COMMANDS = [
        "look", "north", "south", "east", "west", "up", "down",
        "say", "tell", "whisper", "shout", "gossip", "ooc", "emote", "pose",
        "who", "score", "inventory", "equipment", "exits",
        "get", "drop", "wear", "remove", "use", "give",
        "kill", "flee", "cast", "spells", "abilities",
        "help", "quit", "clear", "colors",
    ];
    let tabMatches = [];
    let tabIndex = 0;
    let tabPrefix = "";

    function tabComplete() {
        if (tabMatches.length > 0 && tabPrefix === inputBuffer.split(" ")[0]) {
            // cycle through matches
            tabIndex = (tabIndex + 1) % tabMatches.length;
        } else {
            const parts = inputBuffer.split(" ");
            tabPrefix = parts[0].toLowerCase();
            if (!tabPrefix) return;
            tabMatches = COMMANDS.filter(c => c.startsWith(tabPrefix) && c !== tabPrefix);
            if (tabMatches.length === 0) return;
            tabIndex = 0;
        }
        // erase current input from terminal
        const eraseLen = inputBuffer.length;
        term.write("\b \b".repeat(eraseLen));
        // keep args after the first word
        const parts = inputBuffer.split(" ");
        const args = parts.slice(1).join(" ");
        inputBuffer = tabMatches[tabIndex] + (args ? " " + args : "");
        term.write(inputBuffer);
    }

    // ── Mini-map ──

    const visitedRooms = new Map(); // roomId -> { x, y, exits: {dir: roomId} }
    let currentRoomId = null;

    const DIR_OFFSETS = {
        north: { dx: 0, dy: -1 },
        south: { dx: 0, dy: 1 },
        east: { dx: 1, dy: 0 },
        west: { dx: -1, dy: 0 },
        up: { dx: 0.5, dy: -0.5 },
        down: { dx: -0.5, dy: 0.5 },
    };

    function updateMap(roomId, exits) {
        currentRoomId = roomId;

        if (!visitedRooms.has(roomId)) {
            // place the room; try to infer position from a neighbor
            let placed = false;
            for (const [dir, neighborId] of Object.entries(exits)) {
                const neighbor = visitedRooms.get(neighborId);
                if (neighbor) {
                    const off = DIR_OFFSETS[dir];
                    if (off) {
                        visitedRooms.set(roomId, {
                            x: neighbor.x - off.dx,
                            y: neighbor.y - off.dy,
                            exits: exits,
                        });
                        placed = true;
                        break;
                    }
                }
            }
            if (!placed) {
                // first room or disconnected — place at origin
                if (visitedRooms.size === 0) {
                    visitedRooms.set(roomId, { x: 0, y: 0, exits: exits });
                } else {
                    // offset from last position
                    const prev = visitedRooms.get([...visitedRooms.keys()].pop());
                    visitedRooms.set(roomId, { x: (prev?.x ?? 0) + 1, y: prev?.y ?? 0, exits: exits });
                }
            }
        } else {
            // update exits for existing room
            visitedRooms.get(roomId).exits = exits;
        }

        // ensure all exit targets have placeholder entries
        for (const [dir, targetId] of Object.entries(exits)) {
            if (!visitedRooms.has(targetId)) {
                const current = visitedRooms.get(roomId);
                const off = DIR_OFFSETS[dir];
                if (current && off) {
                    visitedRooms.set(targetId, {
                        x: current.x + off.dx,
                        y: current.y + off.dy,
                        exits: {},
                    });
                }
            }
        }

        renderMap();
    }

    function renderMap() {
        const dpr = window.devicePixelRatio || 1;
        const rect = mapCanvas.getBoundingClientRect();
        const w = rect.width;
        const h = rect.height;
        mapCanvas.width = w * dpr;
        mapCanvas.height = h * dpr;
        mapCtx.setTransform(dpr, 0, 0, dpr, 0, 0);

        mapCtx.fillStyle = "#060e18";
        mapCtx.fillRect(0, 0, w, h);

        if (visitedRooms.size === 0) return;

        const current = visitedRooms.get(currentRoomId);
        if (!current) return;

        const cellSize = 24;
        const nodeSize = 8;
        const cx = w / 2;
        const cy = h / 2;

        // draw connections
        mapCtx.strokeStyle = "#2a4a6a";
        mapCtx.lineWidth = 1.5;
        for (const [id, room] of visitedRooms) {
            const rx = cx + (room.x - current.x) * cellSize;
            const ry = cy + (room.y - current.y) * cellSize;
            for (const [dir, targetId] of Object.entries(room.exits)) {
                const target = visitedRooms.get(targetId);
                if (!target) continue;
                const tx = cx + (target.x - current.x) * cellSize;
                const ty = cy + (target.y - current.y) * cellSize;
                mapCtx.beginPath();
                mapCtx.moveTo(rx, ry);
                mapCtx.lineTo(tx, ty);
                mapCtx.stroke();
            }
        }

        // draw room nodes
        for (const [id, room] of visitedRooms) {
            const rx = cx + (room.x - current.x) * cellSize;
            const ry = cy + (room.y - current.y) * cellSize;
            if (rx < -nodeSize || rx > w + nodeSize || ry < -nodeSize || ry > h + nodeSize) continue;

            const isCurrent = id === currentRoomId;
            mapCtx.fillStyle = isCurrent ? "#78d8a7" : "#4a7cc7";
            mapCtx.beginPath();
            mapCtx.arc(rx, ry, isCurrent ? nodeSize / 2 + 2 : nodeSize / 2, 0, Math.PI * 2);
            mapCtx.fill();

            if (isCurrent) {
                mapCtx.strokeStyle = "#78d8a7";
                mapCtx.lineWidth = 1.5;
                mapCtx.beginPath();
                mapCtx.arc(rx, ry, nodeSize / 2 + 5, 0, Math.PI * 2);
                mapCtx.stroke();
            }
        }
    }

    // ── WebSocket ──

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

    function sendCommand(cmd) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(cmd);
        }
    }

    // ── GMCP Handlers ──

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
        // XP progress bar
        const xpInto = data.xpIntoLevel;
        const xpNeeded = data.xpToNextLevel;
        if (xpNeeded != null && xpNeeded > 0) {
            const pct = Math.round((xpInto / xpNeeded) * 100);
            xpBar.style.width = `${pct}%`;
            xpBarText.textContent = `${xpInto.toLocaleString()} / ${xpNeeded.toLocaleString()}`;
        } else if (xpNeeded === null) {
            xpBar.style.width = "100%";
            xpBarText.textContent = "MAX";
        } else {
            xpBar.style.width = "0%";
            xpBarText.textContent = "— / —";
        }
    }

    function updateRoomInfo(data) {
        roomTitle.textContent = data.title ?? "—";
        roomDesc.textContent = data.description ?? "";
        const exits = data.exits ?? {};

        // sidebar exits
        exitsWrap.innerHTML = "";
        for (const [dir, roomId] of Object.entries(exits)) {
            const btn = document.createElement("button");
            btn.className = "exit-btn";
            btn.textContent = dir;
            btn.title = roomId;
            btn.addEventListener("click", () => sendCommand(dir));
            exitsWrap.appendChild(btn);
        }

        // nav bar exits (below terminal)
        navExits.innerHTML = "";
        const dirOrder = ["north", "south", "east", "west", "up", "down"];
        const sortedDirs = Object.keys(exits).sort(
            (a, b) => dirOrder.indexOf(a) - dirOrder.indexOf(b),
        );
        for (const dir of sortedDirs) {
            const btn = document.createElement("button");
            btn.className = "nav-btn";
            btn.textContent = dir;
            btn.title = exits[dir];
            btn.addEventListener("click", () => {
                sendCommand(dir);
                term.focus();
            });
            navExits.appendChild(btn);
        }

        // mini-map
        if (data.id) {
            updateMap(data.id, exits);
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

    function createMobElement(mob) {
        const el = document.createElement("div");
        el.className = "mob-item";
        el.dataset.mobId = mob.id;
        const nameSpan = document.createElement("span");
        nameSpan.textContent = mob.name;
        el.appendChild(nameSpan);
        const hpWrap = document.createElement("div");
        hpWrap.className = "mob-hp-wrap";
        const hpFill = document.createElement("div");
        hpFill.className = "mob-hp-fill";
        const pct = Math.round((mob.hp / Math.max(mob.maxHp, 1)) * 100);
        hpFill.style.width = `${pct}%`;
        hpWrap.appendChild(hpFill);
        el.appendChild(hpWrap);
        return el;
    }

    function updateRoomMobs(data) {
        mobsList.innerHTML = "";
        if (!Array.isArray(data) || data.length === 0) {
            mobsList.innerHTML = '<span class="empty-hint">No mobs here</span>';
            return;
        }
        for (const m of data) {
            mobsList.appendChild(createMobElement(m));
        }
    }

    function addRoomMob(data) {
        const hint = mobsList.querySelector(".empty-hint");
        if (hint) hint.remove();
        mobsList.appendChild(createMobElement(data));
    }

    function updateRoomMob(data) {
        const el = mobsList.querySelector(`[data-mob-id="${CSS.escape(data.id)}"]`);
        if (!el) return;
        const hpFill = el.querySelector(".mob-hp-fill");
        if (hpFill) {
            const pct = Math.round((data.hp / Math.max(data.maxHp, 1)) * 100);
            hpFill.style.width = `${pct}%`;
        }
    }

    function removeRoomMob(data) {
        const el = mobsList.querySelector(`[data-mob-id="${CSS.escape(data.id)}"]`);
        if (el) el.remove();
        if (mobsList.children.length === 0) {
            mobsList.innerHTML = '<span class="empty-hint">No mobs here</span>';
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
            case "Room.Mobs":
                updateRoomMobs(data);
                break;
            case "Room.AddMob":
                addRoomMob(data);
                break;
            case "Room.UpdateMob":
                updateRoomMob(data);
                break;
            case "Room.RemoveMob":
                removeRoomMob(data);
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

    // ── Connection ──

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

    // ── Terminal input ──

    function clearInputLine() {
        if (inputBuffer.length > 0) {
            term.write("\b \b".repeat(inputBuffer.length));
        }
    }

    function replaceInput(newText) {
        clearInputLine();
        inputBuffer = newText;
        term.write(inputBuffer);
    }

    term.onData((data) => {
        if (!connected || !ws || ws.readyState !== WebSocket.OPEN) {
            return;
        }

        // detect escape sequences for arrow keys
        if (data === "\x1b[A") {
            // up arrow — older history
            if (commandHistory.length === 0) return;
            if (historyIndex === -1) {
                savedInput = inputBuffer;
                historyIndex = commandHistory.length - 1;
            } else if (historyIndex > 0) {
                historyIndex--;
            }
            replaceInput(commandHistory[historyIndex]);
            return;
        }
        if (data === "\x1b[B") {
            // down arrow — newer history
            if (historyIndex === -1) return;
            historyIndex++;
            if (historyIndex >= commandHistory.length) {
                historyIndex = -1;
                replaceInput(savedInput);
            } else {
                replaceInput(commandHistory[historyIndex]);
            }
            return;
        }

        for (const ch of data) {
            if (ch === "\r") {
                pushHistory(inputBuffer);
                sendCommand(inputBuffer);
                term.write("\r\n");
                inputBuffer = "";
                historyIndex = -1;
                tabMatches = [];
                continue;
            }

            if (ch === "\u007f") {
                if (inputBuffer.length > 0) {
                    inputBuffer = inputBuffer.slice(0, -1);
                    term.write("\b \b");
                }
                tabMatches = [];
                continue;
            }

            if (ch === "\t") {
                tabComplete();
                continue;
            }

            if (isPrintable(ch)) {
                inputBuffer += ch;
                term.write(ch);
                tabMatches = [];
            }
        }
    });

    // ── UI events ──

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

    window.addEventListener("resize", () => {
        if (visitedRooms.size > 0) renderMap();
    });

    connect();
})();
