// Automatically detect environment
const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' || window.location.hostname === '';
const cloudUrl = 'dehla-pakad-web.onrender.com';
const hostAddress = isLocal ? 'localhost:7070' : cloudUrl;
const protocol = isLocal ? "ws://" : "wss://";
const wsUrl = protocol + hostAddress + "/game";

let ws = null;
let mySecretToken = localStorage.getItem("dehlaToken");
let reconnectAttempts = 0;
let reconnectTimer = null;
let heartbeatInterval = null;
let pongTimeout = null;
// 🌟 CACHE TRACKERS: Prevent endless animations
let lastTrickCache = "";
let lastHandCache = "";
// ==========================================
// 🌟 AUDIO ENGINE
// ==========================================
let isMuted = false;
let hasPlayedBowniCallThisRound = false;
let hasPlayedVictorySoundThisRound = false;
let prevTableCardCount = 0;
let prevDehlaScoreA = 0;
let prevDehlaScoreB = 0;

function toggleMute() {
    isMuted = !isMuted;
    const btn = document.getElementById("mute-toggle-btn");
    btn.innerText = isMuted ? "🔇 Unmute Sounds" : "🔊 Mute Sounds";
}

function playSound(soundId, allowOverlap = false) {
    if (isMuted) return;
    const audio = document.getElementById(soundId);
    if (audio) {
        if (allowOverlap) {
            const clonedAudio = audio.cloneNode();
            clonedAudio.play().catch(e => console.log("Audio blocked by browser:", e));
        } else {
            audio.currentTime = 0;
            audio.play().catch(e => console.log("Audio blocked by browser:", e));
        }
    }
}

// ==========================================
// 🌟 RESILIENT CONNECTION MANAGER
// ==========================================
function connectWebSocket() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
        return;
    }

    setLocalConnectionStatus("ORANGE");
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log("[WS] Connected successfully.");
        reconnectAttempts = 0;
        setLocalConnectionStatus("GREEN");

        document.getElementById("lobby-status").innerText = "Connected! Welcome.";
        document.getElementById("lobby-status").className = "text-success";
        document.getElementById("lobby-controls").style.display = "block";

        startHeartbeat();

        // If resuming session, dispatch token immediately
        if (mySecretToken) {
            document.getElementById("lobby-status").innerText = "Reconnecting...";
            document.getElementById("lobby-controls").style.display = "none";
            safeSend({ action: "RECONNECT", playerToken: mySecretToken });
        }
    };

    ws.onmessage = (event) => {
        handleServerMessage(event.data);
    };

    ws.onclose = (event) => {
        console.warn("[WS] Socket closed. Initiating auto-reconnect...");
        stopHeartbeat();
        setLocalConnectionStatus("ORANGE");
        scheduleReconnect();
    };

    ws.onerror = (err) => {
        console.error("[WS] Socket error encountered:", err);
        ws.close();
    };
}

function safeSend(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

function startHeartbeat() {
    stopHeartbeat();
    heartbeatInterval = setInterval(() => {
        safeSend({ action: "PING" });

        // 🌟 ZOMBIE SOCKET KILLER: If the server doesn't reply in 2.5 seconds, the internet is dead.
        pongTimeout = setTimeout(() => {
            console.warn("[WS] Ping timeout! Internet dropped silently. Forcing reconnect...");
            if (ws) ws.close(); // This safely triggers your ws.onclose loop and UI overlay
        }, 2500);

    }, 3500);
}

function stopHeartbeat() {
    if (heartbeatInterval) {
        clearInterval(heartbeatInterval);
        heartbeatInterval = null;
    }
    // 🌟 BUG FIX: Disarm the zombie fuse if the connection closes naturally
    if (pongTimeout) {
        clearTimeout(pongTimeout);
        pongTimeout = null;
    }
}

function scheduleReconnect() {
    if (reconnectTimer) return;

    // Exponential backoff: 1s, 2s, 4s, max 8s
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 8000);
    reconnectAttempts++;

    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connectWebSocket();
    }, delay);
}

// Mobile Backgrounding & Tab Switching Watcher
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
        console.log("[APP] Tab foregrounded. Validating connection state...");
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            connectWebSocket();
        } else {
            safeSend({ action: "PING" });
        }
    }
});

