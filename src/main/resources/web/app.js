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

    // ── Canvas Rendering System (Phase 4) ──

    let canvasRenderer = null;
    let canvasCamera = null;
    let canvasInteraction = null;
    let gmcpIntegration = null;
    let animationFrameId = null;

    // ── Performance Monitoring (Phase 5c) ──

    let performanceProfiler = null;
    let qualitySettings = null;
    let performanceDashboard = null;

    // Initialize canvas system when DOM is ready
    setTimeout(() => {
        const worldCanvas = document.getElementById("world-canvas");
        const mapCanvas = document.getElementById("map-canvas");

        if (!worldCanvas) return; // Canvas not available yet

        try {
            // Initialize design tokens (loads CSS variables)
            const designTokens = new DesignTokens();

            // Create renderer, camera, interaction
            canvasRenderer = new CanvasWorldRenderer(worldCanvas, designTokens);
            canvasCamera = new Camera(worldCanvas);
            canvasInteraction = new CanvasInteraction(worldCanvas, canvasCamera, {
                onExitClick: (exit) => sendCommand(exit.direction),
                onMobClick: (mob) => sendCommand(`kill ${mob.name}`),
                onTileClick: (pos) => console.log('Clicked tile:', pos),
            });

            // Create GMCP integration
            gmcpIntegration = new GMCPCanvasIntegration(
                canvasRenderer,
                canvasCamera,
                canvasInteraction
            );

            // Initialize multi-zone rendering systems (Phase 5b)
            gmcpIntegration.initializeMultiZoneSystems();
            if (gmcpIntegration.multiZoneRenderer) {
                canvasRenderer.updateGameState({
                    multiZoneRenderer: gmcpIntegration.multiZoneRenderer,
                });
                console.log('Multi-zone rendering system initialized');
            }

            // Initialize performance monitoring (Phase 5c)
            performanceProfiler = new PerformanceProfiler();
            qualitySettings = new QualitySettings();
            performanceDashboard = new PerformanceDashboard(performanceProfiler, qualitySettings);

            // Auto-detect quality level based on device
            qualitySettings.autoDetectQualityLevel();
            qualitySettings.checkMotionPreferences();

            // Try to load saved settings
            if (!qualitySettings.loadFromLocalStorage()) {
                console.log(`Auto-detected quality level: ${qualitySettings.getQualityLevelName()}`);
            }

            canvasRenderer.updateGameState({
                performanceDashboard: performanceDashboard,
            });

            // Toggle dashboard with keyboard shortcut (Alt+D)
            document.addEventListener('keydown', (e) => {
                if (e.altKey && e.key === 'd') {
                    performanceDashboard.toggle();
                    console.log(`Dashboard ${performanceDashboard.isVisible ? 'shown' : 'hidden'}`);
                }
            });

            // Start animation loop
            canvasRenderer.scheduleRender();
            animationFrameId = requestAnimationFrame(canvasAnimationLoop);

            console.log('Canvas rendering system initialized (press Alt+D to show performance dashboard)');
        } catch (e) {
            console.error('Failed to initialize canvas:', e);
        }
    }, 100);

    // Canvas animation loop with performance monitoring
    let frameCount = 0;
    let lastFpsUpdate = performance.now();
    let lastAdaptiveCheck = performance.now();
    let fps = 60;

    function canvasAnimationLoop() {
        if (!canvasRenderer || !canvasCamera) {
            animationFrameId = requestAnimationFrame(canvasAnimationLoop);
            return;
        }

        const now = performance.now();

        // Update camera to follow player
        const playerPos = canvasRenderer.gameState.playerPos;
        if (playerPos) {
            canvasCamera.setTarget(playerPos.x, playerPos.y);
        }

        // Update camera with smooth easing
        canvasCamera.update();

        // Update compass direction
        if (canvasRenderer.gameState.exits) {
            canvasRenderer.updateCompass();
        }

        // Update ambient effects (Phase 5a)
        if (gmcpIntegration && gmcpIntegration.timeOfDaySystem) {
            const dt = 16; // ~60fps delta
            gmcpIntegration.timeOfDaySystem.update();
            gmcpIntegration.weatherSystem.update(undefined, dt);
            gmcpIntegration.ambientEffectsSystem.update(
                gmcpIntegration.timeOfDaySystem.timeOfDay,
                gmcpIntegration.currentSeason,
                gmcpIntegration.weatherSystem.currentWeather,
                dt
            );
        }

        // Schedule render
        canvasRenderer.scheduleRender();

        // Performance profiling (Phase 5c)
        if (performanceProfiler) {
            performanceProfiler.updateFrameTiming();
            performanceProfiler.updateMemoryUsage();

            // Adaptive quality adjustment every 2 seconds
            if (now - lastAdaptiveCheck > 2000 && performanceProfiler.fps > 0) {
                qualitySettings.adaptiveAdjustment(performanceProfiler.fps);
                lastAdaptiveCheck = now;
            }
        }

        // FPS monitoring
        frameCount++;
        if (now - lastFpsUpdate > 500) {
            fps = Math.round((frameCount * 1000) / (now - lastFpsUpdate));
            frameCount = 0;
            lastFpsUpdate = now;

            // Warn if FPS drops below 50
            if (fps < 50 && performanceProfiler) {
                console.warn(`Canvas FPS: ${fps} (performance warning)`);
            }
        }

        // Continue loop
        animationFrameId = requestAnimationFrame(canvasAnimationLoop);
    }

    let ws = null;
    let connected = false;
    let authenticated = false; // Track actual game authentication, not just connection
    let inputBuffer = "";

    // ── Login Screen Handlers (Phase 3) ──

    const loginOverlay = document.getElementById("login-overlay");
    const loginForm = document.getElementById("login-form");
    const loginMessage = document.getElementById("login-message");
    const loginUsername = document.getElementById("login-username");
    const loginPassword = document.getElementById("login-password");
    const btnRegister = document.getElementById("btn-register");

    // Initialize login overlay hidden (show after connection)
    if (loginOverlay) {
        loginOverlay.style.display = "none";
    }

    function showLoginMessage(message, type = "info") {
        if (loginMessage) {
            loginMessage.textContent = message;
            loginMessage.className = `form-message ${type}`;
            loginMessage.style.display = "block";
        }
    }

    function hideLogin() {
        if (loginOverlay) {
            loginOverlay.classList.add("hidden");
            loginOverlay.classList.remove("loading");
            loginOverlay.style.display = "none";
        }
        // Clear form for next login attempt
        if (loginUsername) {
            loginUsername.value = "";
            loginUsername.disabled = false;
        }
        if (loginPassword) {
            loginPassword.value = "";
            loginPassword.disabled = false;
        }
        if (loginMessage) {
            loginMessage.style.display = "none";
        }
    }

    function showLogin() {
        if (loginOverlay) {
            loginOverlay.classList.remove("hidden");
            loginOverlay.style.display = "flex";
            if (loginUsername) {
                setTimeout(() => loginUsername.focus(), 100);
            }
        }
    }

    if (loginForm) {
        loginForm.addEventListener("submit", (e) => {
            e.preventDefault();
            const username = loginUsername.value.trim();
            const password = loginPassword.value;

            if (!username || !password) {
                showLoginMessage("Please enter username and password", "error");
                return;
            }

            // Disable inputs and show loading state (apply to overlay for CSS)
            if (loginOverlay) {
                loginOverlay.classList.add("loading");
            }
            if (loginUsername) loginUsername.disabled = true;
            if (loginPassword) loginPassword.disabled = true;

            // Send username first (server will prompt for password)
            sendCommand(username);
            showLoginMessage("Sending credentials...", "info");

            // Password will be sent automatically when server prompts for it
            // (see message handler below)
        });
    }

    if (btnRegister) {
        btnRegister.addEventListener("click", (e) => {
            e.preventDefault();
            // Placeholder for registration flow
            showLoginMessage("Registration coming soon", "info");
        });
    }

    // Show login when not authenticated (connection may be open but player not logged in yet)
    function updateLoginUI() {
        if (!authenticated) {
            showLogin();
        } else {
            hideLogin();
        }
    }

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
    const effectsList = document.getElementById("effects-list");
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
        "kill", "flee", "cast", "spells", "abilities", "effects",
        "help", "quit", "clear", "colors",
    ];
    let tabMatches = [];
    let tabIndex = 0;
    let tabOriginalPrefix = "";
    let tabArgs = "";

    function tabComplete() {
        const parts = inputBuffer.split(" ");
        const firstWord = parts[0].toLowerCase();

        if (tabMatches.length > 0 && tabOriginalPrefix
            && firstWord !== tabOriginalPrefix
            && tabMatches.includes(firstWord)) {
            // still cycling — advance to next match
            tabIndex = (tabIndex + 1) % tabMatches.length;
        } else {
            // start a new completion
            tabOriginalPrefix = firstWord;
            tabArgs = parts.slice(1).join(" ");
            if (!tabOriginalPrefix) return;
            tabMatches = COMMANDS.filter(c => c.startsWith(tabOriginalPrefix) && c !== tabOriginalPrefix);
            if (tabMatches.length === 0) return;
            tabIndex = 0;
        }
        clearInputLine();
        inputBuffer = tabMatches[tabIndex] + (tabArgs ? " " + tabArgs : "");
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

        // When disconnected, clear auth state
        if (!isConnected) {
            authenticated = false;
        }

        // Update login UI (stays visible until authenticated)
        if (typeof updateLoginUI === "function") {
            updateLoginUI();
        }
    }

    function resetHud() {
        charName.textContent = "—";
        charInfo.textContent = "—";
        hpBar.style.width = "0%";
        hpText.textContent = "— / —";
        manaBar.style.width = "0%";
        manaText.textContent = "— / —";
        xpBar.style.width = "0%";
        xpBarText.textContent = "— / —";
        levelVal.textContent = "—";
        xpVal.textContent = "—";
        roomTitle.textContent = "—";
        roomDesc.textContent = "";
        exitsWrap.innerHTML = "";
        navExits.innerHTML = "";
        playersList.innerHTML = '<span class="empty-hint">—</span>';
        mobsList.innerHTML = '<span class="empty-hint">—</span>';
        invList.innerHTML = '<span class="empty-hint">—</span>';
        equipList.innerHTML = '<span class="empty-hint">—</span>';
        effectsList.innerHTML = '<span class="empty-hint">None</span>';
        visitedRooms.clear();
        currentRoomId = null;
        renderMap();

        // Re-enable login form for next attempt
        if (loginOverlay) {
            loginOverlay.classList.remove("loading");
        }
        if (loginUsername) {
            loginUsername.disabled = false;
            loginUsername.value = "";
        }
        if (loginPassword) {
            loginPassword.disabled = false;
            loginPassword.value = "";
        }
        if (loginMessage) {
            loginMessage.style.display = "none";
        }
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
        const xpInto = data.xpIntoLevel ?? 0;
        const xpNeeded = data.xpToNextLevel;
        if (xpNeeded != null && xpNeeded > 0) {
            const pct = Math.min(100, Math.round((xpInto / xpNeeded) * 100));
            xpBar.style.width = `${pct}%`;
            xpBarText.textContent = `${xpInto.toLocaleString()} / ${xpNeeded.toLocaleString()}`;
        } else if (xpNeeded === null) {
            xpBar.style.width = "100%";
            xpBarText.textContent = "MAX";
        } else {
            xpBar.style.width = "0%";
            xpBarText.textContent = "— / —";
        }
        const goldVal = document.getElementById("gold-val");
        if (goldVal) {
            goldVal.textContent = (data.gold ?? 0).toLocaleString();
        }

        // Canvas integration: update player vitals
        if (canvasRenderer && gmcpIntegration) {
            gmcpIntegration.handleCharVitals(data);
            canvasRenderer.scheduleRender();
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

        // Canvas integration: update room data
        if (canvasRenderer && gmcpIntegration) {
            gmcpIntegration.handleRoomInfo(data);
            canvasRenderer.scheduleRender();
        }
    }

    function updateCharName(data) {
        charName.textContent = data.name ?? "—";
        const race = data.race ?? "";
        const cls = data["class"] ?? "";
        const level = data.level ?? "";
        charInfo.textContent = level ? `Level ${level} ${race} ${cls}` : "—";

        // Mark as authenticated once we receive character name (login successful)
        if (data.name && !authenticated) {
            authenticated = true;
            updateLoginUI();
            console.log("Authentication successful, hiding login overlay");
        }
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
            // Update canvas with empty players
            if (canvasRenderer && gmcpIntegration) {
                gmcpIntegration.handleRoomEntities({ players: [] });
                canvasRenderer.scheduleRender();
            }
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

        // Canvas integration: update players here
        if (canvasRenderer && gmcpIntegration) {
            gmcpIntegration.handleRoomEntities({ players: data });
            canvasRenderer.scheduleRender();
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
            // Update canvas with empty mobs
            if (canvasRenderer && gmcpIntegration) {
                gmcpIntegration.handleRoomEntities({ mobs: [] });
                canvasRenderer.scheduleRender();
            }
            return;
        }
        for (const m of data) {
            mobsList.appendChild(createMobElement(m));
        }

        // Canvas integration: update mobs
        if (canvasRenderer && gmcpIntegration) {
            gmcpIntegration.handleRoomEntities({ mobs: data });
            canvasRenderer.scheduleRender();
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

    function updateStatusEffects(data) {
        effectsList.innerHTML = "";
        if (!Array.isArray(data) || data.length === 0) {
            effectsList.innerHTML = '<span class="empty-hint">None</span>';
            return;
        }
        for (const e of data) {
            const el = document.createElement("div");
            el.className = "effect-item";
            const nameSpan = document.createElement("span");
            nameSpan.className = "effect-name";
            nameSpan.textContent = e.name + (e.stacks > 1 ? ` x${e.stacks}` : "");
            const typeSpan = document.createElement("span");
            typeSpan.className = `effect-type effect-type-${e.type.toLowerCase()}`;
            typeSpan.textContent = e.type;
            const timeSpan = document.createElement("span");
            timeSpan.className = "effect-time";
            const secs = Math.max(1, Math.ceil(e.remainingMs / 1000));
            timeSpan.textContent = `${secs}s`;
            el.appendChild(nameSpan);
            el.appendChild(typeSpan);
            el.appendChild(timeSpan);
            effectsList.appendChild(el);
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
            case "Char.StatusEffects":
                updateStatusEffects(data);
                break;

            // Canvas rendering integration (Phase 4)
            case "Room.Map":
                if (gmcpIntegration) gmcpIntegration.handleRoomMap(data);
                break;
            case "Room.Entities":
                if (gmcpIntegration) gmcpIntegration.handleRoomEntities(data);
                if (gmcpIntegration) gmcpIntegration.scheduleRender();
                break;
            case "Combat.Damage":
                if (gmcpIntegration) gmcpIntegration.handleCombatDamage(data);
                if (gmcpIntegration) gmcpIntegration.scheduleRender();
                break;
            case "Abilities.Cast":
                if (gmcpIntegration) gmcpIntegration.handleAbilityCast(data);
                if (gmcpIntegration) gmcpIntegration.scheduleRender();
                break;
            case "Combat.GroundEffect":
                if (gmcpIntegration) gmcpIntegration.handleGroundEffect(data);
                if (gmcpIntegration) gmcpIntegration.scheduleRender();
                break;
            case "Room.Ambiance":
                if (gmcpIntegration) gmcpIntegration.handleRoomAmbiance(data);
                break;
            case "Room.Adjacent":
                if (gmcpIntegration) gmcpIntegration.handleRoomAdjacent(data);
                if (gmcpIntegration) gmcpIntegration.scheduleRender();
                break;

            // Existing default cases
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
                    // Check for password prompt and auto-submit if password is available
                    if (!authenticated && loginPassword && loginPassword.value) {
                        const text = event.data.toLowerCase();
                        if (text.includes("password") && (text.includes("enter") || text.includes(":"))) {
                            // Server is asking for password, send it automatically
                            // Clear value immediately to prevent duplicate sends from subsequent messages
                            const password = loginPassword.value;
                            loginPassword.value = "";
                            setTimeout(() => {
                                sendCommand(password);
                                showLoginMessage("Authenticating...", "info");
                            }, 50);
                        }
                    }
                    term.write(event.data);
                }
            }
        });

        ws.addEventListener("close", () => {
            setConnected(false);
            inputBuffer = "";
            resetHud();
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
