(() => {
    // ═══════════════════════════════════════════════════════════════════════
    // CONFIG
    // ═══════════════════════════════════════════════════════════════════════

    const WS_URL = (() => {
        const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
        return `${scheme}://${location.host}/ws`;
    })();

    const HISTORY_KEY = 'ambonmud_history_v2';
    const MAX_HISTORY = 100;

    /** xterm.js theme — dark terminal that harmonises with the Surreal Gentle Magic palette */
    const XTERM_THEME = {
        background:        '#1C1C2A',
        foreground:        '#E8E8F0',
        cursor:            '#E8D8A8',
        selectionBackground: 'rgba(216, 197, 232, 0.3)',
        black:             '#1C1C2A',
        brightBlack:       '#6B6B7B',
        red:               '#C5A8A8',
        brightRed:         '#D8B8B8',
        green:             '#C5D8A8',
        brightGreen:       '#D5E8B8',
        yellow:            '#E8D8A8',
        brightYellow:      '#F0E8C0',
        blue:              '#B8D8E8',
        brightBlue:        '#C8E4F0',
        magenta:           '#D8C5E8',
        brightMagenta:     '#E8D8F8',
        cyan:              '#B8D0D8',
        brightCyan:        '#C8E0E8',
        white:             '#E8E8F0',
        brightWhite:       '#FFFFFF',
    };

    const DIR_ORDER = ['north', 'south', 'east', 'west', 'up', 'down'];

    const DIR_OFFSETS = {
        north: { dx:  0,    dy: -1   },
        south: { dx:  0,    dy:  1   },
        east:  { dx:  1,    dy:  0   },
        west:  { dx: -1,    dy:  0   },
        up:    { dx:  0.5,  dy: -0.5 },
        down:  { dx: -0.5,  dy:  0.5 },
    };

    const TAB_COMMANDS = [
        'look', 'north', 'south', 'east', 'west', 'up', 'down',
        'say', 'tell', 'whisper', 'shout', 'gossip', 'ooc', 'emote', 'pose',
        'who', 'score', 'inventory', 'equipment', 'exits',
        'get', 'drop', 'wear', 'remove', 'use', 'give',
        'kill', 'flee', 'cast', 'spells', 'abilities', 'effects',
        'help', 'quit', 'clear', 'colors',
    ];

    // ═══════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════

    /** Login state machine:
     *  idle → connecting → awaiting_prompt → sent_name → sent_password → authenticated
     *  Any error/disconnect resets back to idle.
     */
    let loginState    = 'idle';
    let loginName     = '';
    let loginPassword = '';
    let loginIsNewUser = false;

    let ws = null;
    let inputBuffer = '';
    let commandHistory = [];
    let historyIndex = -1;
    let savedInput = '';

    let tabMatches = [];
    let tabIndex = 0;
    let tabOriginalPrefix = '';
    let tabArgs = '';

    const visitedRooms = new Map(); // roomId → { x, y, exits: { dir: roomId } }
    let currentRoomId = null;

    // ═══════════════════════════════════════════════════════════════════════
    // DOM REFS
    // ═══════════════════════════════════════════════════════════════════════

    const loginOverlay    = document.getElementById('login-overlay');
    const loginForm       = document.getElementById('login-form');
    const loginNameInput  = document.getElementById('login-name');
    const loginPassInput  = document.getElementById('login-password');
    const loginError      = document.getElementById('login-error');
    const loginBtn         = document.getElementById('login-btn');
    const loginNewUserCheck = document.getElementById('login-new-user');

    const statusPill      = document.getElementById('status');
    const reconnectBtn    = document.getElementById('reconnect');

    const hpBar           = document.getElementById('hp-bar');
    const hpText          = document.getElementById('hp-text');
    const manaBar         = document.getElementById('mana-bar');
    const manaText        = document.getElementById('mana-text');
    const xpBar           = document.getElementById('xp-bar');
    const xpBarText       = document.getElementById('xp-bar-text');
    const levelVal        = document.getElementById('level-val');
    const xpVal           = document.getElementById('xp-val');
    const goldVal         = document.getElementById('gold-val');

    const charName        = document.getElementById('char-name');
    const charInfo        = document.getElementById('char-info');

    const roomTitle       = document.getElementById('room-title');
    const roomDesc        = document.getElementById('room-desc');
    const exitsWrap       = document.getElementById('exits-wrap');
    const navExits        = document.getElementById('nav-exits');

    const playersList     = document.getElementById('players-list');
    const mobsList        = document.getElementById('mobs-list');
    const effectsList     = document.getElementById('effects-list');
    const invList         = document.getElementById('inv-list');
    const equipList       = document.getElementById('equip-list');

    const mapCanvas       = document.getElementById('map-canvas');
    const mapCtx          = mapCanvas.getContext('2d');

    // ═══════════════════════════════════════════════════════════════════════
    // TERMINAL
    // ═══════════════════════════════════════════════════════════════════════

    const term = new Terminal({
        cursorBlink: true,
        fontFamily: '"Cascadia Mono", "Consolas", "Courier New", monospace',
        fontSize: 15,
        rows: 30,
        convertEol: false,
        theme: XTERM_THEME,
    });

    term.open(document.getElementById('terminal'));

    // ═══════════════════════════════════════════════════════════════════════
    // COMMAND HISTORY
    // ═══════════════════════════════════════════════════════════════════════

    try {
        const stored = localStorage.getItem(HISTORY_KEY);
        if (stored) commandHistory = JSON.parse(stored);
    } catch (_) { /* ignore */ }

    function pushHistory(cmd) {
        if (!cmd.trim()) return;
        if (commandHistory.length > 0 && commandHistory[commandHistory.length - 1] === cmd) return;
        commandHistory.push(cmd);
        if (commandHistory.length > MAX_HISTORY) commandHistory.shift();
        historyIndex = -1;
        try { localStorage.setItem(HISTORY_KEY, JSON.stringify(commandHistory)); } catch (_) { /* ignore */ }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TAB COMPLETION
    // ═══════════════════════════════════════════════════════════════════════

    function tabComplete() {
        const parts = inputBuffer.split(' ');
        const first = parts[0].toLowerCase();

        if (tabMatches.length > 0 && tabOriginalPrefix && tabMatches.includes(first)) {
            tabIndex = (tabIndex + 1) % tabMatches.length;
        } else {
            tabOriginalPrefix = first;
            tabArgs = parts.slice(1).join(' ');
            if (!tabOriginalPrefix) return;
            tabMatches = TAB_COMMANDS.filter(c => c.startsWith(tabOriginalPrefix) && c !== tabOriginalPrefix);
            if (tabMatches.length === 0) return;
            tabIndex = 0;
        }
        clearInputLine();
        inputBuffer = tabMatches[tabIndex] + (tabArgs ? ' ' + tabArgs : '');
        term.write(inputBuffer);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MINI-MAP RENDERER
    // ═══════════════════════════════════════════════════════════════════════

    function updateMap(roomId, exits) {
        currentRoomId = roomId;

        if (!visitedRooms.has(roomId)) {
            let placed = false;
            for (const [dir, neighborId] of Object.entries(exits)) {
                const neighbor = visitedRooms.get(neighborId);
                if (neighbor) {
                    const off = DIR_OFFSETS[dir];
                    if (off) {
                        visitedRooms.set(roomId, { x: neighbor.x - off.dx, y: neighbor.y - off.dy, exits });
                        placed = true;
                        break;
                    }
                }
            }
            if (!placed) {
                if (visitedRooms.size === 0) {
                    visitedRooms.set(roomId, { x: 0, y: 0, exits });
                } else {
                    const prev = visitedRooms.get([...visitedRooms.keys()].pop());
                    visitedRooms.set(roomId, { x: (prev?.x ?? 0) + 1, y: prev?.y ?? 0, exits });
                }
            }
        } else {
            visitedRooms.get(roomId).exits = exits;
        }

        // pre-place exit destinations as stub rooms
        for (const [dir, targetId] of Object.entries(exits)) {
            if (!visitedRooms.has(targetId)) {
                const current = visitedRooms.get(roomId);
                const off = DIR_OFFSETS[dir];
                if (current && off) {
                    visitedRooms.set(targetId, { x: current.x + off.dx, y: current.y + off.dy, exits: {} });
                }
            }
        }

        renderMap();
    }

    function renderMap() {
        const dpr  = window.devicePixelRatio || 1;
        const rect = mapCanvas.getBoundingClientRect();
        const w    = rect.width  || 210;
        const h    = rect.height || 150;

        mapCanvas.width  = w * dpr;
        mapCanvas.height = h * dpr;
        mapCtx.setTransform(dpr, 0, 0, dpr, 0, 0);

        // Background — matches terminal
        mapCtx.fillStyle = '#1C1C2A';
        mapCtx.fillRect(0, 0, w, h);

        if (visitedRooms.size === 0) return;
        const current = visitedRooms.get(currentRoomId);
        if (!current) return;

        const cellSize = 24;
        const nodeSize = 7;
        const cx = w / 2;
        const cy = h / 2;

        // Draw connections — pale-blue tinted
        mapCtx.strokeStyle = 'rgba(184, 216, 232, 0.35)';
        mapCtx.lineWidth = 1.5;
        for (const [, room] of visitedRooms) {
            const rx = cx + (room.x - current.x) * cellSize;
            const ry = cy + (room.y - current.y) * cellSize;
            for (const [, targetId] of Object.entries(room.exits)) {
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

        // Draw room nodes
        for (const [id, room] of visitedRooms) {
            const rx = cx + (room.x - current.x) * cellSize;
            const ry = cy + (room.y - current.y) * cellSize;
            if (rx < -nodeSize || rx > w + nodeSize || ry < -nodeSize || ry > h + nodeSize) continue;

            const isCurrent = id === currentRoomId;

            if (isCurrent) {
                // Soft glow ring — lavender
                mapCtx.strokeStyle = 'rgba(216, 197, 232, 0.45)';
                mapCtx.lineWidth = 1.5;
                mapCtx.beginPath();
                mapCtx.arc(rx, ry, nodeSize + 5, 0, Math.PI * 2);
                mapCtx.stroke();
                // Current room — soft-gold
                mapCtx.fillStyle = '#E8D8A8';
            } else {
                // Visited room — pale-blue
                mapCtx.fillStyle = '#B8D8E8';
            }

            mapCtx.beginPath();
            mapCtx.arc(rx, ry, isCurrent ? nodeSize / 2 + 2 : nodeSize / 2, 0, Math.PI * 2);
            mapCtx.fill();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DOM UPDATERS
    // ═══════════════════════════════════════════════════════════════════════

    function updateVitals(data) {
        const hp      = data.hp      ?? 0;
        const maxHp   = data.maxHp   ?? 1;
        const mana    = data.mana    ?? 0;
        const maxMana = data.maxMana ?? 1;

        hpBar.style.width  = `${Math.round((hp   / Math.max(maxHp,   1)) * 100)}%`;
        manaBar.style.width = `${Math.round((mana / Math.max(maxMana, 1)) * 100)}%`;
        hpText.textContent   = `${hp} / ${maxHp}`;
        manaText.textContent = `${mana} / ${maxMana}`;
        levelVal.textContent = data.level ?? '—';

        if (data.xp !== undefined) xpVal.textContent = data.xp.toLocaleString();
        if (goldVal) goldVal.textContent = (data.gold ?? 0).toLocaleString();

        const xpInto   = data.xpIntoLevel   ?? 0;
        const xpNeeded = data.xpToNextLevel;
        if (xpNeeded != null && xpNeeded > 0) {
            xpBar.style.width  = `${Math.min(100, Math.round((xpInto / xpNeeded) * 100))}%`;
            xpBarText.textContent = `${xpInto.toLocaleString()} / ${xpNeeded.toLocaleString()}`;
        } else if (xpNeeded === null) {
            xpBar.style.width  = '100%';
            xpBarText.textContent = 'MAX';
        } else {
            xpBar.style.width  = '0%';
            xpBarText.textContent = '— / —';
        }
    }

    function updateRoomInfo(data) {
        roomTitle.textContent = data.title       ?? '—';
        roomDesc.textContent  = data.description ?? '';

        const exits = data.exits ?? {};

        // Sidebar exit buttons
        exitsWrap.innerHTML = '';
        for (const [dir, roomId] of Object.entries(exits)) {
            const btn = document.createElement('button');
            btn.className = 'exit-btn';
            btn.textContent = dir;
            btn.title = roomId;
            btn.addEventListener('click', () => sendCommand(dir));
            exitsWrap.appendChild(btn);
        }

        // Nav bar exit buttons (below terminal) — sorted by DIR_ORDER
        navExits.innerHTML = '';
        const sortedDirs = Object.keys(exits).sort(
            (a, b) => DIR_ORDER.indexOf(a) - DIR_ORDER.indexOf(b),
        );
        for (const dir of sortedDirs) {
            const btn = document.createElement('button');
            btn.className = 'nav-btn';
            btn.textContent = dir;
            btn.title = exits[dir];
            btn.addEventListener('click', () => { sendCommand(dir); term.focus(); });
            navExits.appendChild(btn);
        }

        if (data.id) updateMap(data.id, exits);
    }

    function updateCharName(data) {
        charName.textContent = data.name ?? '—';
        const race  = data.race         ?? '';
        const cls   = data['class']     ?? '';
        const level = data.level        ?? '';
        charInfo.textContent = level ? `Level ${level} ${race} ${cls}`.trim() : '—';
    }

    function updateInventory(data) {
        const inv = data.inventory ?? [];
        const eq  = data.equipment ?? {};

        invList.innerHTML = '';
        if (inv.length === 0) {
            invList.innerHTML = '<span class="empty-hint">Nothing carried</span>';
        } else {
            for (const item of inv) invList.appendChild(makeInvElement(item));
        }

        equipList.innerHTML = '';
        const slots = ['head', 'body', 'hand'];
        let hasEquipped = false;
        for (const slot of slots) {
            const item = eq[slot];
            if (!item) continue;
            hasEquipped = true;
            const el      = document.createElement('div');
            el.className  = 'equip-item';
            const slotEl  = document.createElement('span');
            slotEl.className = 'equip-slot';
            slotEl.textContent = slot;
            const nameEl  = document.createElement('span');
            nameEl.textContent = item.name;
            el.appendChild(slotEl);
            el.appendChild(nameEl);
            equipList.appendChild(el);
        }
        if (!hasEquipped) equipList.innerHTML = '<span class="empty-hint">Nothing equipped</span>';
    }

    function makeInvElement(item) {
        const el = document.createElement('div');
        el.className = 'inv-item';
        el.dataset.itemId = item.id;
        el.textContent = item.name;
        return el;
    }

    function addInventoryItem(data) {
        const hint = invList.querySelector('.empty-hint');
        if (hint) hint.remove();
        invList.appendChild(makeInvElement(data));
    }

    function removeInventoryItem(data) {
        invList.querySelector(`[data-item-id="${CSS.escape(data.id)}"]`)?.remove();
        if (invList.children.length === 0) {
            invList.innerHTML = '<span class="empty-hint">Nothing carried</span>';
        }
    }

    function updateRoomPlayers(data) {
        playersList.innerHTML = '';
        if (!Array.isArray(data) || data.length === 0) {
            playersList.innerHTML = '<span class="empty-hint">Nobody else here</span>';
            return;
        }
        for (const p of data) playersList.appendChild(makePlayerElement(p));
    }

    function makePlayerElement(p) {
        const el      = document.createElement('div');
        el.className  = 'player-item';
        el.dataset.playerName = p.name;
        const nameEl  = document.createElement('span');
        nameEl.textContent = p.name;
        const lvlEl   = document.createElement('span');
        lvlEl.className = 'player-level';
        lvlEl.textContent = `Lv${p.level}`;
        el.appendChild(nameEl);
        el.appendChild(lvlEl);
        return el;
    }

    function addRoomPlayer(data) {
        playersList.querySelector('.empty-hint')?.remove();
        playersList.appendChild(makePlayerElement(data));
    }

    function removeRoomPlayer(data) {
        playersList.querySelector(`[data-player-name="${CSS.escape(data.name)}"]`)?.remove();
        if (playersList.children.length === 0) {
            playersList.innerHTML = '<span class="empty-hint">Nobody else here</span>';
        }
    }

    function makeMobElement(mob) {
        const el     = document.createElement('div');
        el.className = 'mob-item';
        el.dataset.mobId = mob.id;
        const nameEl = document.createElement('span');
        nameEl.textContent = mob.name;
        el.appendChild(nameEl);
        const track  = document.createElement('div');
        track.className = 'mob-hp-track';
        const fill   = document.createElement('div');
        fill.className = 'mob-hp-fill';
        fill.style.width = `${Math.round((mob.hp / Math.max(mob.maxHp, 1)) * 100)}%`;
        track.appendChild(fill);
        el.appendChild(track);
        return el;
    }

    function updateRoomMobs(data) {
        mobsList.innerHTML = '';
        if (!Array.isArray(data) || data.length === 0) {
            mobsList.innerHTML = '<span class="empty-hint">No mobs here</span>';
            return;
        }
        for (const m of data) mobsList.appendChild(makeMobElement(m));
    }

    function addRoomMob(data) {
        mobsList.querySelector('.empty-hint')?.remove();
        mobsList.appendChild(makeMobElement(data));
    }

    function updateRoomMob(data) {
        const el = mobsList.querySelector(`[data-mob-id="${CSS.escape(data.id)}"]`);
        if (!el) return;
        const fill = el.querySelector('.mob-hp-fill');
        if (fill) fill.style.width = `${Math.round((data.hp / Math.max(data.maxHp, 1)) * 100)}%`;
    }

    function removeRoomMob(data) {
        mobsList.querySelector(`[data-mob-id="${CSS.escape(data.id)}"]`)?.remove();
        if (mobsList.children.length === 0) {
            mobsList.innerHTML = '<span class="empty-hint">No mobs here</span>';
        }
    }

    function updateStatusEffects(data) {
        effectsList.innerHTML = '';
        if (!Array.isArray(data) || data.length === 0) {
            effectsList.innerHTML = '<span class="empty-hint">None</span>';
            return;
        }
        for (const e of data) {
            const el      = document.createElement('div');
            el.className  = 'effect-item';
            const nameEl  = document.createElement('span');
            nameEl.className = 'effect-name';
            nameEl.textContent = e.name + (e.stacks > 1 ? ` ×${e.stacks}` : '');
            const typeEl  = document.createElement('span');
            typeEl.className  = `effect-type effect-type-${e.type.toLowerCase()}`;
            typeEl.textContent = e.type;
            const timeEl  = document.createElement('span');
            timeEl.className  = 'effect-time';
            timeEl.textContent = `${Math.max(1, Math.ceil(e.remainingMs / 1000))}s`;
            el.appendChild(nameEl);
            el.appendChild(typeEl);
            el.appendChild(timeEl);
            effectsList.appendChild(el);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GMCP ROUTER
    // ═══════════════════════════════════════════════════════════════════════

    function handleGmcp(pkg, data) {
        switch (pkg) {
            case 'Char.Vitals':        updateVitals(data);        break;
            case 'Room.Info':          updateRoomInfo(data);       break;
            case 'Char.Name':
                updateCharName(data);
                if (loginState !== 'authenticated') onAuthenticated();
                break;
            case 'Char.Items.List':    updateInventory(data);      break;
            case 'Char.Items.Add':     addInventoryItem(data);     break;
            case 'Char.Items.Remove':  removeInventoryItem(data);  break;
            case 'Room.Players':       updateRoomPlayers(data);    break;
            case 'Room.AddPlayer':     addRoomPlayer(data);        break;
            case 'Room.RemovePlayer':  removeRoomPlayer(data);     break;
            case 'Room.Mobs':          updateRoomMobs(data);       break;
            case 'Room.AddMob':        addRoomMob(data);           break;
            case 'Room.UpdateMob':     updateRoomMob(data);        break;
            case 'Room.RemoveMob':     removeRoomMob(data);        break;
            case 'Char.StatusEffects': updateStatusEffects(data);  break;
            case 'Char.StatusVars':
            case 'Char.Skills':
            case 'Comm.Channel':
            case 'Core.Ping':
                break;
        }
    }

    function tryParseGmcp(text) {
        if (!text.trimStart().startsWith('{')) return null;
        try {
            const obj = JSON.parse(text);
            if (typeof obj.gmcp === 'string') return { pkg: obj.gmcp, data: obj.data ?? {} };
        } catch (_) { /* not JSON */ }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LOGIN FLOW
    // ═══════════════════════════════════════════════════════════════════════

    function setStatus(state) {
        statusPill.className = `status-pill ${state}`;
        statusPill.textContent = state.charAt(0).toUpperCase() + state.slice(1);
    }

    function showLoginError(msg) {
        loginError.textContent = msg;
        loginError.hidden = false;
        loginBtn.disabled = false;
        loginBtn.textContent = 'Enter the World';
    }

    function clearLoginError() {
        loginError.hidden = true;
        loginError.textContent = '';
    }

    function hideLoginOverlay() {
        if (loginOverlay.hidden) return;
        loginOverlay.classList.add('is-hiding');
        let dismissed = false;
        function dismiss() {
            if (dismissed) return;
            dismissed = true;
            loginOverlay.hidden = true;
            term.focus();
        }
        // Belt-and-suspenders: use both animationend and a timeout fallback
        loginOverlay.addEventListener('animationend', dismiss, { once: true });
        setTimeout(dismiss, 450);
    }

    function resetLoginForm() {
        loginNameInput.value  = '';
        loginPassInput.value  = '';
        loginNewUserCheck.checked = false;
        clearLoginError();
        loginBtn.disabled = false;
        loginBtn.textContent = 'Enter the World';
        loginOverlay.classList.remove('is-hiding');
        loginOverlay.hidden = false;
        loginNameInput.focus();
    }

    function onAuthenticated() {
        loginState = 'authenticated';
        setStatus('connected');
        // Overlay already dismissed in onWsOpenedForLogin; just update the status pill.
    }

    /** Called when the WS first opens during a login attempt. */
    function onWsOpenedForLogin() {
        loginState = 'awaiting_prompt';
        setStatus('connecting');
        // Hide the overlay as soon as the socket is live.
        // The auto-login state machine sends credentials as server prompts arrive.
        // If it stalls the user can still type freely in the terminal.
        hideLoginOverlay();
    }

    /** Inspect server text lines during the login handshake. */
    function handleLoginText(text) {
        if (loginState === 'awaiting_prompt') {
            // Server has sent something — send the character name
            sendRaw(loginName);
            loginState = 'sent_name';
        } else if (loginState === 'sent_name') {
            if (/password/i.test(text)) {
                // "Password:" (existing user) or "Create a password:" (new user)
                sendRaw(loginPassword);
                loginState = 'sent_password';
            } else if (/\(yes\/no\)/i.test(text)) {
                // "No user named '...' was found. Create a new user? (yes/no)"
                if (loginIsNewUser) {
                    sendRaw('yes');
                    // stay in 'sent_name' — password prompt follows
                } else {
                    sendRaw('no');
                    loginState = 'rejected_new_user';
                    // server will close the connection; ws 'close' handler restores the overlay
                }
            }
        }
        // In 'sent_password' we wait for Char.Name GMCP — nothing more to auto-send.
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WEBSOCKET SETUP
    // ═══════════════════════════════════════════════════════════════════════

    function sendRaw(text) {
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(text);
    }

    function sendCommand(cmd) {
        sendRaw(cmd);
    }

    function writeSystem(msg) {
        term.write(`\r\n\x1b[2m${msg}\x1b[0m\r\n`);
    }

    function resetHud() {
        charName.textContent  = '—';
        charInfo.textContent  = '—';
        hpBar.style.width     = '0%';
        hpText.textContent    = '— / —';
        manaBar.style.width   = '0%';
        manaText.textContent  = '— / —';
        xpBar.style.width     = '0%';
        xpBarText.textContent = '— / —';
        levelVal.textContent  = '—';
        xpVal.textContent     = '—';
        if (goldVal) goldVal.textContent = '0';
        roomTitle.textContent = '—';
        roomDesc.textContent  = '';
        exitsWrap.innerHTML   = '';
        navExits.innerHTML    = '';
        playersList.innerHTML = '<span class="empty-hint">—</span>';
        mobsList.innerHTML    = '<span class="empty-hint">—</span>';
        invList.innerHTML     = '<span class="empty-hint">—</span>';
        equipList.innerHTML   = '<span class="empty-hint">—</span>';
        effectsList.innerHTML = '<span class="empty-hint">None</span>';
        visitedRooms.clear();
        currentRoomId = null;
        renderMap();
    }

    function connect(name, password, isNewUser) {
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

        loginName      = name;
        loginPassword  = password;
        loginIsNewUser = isNewUser;
        loginState     = 'connecting';
        setStatus('connecting');

        ws = new WebSocket(WS_URL);

        ws.addEventListener('open', () => {
            onWsOpenedForLogin();
        });

        ws.addEventListener('message', (event) => {
            if (typeof event.data !== 'string') return;
            const gmcp = tryParseGmcp(event.data);
            if (gmcp) {
                handleGmcp(gmcp.pkg, gmcp.data);
            } else {
                // Feed text to login state machine while logging in
                if (loginState !== 'authenticated') {
                    handleLoginText(event.data);
                }
                term.write(event.data);
            }
        });

        ws.addEventListener('close', () => {
            const wasAuthenticated  = loginState === 'authenticated';
            const rejectedNewUser   = loginState === 'rejected_new_user';
            loginState = 'idle';
            setStatus('disconnected');
            inputBuffer = '';
            tabMatches  = [];
            resetHud();
            writeSystem('Connection closed.');
            if (rejectedNewUser) {
                // User not found and they didn't want to register — restore overlay with hint
                resetLoginForm();
                loginNameInput.value = loginName;
                showLoginError('No user found with that name. Tick "New character" to register.');
            } else if (!wasAuthenticated) {
                // Connection dropped during login — restore overlay
                resetLoginForm();
                showLoginError('Connection closed. Please try again.');
            } else {
                // Authenticated session disconnected — show overlay for reconnect
                resetLoginForm();
                loginNameInput.value = loginName;
            }
        });

        ws.addEventListener('error', () => {
            loginState = 'idle';
            setStatus('disconnected');
            inputBuffer = '';
            tabMatches  = [];
            writeSystem('Connection error.');
            showLoginError('Could not connect. Check that the server is running.');
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INPUT HANDLING
    // ═══════════════════════════════════════════════════════════════════════

    function isPrintable(ch) {
        const code = ch.charCodeAt(0);
        return code >= 0x20 && code !== 0x7f;
    }

    function clearInputLine() {
        if (inputBuffer.length > 0) term.write('\b \b'.repeat(inputBuffer.length));
    }

    function replaceInput(text) {
        clearInputLine();
        inputBuffer = text;
        term.write(inputBuffer);
    }

    term.onData((data) => {
        if (!ws || ws.readyState !== WebSocket.OPEN) return;

        // History navigation only available once authenticated
        if (loginState === 'authenticated') {
            if (data === '\x1b[A') {
                if (commandHistory.length === 0) return;
                if (historyIndex === -1) { savedInput = inputBuffer; historyIndex = commandHistory.length - 1; }
                else if (historyIndex > 0) historyIndex--;
                replaceInput(commandHistory[historyIndex]);
                return;
            }
            if (data === '\x1b[B') {
                if (historyIndex === -1) return;
                historyIndex++;
                replaceInput(historyIndex >= commandHistory.length ? (historyIndex = -1, savedInput) : commandHistory[historyIndex]);
                return;
            }
        }

        for (const ch of data) {
            if (ch === '\r') {
                if (loginState === 'authenticated') pushHistory(inputBuffer);
                sendCommand(inputBuffer);
                term.write('\r\n');
                inputBuffer  = '';
                historyIndex = -1;
                tabMatches   = [];
                continue;
            }
            if (ch === '\u007f') {
                if (inputBuffer.length > 0) { inputBuffer = inputBuffer.slice(0, -1); term.write('\b \b'); }
                tabMatches = [];
                continue;
            }
            if (ch === '\t') {
                if (loginState === 'authenticated') tabComplete();
                continue;
            }
            if (isPrintable(ch)) { inputBuffer += ch; term.write(ch); tabMatches = []; }
        }
    });

    // ═══════════════════════════════════════════════════════════════════════
    // UI EVENTS
    // ═══════════════════════════════════════════════════════════════════════

    loginForm.addEventListener('submit', (e) => {
        e.preventDefault();
        clearLoginError();

        const name     = loginNameInput.value.trim();
        const password = loginPassInput.value;

        if (!name) {
            loginNameInput.focus();
            showLoginError('Please enter a character name.');
            return;
        }
        if (!password) {
            loginPassInput.focus();
            showLoginError('Please enter a password.');
            return;
        }

        loginBtn.disabled = true;
        loginBtn.textContent = 'Connecting…';
        connect(name, password, loginNewUserCheck.checked);
    });

    reconnectBtn.addEventListener('click', () => {
        if (ws) ws.close();
        resetLoginForm();
        if (loginName) loginNameInput.value = loginName;
    });

    window.addEventListener('beforeunload', () => { if (ws) ws.close(); });
    window.addEventListener('resize', () => { if (visitedRooms.size > 0) renderMap(); });

    // ═══════════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════════

    loginNameInput.focus();
})();