window.addEventListener("pageshow", (event) => {
    if (event.persisted && (!ws || ws.readyState !== WebSocket.OPEN)) {
        connectWebSocket();
    }
});
// 🌟 NEW: OS-Level Instant Network Detection
window.addEventListener('offline', () => {
    console.warn("[NETWORK] OS detected offline status. Instantly updating UI.");
    setLocalConnectionStatus("ORANGE");
    if (ws) ws.close(); // Force the reconnect loop to begin immediately
});

window.addEventListener('online', () => {
    console.log("[NETWORK] OS detected online status.");
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        connectWebSocket();
    }
});

function setLocalConnectionStatus(color) {
    const myLed = document.querySelector("#pod-me .turn-indicator");
    if (myLed) {
        myLed.className = "turn-indicator " + (color === "GREEN" ? "led-green" : color === "ORANGE" ? "led-orange" : "led-red");
    }
}

// Initial Connection
connectWebSocket();

// ==========================================
// 🌟 CARD HELPERS & RENDERING
// ==========================================
function getSVGFilename(cardStr) {
    const parts = cardStr.split(" of ");
    const rank = parts[0];
    const suit = parts[1].toLowerCase();

    const rankMap = {
        'ACE': 'ace', 'KING': 'king', 'QUEEN': 'queen', 'JACK': 'jack',
        'TEN': '10', 'NINE': '9', 'EIGHT': '8', 'SEVEN': '7',
        'SIX': '6', 'FIVE': '5', 'FOUR': '4', 'THREE': '3', 'TWO': '2'
    };
    const mappedRank = rankMap[rank] || rank.toLowerCase();
    return `${mappedRank}_of_${suit}.svg`;
}

function createCardHTML(cardStr, isClickable, isCenter = false) {
    const fileName = getSVGFilename(cardStr);
    const clickEvent = isClickable ? `onclick="playCard('${cardStr}')"` : "";
    const disableClass = (!isClickable && !isCenter) ? "card-disabled" : "";

    return `
        <div class="playing-card ${disableClass}" ${clickEvent}
             style="background-image: url('main/resources/cards/${fileName}');">
        </div>`;
}

function sortHand(handArray) {
    if (!handArray) return [];

    const rankWeights = {
        'ACE': 14, 'KING': 13, 'QUEEN': 12, 'JACK': 11, 'TEN': 10,
        'NINE': 9, 'EIGHT': 8, 'SEVEN': 7, 'SIX': 6, 'FIVE': 5,
        'FOUR': 4, 'THREE': 3, 'TWO': 2
    };

    const suitWeights = {
        'SPADES': 4,
        'HEARTS': 3,
        'CLUBS': 2,
        'DIAMONDS': 1
    };

    return handArray.sort((a, b) => {
        const [rankA, suitA] = a.split(" of ");
        const [rankB, suitB] = b.split(" of ");

        if (suitWeights[suitA] !== suitWeights[suitB]) {
            return suitWeights[suitB] - suitWeights[suitA];
        }
        return rankWeights[rankB] - rankWeights[rankA];
    });
}

// ==========================================
// 🌟 SERVER STATE HANDLER
// ==========================================
function handleServerMessage(data) {
    const state = JSON.parse(data);
    // 🌟 DISARM THE FUSE: The server is alive!
        if (state.action === "PONG") {
            clearTimeout(pongTimeout);
            return; // Stop processing, no UI updates needed for a pong
        }
    if (state.errorMessage) {
        if (state.errorType === "RULE_VIOLATION") {
            playSound("sfx-error");
        } else {
            alert(state.errorMessage);
            if (state.errorMessage.includes("expired") || state.errorMessage.includes("rejoin") || state.errorMessage.includes("dissolved")) {
                localStorage.removeItem("dehlaToken");
                mySecretToken = null;
                location.reload();
            }
        }
        return;
    }

    if (state.myToken && state.myToken !== mySecretToken) {
        mySecretToken = state.myToken;
        localStorage.setItem("dehlaToken", mySecretToken);
    }

    // Switch viewports cleanly
    document.getElementById("lobby-screen").style.setProperty("display", "none", "important");
    document.getElementById("game-screen").style.setProperty("display", "block", "important");
    document.getElementById("menu-room-code").innerText = state.roomCode;

    // HUD Updates
    const tSuit = state.trumpSuit === "HEARTS" ? "♥️" : state.trumpSuit === "DIAMONDS" ? "♦️" : state.trumpSuit === "SPADES" ? "♠️" : state.trumpSuit === "CLUBS" ? "♣️" : "NONE";
    document.getElementById("trump").innerText = tSuit;
    document.getElementById("menu-scoreA").innerText = state.teamAScore;
    document.getElementById("menu-scoreB").innerText = state.teamBScore;
    document.getElementById("pile-counter").innerText = `Pile: ${state.accumulatedPileSize} cards`;
  //Spectators list and count handling starts here

      // 🌟 1. SPECTATOR CSS & BADGE INJECTION
      document.body.classList.toggle("spectator-mode", !!state.isSpectator);

      const specBadge = document.getElementById("spectator-badge");
      if (state.spectatorCount > 0) {
          specBadge.style.display = "block";
          document.getElementById("spectator-count-text").innerText = state.spectatorCount;

          const specTable = document.getElementById("spectators-table-body");
          specTable.innerHTML = "";
          if (state.spectatorNames) {
              state.spectatorNames.forEach(name => {
                  specTable.innerHTML += `<tr><td class="text-white fw-bold ps-3 py-2">👁️ ${name}</td></tr>`;
              });
          }
      } else {
          specBadge.style.display = "none";
      }
      //Spectators list and count handling Ends here

    // Anti-Clockwise Seating
    // Anti-Clockwise Seating
        let myIndex = -1;
        if (state.seatingPlayerIds && state.seatingPlayerIds.length > 0) {
            myIndex = state.isSpectator ? 0 : (state.myPlayerId ? state.seatingPlayerIds.indexOf(state.myPlayerId) : -1);
        }

   let rightPlayer = "Waiting...", topPlayer = "Waiting...", leftPlayer = "Waiting...";
       let bottomPlayerId = null, rightPlayerId = null, topPlayerId = null, leftPlayerId = null;
       let bottomPlayerName = "Waiting...";

    if (myIndex !== -1 && state.seatingOrder && state.seatingPlayerIds) {
        const len = state.seatingPlayerIds.length;
        bottomPlayerId = state.seatingPlayerIds[myIndex];
        bottomPlayerName = state.isSpectator ? state.seatingOrder[myIndex] : (state.myName + " (You)");
        rightPlayer = state.seatingOrder[(myIndex + 1) % len] || "Waiting...";
        topPlayer = state.seatingOrder[(myIndex + 2) % len] || "Waiting...";
        leftPlayer = state.seatingOrder[(myIndex + 3) % len] || "Waiting...";

        rightPlayerId = state.seatingPlayerIds[(myIndex + 1) % len];
        topPlayerId = state.seatingPlayerIds[(myIndex + 2) % len];
        leftPlayerId = state.seatingPlayerIds[(myIndex + 3) % len];

        const getBadge = (playerId) => {
            if (!state.playerTeamsById || !playerId) return "";
            const team = state.playerTeamsById[playerId];
            if (!team) return "";
            return team === "TEAM_A"
                ? `<span class="badge-team-a" style="font-size: 0.75rem;">A</span>`
                : `<span class="badge-team-b" style="font-size: 0.75rem;">B</span>`;
        };

       document.getElementById("badge-me").innerHTML = getBadge(bottomPlayerId);
        document.getElementById("badge-right").innerHTML = getBadge(rightPlayerId);
        document.getElementById("badge-top").innerHTML = getBadge(topPlayerId);
        document.getElementById("badge-left").innerHTML = getBadge(leftPlayerId);

        // Names remain unpolluted without (Wait...) strings
        document.getElementById("bottom-pod-name").innerText = bottomPlayerName;
        document.getElementById("right-pod-name").innerText = rightPlayer;
        document.getElementById("top-pod-name").innerText = topPlayer;
        document.getElementById("left-pod-name").innerText = leftPlayer;

        // 🌟 3-COLOR LED CONNECTION STATUS UPDATE (Zero UI Lies)
        const applyLED = (podId, playerId) => {
            const led = document.querySelector(`#${podId} .turn-indicator`);
            if (!led) return;

            if (!playerId || !state.connectionStatuses || !state.connectionStatuses[playerId]) {
                led.className = "turn-indicator led-green";
                return;
            }

            const status = state.connectionStatuses[playerId];
            if (status === "RED") {
                led.className = "turn-indicator led-red";
            } else if (status === "ORANGE") {
                led.className = "turn-indicator led-orange";
            } else {
                led.className = "turn-indicator led-green";
            }
        };

        // If local socket is in reconnect state, force own LED to Orange locally
        if (ws.readyState !== WebSocket.OPEN && !state.isSpectator) {
                    setLocalConnectionStatus("ORANGE");
                } else {
                    applyLED("pod-me", bottomPlayerId);
                }

        applyLED("pod-right", rightPlayerId);
        applyLED("pod-top", topPlayerId);
        applyLED("pod-left", leftPlayerId);
    }

    // Rules Menu
    const evenRuleSpan = document.getElementById("menu-rule-even");
    if (evenRuleSpan) {
        evenRuleSpan.innerHTML = state.isEvenDehla ? "<span class='text-success'>🟢 ON</span>" : "<span class='text-danger'>🔴 OFF</span>";
        document.getElementById("menu-rule-limit").innerText = state.maxRounds ? state.maxRounds : "Unlimited";
    }

    // 🌟 TURN BORDER GLOW (Strictly decoupled from LED indicator)
    document.getElementById("pod-me").classList.remove("active-turn");
    document.getElementById("pod-right").classList.remove("active-turn");
    document.getElementById("pod-top").classList.remove("active-turn");
    document.getElementById("pod-left").classList.remove("active-turn");

    if (!state.isPaused) {
            if (state.currentTurnPlayerId === bottomPlayerId) {
                document.getElementById("pod-me").classList.add("active-turn");
            } else if (state.currentTurnPlayerId === rightPlayerId) {
            document.getElementById("pod-right").classList.add("active-turn");
        } else if (state.currentTurnPlayerId === topPlayerId) {
            document.getElementById("pod-top").classList.add("active-turn");
        } else if (state.currentTurnPlayerId === leftPlayerId) {
            document.getElementById("pod-left").classList.add("active-turn");
        }
    }

    // Audio SFX Triggers
    const currentTableCardCount = state.currentTrickCards ? state.currentTrickCards.length : 0;
    if (currentTableCardCount > prevTableCardCount) {
        playSound("sfx-card-snap", true);
    }
    prevTableCardCount = currentTableCardCount;

    const teamACapturedDehla = state.teamAScore > prevDehlaScoreA;
    const teamBCapturedDehla = state.teamBScore > prevDehlaScoreB;
    if (teamACapturedDehla || teamBCapturedDehla) {
        const isBowniEnding = (state.currentPhase === "ROUND_OVER" || state.currentPhase === "MATCH_OVER") && state.bowniTeam;
        if (!isBowniEnding) playSound("sfx-dehla-capture");
    }
    prevDehlaScoreA = state.teamAScore;
    prevDehlaScoreB = state.teamBScore;

    // Render Table Cards with Directional Toss
   // ==========================================
       // RENDER TABLE CARDS (With Animation Protection)
       // ==========================================
       const newTrickCache = JSON.stringify(state.currentTrickCards || []);

       // 🛡️ Only wipe and animate if the table cards actually changed!
       if (lastTrickCache !== newTrickCache) {
           lastTrickCache = newTrickCache;
           const tableDiv = document.getElementById("table-cards");
           tableDiv.innerHTML = "";

           if (state.currentTrickCards && state.trickPlayerIds) {
               state.currentTrickCards.forEach((cardStr, index) => {
                   const cardHTML = createCardHTML(cardStr, false, true);
                   const playedById = state.trickPlayerIds[index];

                   let animationDir = 'bottom'; // Me
                   if (playedById === rightPlayerId) animationDir = 'right';
                   if (playedById === topPlayerId) animationDir = 'top';
                   if (playedById === leftPlayerId) animationDir = 'left';

                   tableDiv.innerHTML += `<div class="position-absolute" style="animation: toss-${animationDir} 0.4s ease-out forwards; z-index: ${index};">${cardHTML}</div>`;
               });
           }
       }

    // Render Hand (Blocked completely during disconnection / pauses)
// ==========================================
    // RENDER MY HAND (With Re-render Protection)
    // ==========================================
    const isSocketOpen = (ws && ws.readyState === WebSocket.OPEN);
    const canIPlay = isSocketOpen && state.isMyTurn && !state.isPaused && (state.currentPhase === "MAIN_PLAY" || state.currentPhase === "DISCOVERING_TRUMP");

    // We add 'canIPlay' to the cache so the cards update if it becomes our turn
    const newHandCache = JSON.stringify(state.myHand || []) + canIPlay;

    if (lastHandCache !== newHandCache) {
        lastHandCache = newHandCache;
        const handDiv = document.getElementById("my-hand");
        handDiv.innerHTML = "";

        const sortedHand = sortHand(state.myHand);
        sortedHand.forEach(cardStr => {
            handDiv.innerHTML += createCardHTML(cardStr, canIPlay, false);
        });
    }
    if (!state.bowniTeam) hasPlayedBowniCallThisRound = false;
    if (state.currentPhase !== "ROUND_OVER" && state.currentPhase !== "MATCH_OVER") hasPlayedVictorySoundThisRound = false;

    document.getElementById("bowni-container").style.display = (state.currentPhase === "BOWNI_DECLARATION") ? "block" : "none";
    const badge = document.getElementById("bowni-badge");
    if (state.bowniTeam) {
        badge.style.display = "block";
        badge.innerText = `🔥 ${state.bowniTeam === 'TEAM_A' ? 'A' : 'B'} : Bowni!`;
        badge.className = "hud-badge text-white m-0 " + (state.bowniTeam === 'TEAM_A' ? 'bowni-neon-a' : 'bowni-neon-b');
        if (!hasPlayedBowniCallThisRound && state.currentPhase === "MAIN_PLAY") {
            playSound("sfx-bowni-call");
            hasPlayedBowniCallThisRound = true;
        }
    } else {
        badge.style.display = "none";
    }



    // Scoreboard Rendering
    if (state.historyTeamA) {
        const mainTbody = document.getElementById("main-scoreboard-body");
        const victoryTbody = document.getElementById("victory-scoreboard-body");
        mainTbody.innerHTML = "";
        victoryTbody.innerHTML = "";
        for (let i = 0; i < state.historyTeamA.length; i++) {
            const rowHTML = `<tr><td>${i + 1}</td><td class="text-success">+${state.historyTeamA[i]}</td><td class="text-success">+${state.historyTeamB[i]}</td></tr>`;
            mainTbody.innerHTML += rowHTML;
            victoryTbody.innerHTML += rowHTML;
        }
        document.getElementById("history-total-A").innerText = state.matchScoreA || 0;
        document.getElementById("history-total-B").innerText = state.matchScoreB || 0;
    }

    // Victory Modals
    const victoryScreen = document.getElementById("victory-screen");
    if (state.currentPhase === "ROUND_OVER" || state.currentPhase === "MATCH_OVER") {
        let currentPtsA = 0, currentPtsB = 0;
        if (state.historyTeamA && state.historyTeamA.length > 0) {
            currentPtsA = state.historyTeamA[state.historyTeamA.length - 1];
            currentPtsB = state.historyTeamB[state.historyTeamB.length - 1];
        }
        document.getElementById("modal-dehlasA").innerText = state.teamAScore;
        document.getElementById("modal-dehlasB").innerText = state.teamBScore;
        document.getElementById("modal-pointsA").innerText = currentPtsA;
        document.getElementById("modal-pointsB").innerText = currentPtsB;
        document.getElementById("modal-match-score").innerText = `${state.matchScoreA || 0} - ${state.matchScoreB || 0}`;

        let winnerText = "It's a Tie!";
        if (state.bowniTeam) {
            if (state.bowniTeam === 'TEAM_A') {
                if (state.teamAScore === 4) {
                    winnerText = "🔥 TEAM A BOWNI SUCCESSFUL! 🔥";
                    if (!hasPlayedVictorySoundThisRound){ playSound("sfx-bowni-success"); hasPlayedVictorySoundThisRound = true;}
                } else {
                    winnerText = "💥 BOWNI FAILED! TEAM B WINS! 💥";
                    if (!hasPlayedVictorySoundThisRound) { playSound("sfx-bowni-fail"); hasPlayedVictorySoundThisRound = true;}
                }
            } else if (state.bowniTeam === 'TEAM_B') {
                if (state.teamBScore === 4) {
                    winnerText = "🔥 TEAM B BOWNI SUCCESSFUL! 🔥";
                    if (!hasPlayedVictorySoundThisRound){ playSound("sfx-bowni-success"); hasPlayedVictorySoundThisRound = true;}
                } else {
                    winnerText = "💥 BOWNI FAILED! TEAM A WINS! 💥";
                    if (!hasPlayedVictorySoundThisRound){ playSound("sfx-bowni-fail"); hasPlayedVictorySoundThisRound = true;}
                }
            }
        } else {
            if (state.teamAScore > state.teamBScore) winnerText = "Team A Wins Round!";
            if (state.teamBScore > state.teamAScore) winnerText = "Team B Wins Round!";
            if (state.teamAScore === 4) winnerText = "🏆 TEAM A NATURAL KOT! 🏆";
            if (state.teamBScore === 4) winnerText = "🏆 TEAM B NATURAL KOT! 🏆";
        }

        document.getElementById("victory-title").innerText = winnerText;

        if (state.currentPhase === "MATCH_OVER") {
            document.getElementById("victory-buttons").style.setProperty("display", "none", "important");
            document.getElementById("game-over-message").style.display = "block";
            document.querySelector("#victory-screen h2").innerText = "Match Finished!";

            const grandMsg = document.getElementById("game-over-message");
            if (state.matchScoreA > state.matchScoreB) {
                grandMsg.innerHTML = "🏆 TEAM A WINS THE MATCH! 🏆<br><button class='btn btn-light mt-3 px-4 rounded-pill fw-bold text-dark' onclick='leaveRoom()'>Leave Table</button>";
            } else if (state.matchScoreB > state.matchScoreA) {
                grandMsg.innerHTML = "🏆 TEAM B WINS THE MATCH! 🏆<br><button class='btn btn-light mt-3 px-4 rounded-pill fw-bold text-dark' onclick='leaveRoom()'>Leave Table</button>";
            } else {
                grandMsg.innerHTML = "🤝 TIE MATCH! 🤝<br><button class='btn btn-light mt-3' onclick='leaveRoom()'>Leave Table</button>";
            }
        } else {
            document.getElementById("victory-buttons").style.display = "flex";
            document.getElementById("game-over-message").style.display = "none";
            document.querySelector("#victory-screen h2").innerText = "Round Over!";
        }

        victoryScreen.style.setProperty("display", "flex", "important");
    } else {
        victoryScreen.style.setProperty("display", "none", "important");
    }
}

// ==========================================
// 🌟 USER ACTIONS
// ==========================================
let currentLimit = 5;
let stepperInterval = null;
let stepTimeout = null;

function toggleStepper() {
    const isChecked = document.getElementById("roundLimitToggle").checked;
    document.getElementById("stepper-ui").style.display = isChecked ? "flex" : "none";
}

function startStepper(dir) {
    step(dir);
    stepTimeout = setTimeout(() => {
        stepperInterval = setInterval(() => step(dir), 100);
    }, 400);
}

function stopStepper() {
    clearTimeout(stepTimeout);
    clearInterval(stepperInterval);
}

function step(dir) {
    if (dir === 'up' && currentLimit < 100) currentLimit++;
    else if (dir === 'down' && currentLimit > 1) currentLimit--;

    document.getElementById("round-limit-val").innerText = currentLimit;

    const btnDown = document.getElementById("step-down");
    const btnUp = document.getElementById("step-up");
    btnDown.classList.toggle("disabled", currentLimit <= 1);
    btnUp.classList.toggle("disabled", currentLimit >= 100);

    if (currentLimit <= 1 || currentLimit >= 100) stopStepper();
}

function createRoom() {
    const name = document.getElementById("player-name").value.trim();
    if (!name) {
        alert("Enter your name!");
        return;
    }
    const isEvenDehla = document.getElementById("evenDehlaToggle").checked;
    const isLimitEnabled = document.getElementById("roundLimitToggle").checked;

    safeSend({
        action: "CREATE_ROOM",
        playerName: name,
        settings: {
            strictSweepEnabled: isEvenDehla,
            maxRounds: isLimitEnabled ? currentLimit : null
        }
    });
}

function preloadCardImages() {
    const suits = ['hearts', 'diamonds', 'clubs', 'spades'];
    const ranks = ['ace', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'jack', 'queen', 'king'];

    suits.forEach(suit => {
        ranks.forEach(rank => {
            const img = new Image();
            img.src = `main/resources/cards/${rank}_of_${suit}.svg`;
        });
    });
}
preloadCardImages();

function toggleGameMenu() { const menu = document.getElementById("game-menu-modal"); menu.style.display = (menu.style.display === "none" || menu.style.display === "") ? "flex" : "none"; }
function toggleScoreboard() { const screen = document.getElementById("scoreboard-screen"); document.getElementById("game-menu-modal").style.display = "none"; screen.style.display = (screen.style.display === "none" || screen.style.display === "") ? "flex" : "none"; }
function toggleRulesScreen() { const screen = document.getElementById("rules-screen"); document.getElementById("game-menu-modal").style.display = "none"; screen.style.display = (screen.style.display === "none" || screen.style.display === "") ? "flex" : "none"; }
function joinRoom() { 
    const name = document.getElementById("player-name").value.trim(); 
    const code = document.getElementById("join-code").value.trim().toUpperCase(); 
    if (!name || !code) { alert("Enter Name and Code!"); return; } 
    safeSend({ action: "JOIN_ROOM", playerName: name, roomCode: code }); 
}
//commenting it to add dynamic alert to avoid misstuch || Starts here
//function leaveRoom() {
//    safeSend({ action: "LEAVE_ROOM" });
//    localStorage.removeItem("dehlaToken");
//    setTimeout(() => { location.reload(); }, 100);
//}
//
//function finishGame() {
//    if (confirm("End game?")) safeSend({ action: "FINISH_GAME" });
//}
//commenting it to add dynamic alert to avoid misstuch || Ends here
// 🌟 1. The State-Aware Leave Room
function leaveRoom() {

    // Otherwise, throw the Danger Modal
    showDangerModal(
        "Leave Game?",
        "Are you sure you want to leave the room? This will disconnect you from the table.",
        executeLeaveRoom
    );
}

// The isolated network execution for leaving
function executeLeaveRoom() {
    safeSend({ action: "LEAVE_ROOM" });
    localStorage.removeItem("dehlaToken");
    setTimeout(() => { location.reload(); }, 100);
}

// 🌟 2. The Updated Finish Game
function finishGame() {
    showDangerModal(
        "End Match Early?",
        "Ending the match now will dissolve the room for everyone. Are you sure?",
        function() {
            safeSend({ action: "FINISH_GAME" });
        }
    );
}
// 🚨 UNIVERSAL DANGER MODAL ENGINE
let pendingDangerAction = null;

function showDangerModal(title, message, executionCallback) {
    document.getElementById("danger-title").innerText = title;
    document.getElementById("danger-message").innerText = message;

    // Auto-close Game Menu if it's open, to prevent stacked overlays
    document.getElementById("game-menu-modal").style.display = "none";

    pendingDangerAction = executionCallback;
    document.getElementById("danger-modal").style.display = "flex";
}

function closeDangerModal() {
    document.getElementById("danger-modal").style.display = "none";
    pendingDangerAction = null; // Clear memory
}

function confirmDangerModal() {
    document.getElementById("danger-modal").style.display = "none";
    if (pendingDangerAction && typeof pendingDangerAction === "function") {
        pendingDangerAction(); // Execute the stored network request
    }
    pendingDangerAction = null;
}

function playAgain() { 
    safeSend({ action: "PLAY_AGAIN" }); 
}

function playCard(cardString) { 
    // 🛡️ GUARD: Disallow clicks if socket is not strictly OPEN
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const parts = cardString.split(" of "); 
    safeSend({ action: "PLAY_CARD", suit: parts[1], rank: parts[0] }); 
}

function callBowni() { 
    safeSend({ action: "CALL_BOWNI" }); 
}
function toggleSpectatorModal() {
    const screen = document.getElementById("spectator-list-screen");
    document.getElementById("game-menu-modal").style.display = "none";
    screen.style.display = (screen.style.display === "none" || screen.style.display === "") ? "flex" : "none";
}

function joinAsSpectator() {
    const name = document.getElementById("player-name").value.trim();
    const code = document.getElementById("join-code").value.trim().toUpperCase();
    if (!name || !code) { alert("Enter Name and Code!"); return; }
    safeSend({ action: "JOIN_SPECTATOR", playerName: name, roomCode: code });
}

function toggleHowToPlay() {
    const screen = document.getElementById("how-to-play-modal");
    // Auto-close the main game menu if it happens to be open
    document.getElementById("game-menu-modal").style.display = "none";
    screen.style.display = (screen.style.display === "none" || screen.style.display === "") ? "flex" : "none";
}