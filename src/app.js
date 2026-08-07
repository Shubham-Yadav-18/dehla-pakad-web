
    // Automatically detect if we are testing locally or playing on the internet
    const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' || window.location.hostname === '';

    // ⚠️ PASTE YOUR RENDER DOMAIN HERE (No https://) ⚠️
    const cloudUrl = 'dehla-pakad-web.onrender.com';

    const hostAddress = isLocal ? 'localhost:7070' : cloudUrl;

    // The internet requires Secure WebSockets (wss://) instead of normal (ws://)
    const protocol = isLocal ? "ws://" : "wss://";

    const ws = new WebSocket(protocol + hostAddress + "/game");

    let mySecretToken = localStorage.getItem("dehlaToken");
    // ==================== Sound System starts ======================
      // 🌟 NEW: AUDIO ENGINE
      // ==========================================
      let isMuted = false;
      let hasPlayedBowniCallThisRound = false;
      let hasPlayedVictorySoundThisRound = false;
      // 🌟 AUDIO STATE TRACKERS
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
                  // Production trick: Clone the audio node so rapid card snaps can overlap naturally
                  const clonedAudio = audio.cloneNode();
                  clonedAudio.play().catch(e => console.log("Audio blocked by browser:", e));
              } else {
                  audio.currentTime = 0;
                  audio.play().catch(e => console.log("Audio blocked by browser:", e));
              }
          }
      }

      // Sound System ends

      ws.onopen = () => {
          document.getElementById("lobby-status").innerText = "Connected! Welcome.";
          document.getElementById("lobby-status").className = "text-success";
          document.getElementById("lobby-controls").style.display = "block";
          if (mySecretToken) {
              document.getElementById("lobby-status").innerText = "Reconnecting...";
              document.getElementById("lobby-controls").style.display = "none";
              ws.send(JSON.stringify({ action: "RECONNECT", playerToken: mySecretToken }));
          }
      };
      ws.onclose = () => { alert("Connection lost. Please refresh."); };

      // --- CARD TRANSLATOR MAGIC ---
// --- NEW SVG CARD MAGIC ---
      function getSVGFilename(cardStr) {
          const parts = cardStr.split(" of ");
          const rank = parts[0];
          const suit = parts[1].toLowerCase(); // e.g., "hearts"

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

          // 🌟 UPDATED PATH: Points to resources/cards/
          return `
              <div class="playing-card ${disableClass}" ${clickEvent}
                   style="background-image: url('main/resources/cards/${fileName}');">
              </div>`;
      }
      // --- SILENT HAND SORTING LOGIC ---
      function sortHand(handArray) {
          if (!handArray) return [];

          // 1. Define the hierarchy of cards
          const rankWeights = {
              'ACE': 14, 'KING': 13, 'QUEEN': 12, 'JACK': 11, 'TEN': 10,
              'NINE': 9, 'EIGHT': 8, 'SEVEN': 7, 'SIX': 6, 'FIVE': 5,
              'FOUR': 4, 'THREE': 3, 'TWO': 2
          };

          // 2. Define suit order (Alternating colors: Black, Red, Black, Red)
          const suitWeights = {
              'SPADES': 4,   // Black
              'HEARTS': 3,   // Red
              'CLUBS': 2,    // Black
              'DIAMONDS': 1  // Red
          };

          // 3. Sort the array
          return handArray.sort((a, b) => {
              const [rankA, suitA] = a.split(" of ");
              const [rankB, suitB] = b.split(" of ");

              // First, group by Suit
              if (suitWeights[suitA] !== suitWeights[suitB]) {
                  return suitWeights[suitB] - suitWeights[suitA];
              }
              // If suits are the same, order by Rank (Highest to Lowest)
              return rankWeights[rankB] - rankWeights[rankA];
          });
      }

      ws.onmessage = (event) => {
          const state = JSON.parse(event.data);
          if (state.errorMessage) {
            if (state.errorType === "RULE_VIOLATION") {
                playSound("sfx-error");
            } else{
                  alert(state.errorMessage);
                  if (state.errorMessage.includes("expired") || state.errorMessage.includes("rejoin")) {
                      localStorage.removeItem("dehlaToken"); location.reload();
                  }
              }

            return;
          }

          if (state.myToken && state.myToken !== mySecretToken) {
              mySecretToken = state.myToken; localStorage.setItem("dehlaToken", mySecretToken);
          }

         // 🛡️ Bulletproof hide/show that overrides all CSS
          const lobbyScreen = document.getElementById("lobby-screen");
          lobbyScreen.style.setProperty("display", "none", "important");

          document.getElementById("game-screen").style.setProperty("display", "block", "important");
          document.getElementById("menu-room-code").innerText = state.roomCode;

          // HUD Updates
          let tSuit = state.trumpSuit === "HEARTS" ? "♥️" : state.trumpSuit === "DIAMONDS" ? "♦️" : state.trumpSuit === "SPADES" ? "♠️" : state.trumpSuit === "CLUBS" ? "♣️" : "NONE";
          document.getElementById("trump").innerText = tSuit;
          document.getElementById("menu-scoreA").innerText = state.teamAScore;
          document.getElementById("menu-scoreB").innerText = state.teamBScore;
          document.getElementById("pile-counter").innerText = `Pile: ${state.accumulatedPileSize} cards`;

          // ==========================================
          // TRUE ANTI-CLOCKWISE SEATING LOGIC
          // ==========================================
          const myIndex = (state.seatingOrder && state.seatingOrder.length > 0) ? state.seatingOrder.indexOf(state.myName) : -1;
          let rightPlayer = "Waiting...", topPlayer = "Waiting...", leftPlayer = "Waiting...";

          if (myIndex !== -1 && state.seatingOrder.length > 0) {
              // Anti-Clockwise mapping: Right is +1, Top is +2, Left is +3
              rightPlayer = state.seatingOrder[(myIndex + 1) % state.seatingOrder.length] || "Waiting...";
              topPlayer = state.seatingOrder[(myIndex + 2) % state.seatingOrder.length] || "Waiting...";
              leftPlayer = state.seatingOrder[(myIndex + 3) % state.seatingOrder.length] || "Waiting...";

              if (myIndex !== -1 && state.seatingOrder.length > 0) {
              // Anti-Clockwise mapping: Right is +1, Top is +2, Left is +3
              rightPlayer = state.seatingOrder[(myIndex + 1) % state.seatingOrder.length] || "Waiting...";
              topPlayer = state.seatingOrder[(myIndex + 2) % state.seatingOrder.length] || "Waiting...";
              leftPlayer = state.seatingOrder[(myIndex + 3) % state.seatingOrder.length] || "Waiting...";

              // 🌟 NEW: Helper to generate Team Badges (Now without extra margin classes)
              const getBadge = (pName) => {
                  if (!state.playerTeams || pName === "Waiting...") return "";
                  const team = state.playerTeams[pName];
                  // Notice we removed 'ms-2' so Flexbox can handle the spacing naturally
                  return team === 'TEAM_A' ? `<span class="badge-team-a" style="font-size: 0.75rem;">A</span>` : `<span class="badge-team-b" style="font-size: 0.75rem;">B</span>`;
              };

              // 1. Inject the Badges into their new dedicated slots
              document.getElementById("badge-me").innerHTML = getBadge(state.myName);
              document.getElementById("badge-right").innerHTML = getBadge(rightPlayer);
              document.getElementById("badge-top").innerHTML = getBadge(topPlayer);
              document.getElementById("badge-left").innerHTML = getBadge(leftPlayer);

              // 2. Inject the Names cleanly
              document.getElementById("bottom-pod-name").innerText = state.myName + " (You)";
              document.getElementById("right-pod-name").innerText = rightPlayer;
              document.getElementById("top-pod-name").innerText = topPlayer;
              document.getElementById("left-pod-name").innerText = leftPlayer;
          }

          // 🌟 NEW: Update the Immutable Menu Rules
          const evenRuleSpan = document.getElementById("menu-rule-even");
          if (evenRuleSpan) {
              evenRuleSpan.innerHTML = state.isEvenDehla ? "<span class='text-success'>🟢 ON</span>" : "<span class='text-danger'>🔴 OFF</span>";
              document.getElementById("menu-rule-limit").innerText = state.maxRounds ? state.maxRounds : "Unlimited";
          }
          }

         // Clear glows
          document.getElementById("pod-me").classList.remove("active-turn");
          document.getElementById("pod-right").classList.remove("active-turn");
          document.getElementById("pod-top").classList.remove("active-turn");
          document.getElementById("pod-left").classList.remove("active-turn");
          document.getElementById("my-turn-glow").style.boxShadow = "none";

          // Apply glow (Only if the game is NOT paused)
          if (state.isPaused) {
              document.getElementById("bottom-pod-name").innerText = state.myName + " (Wait...)";
          } else {
              if (state.isMyTurn) {
                  document.getElementById("pod-me").classList.add("active-turn");
                 // document.getElementById("my-turn-glow").style.boxShadow = "0 0 30px rgba(241, 196, 15, 0.5) inset";
                  document.getElementById("bottom-pod-name").innerText = state.myName + " (Your Turn!)";
              } else if (state.currentTurnPlayerName === rightPlayer) { document.getElementById("pod-right").classList.add("active-turn");
              } else if (state.currentTurnPlayerName === topPlayer) { document.getElementById("pod-top").classList.add("active-turn");
              } else if (state.currentTurnPlayerName === leftPlayer) { document.getElementById("pod-left").classList.add("active-turn"); }
          }

          //Card snap and dehla capture sound logic starts here
          // 1. CARD SNAP TRIGGER

          const currentTableCardCount = state.currentTrickCards ? state.currentTrickCards.length : 0;
          if (currentTableCardCount > prevTableCardCount) {
              playSound("sfx-card-snap", true);
          }
          prevTableCardCount = currentTableCardCount;

          // 2. DEHLA CAPTURE TRIGGER (With Priority Check)
          const teamACapturedDehla = state.teamAScore > prevDehlaScoreA;
          const teamBCapturedDehla = state.teamBScore > prevDehlaScoreB;

          if (teamACapturedDehla || teamBCapturedDehla) {
              const isBowniEnding = (state.currentPhase === "ROUND_OVER" || state.currentPhase === "MATCH_OVER") && state.bowniTeam;
              if (!isBowniEnding) {
                  playSound("sfx-dehla-capture");
              }
          }

          prevDehlaScoreA = state.teamAScore;
          prevDehlaScoreB = state.teamBScore;
          //Card snap and dehla capture sound logic ends here


          // ==========================================
          // RENDER TABLE CARDS (Directionally Accurate Toss!)
          // ==========================================
          const tableDiv = document.getElementById("table-cards");
          tableDiv.innerHTML = "";

          if (state.currentTrickCards && state.trickPlayerNames) {
              state.currentTrickCards.forEach((cardStr, index) => {
                  const cardHTML = createCardHTML(cardStr, false, true);
                  const playedByName = state.trickPlayerNames[index];

                  let animationDir = 'bottom'; // Me
                  if (playedByName === rightPlayer) animationDir = 'right';
                  if (playedByName === topPlayer) animationDir = 'top';
                  if (playedByName === leftPlayer) animationDir = 'left';

                  tableDiv.innerHTML += `<div class="position-absolute" style="animation: toss-${animationDir} 0.4s ease-out forwards; z-index: ${index};">${cardHTML}</div>`;
              });
          }

       // --- RENDER MY HAND ---
          const handDiv = document.getElementById("my-hand");
          handDiv.innerHTML = "";

          // Prevent clicking during the 2.5 second pause!
         // Cards are ONLY clickable during MAIN_PLAY
          // 🌟 BUG FIX: Allow clicking during both the Trump Discovery phase AND Main Play!
          const canIPlay = state.isMyTurn && !state.isPaused && (state.currentPhase === "MAIN_PLAY" || state.currentPhase === "DISCOVERING_TRUMP" );
          // 🌟 RESET FLAGS IF A NEW ROUND STARTS
          if (!state.bowniTeam) {
              hasPlayedBowniCallThisRound = false;
          }
          if (state.currentPhase !== "ROUND_OVER" && state.currentPhase !== "MATCH_OVER") {
              hasPlayedVictorySoundThisRound = false;
          }
          // 🌟 NEW: Add these UI Toggles right here!
          document.getElementById("bowni-container").style.display = (state.currentPhase === "BOWNI_DECLARATION") ? "block" : "none";
          const badge = document.getElementById("bowni-badge");
          if (state.bowniTeam) {
              badge.style.display = "block";
              badge.innerText = `🔥 ${state.bowniTeam === 'TEAM_A' ? 'Team A' : 'Team B'} : Bowni!`;
                // 🌟 NEW: Apply the animated CSS class instead of inline styles
                badge.className = "hud-badge text-white m-0 " + (state.bowniTeam === 'TEAM_A' ? 'bowni-neon-a' : 'bowni-neon-b');
              // 🌟 PLAY CALL SOUND (Only once per round)
              if (!hasPlayedBowniCallThisRound && state.currentPhase === "MAIN_PLAY") {
                  playSound("sfx-bowni-call");
                  hasPlayedBowniCallThisRound = true;
              }
          } else {
              badge.style.display = "none";
          }
          // 🌟 End of new UI Toggles

          // ⚠️ THIS IS THE NEW PART: Sort the hand before looping!
          const sortedHand = sortHand(state.myHand);

          sortedHand.forEach(cardStr => {
              handDiv.innerHTML += createCardHTML(cardStr, canIPlay, false);
          });

          // Scoreboard & Victory Logic
          if (state.historyTeamA) {
              const mainTbody = document.getElementById("main-scoreboard-body");
              const victoryTbody = document.getElementById("victory-scoreboard-body");
              mainTbody.innerHTML = ""; victoryTbody.innerHTML = "";
              for (let i = 0; i < state.historyTeamA.length; i++) {
                  const rowHTML = `<tr><td>${i + 1}</td><td class="text-success">+${state.historyTeamA[i]}</td><td class="text-success">+${state.historyTeamB[i]}</td></tr>`;
                  mainTbody.innerHTML += rowHTML; victoryTbody.innerHTML += rowHTML;
              }
              document.getElementById("history-total-A").innerText = state.matchScoreA || 0;
              document.getElementById("history-total-B").innerText = state.matchScoreB || 0;
          }

          const victoryScreen = document.getElementById("victory-screen");
          if (state.currentPhase === "ROUND_OVER" || state.currentPhase === "MATCH_OVER") {
    // 1. UPDATE SCORES (Runs for both phases)
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

    // 2. EVALUATE THE ROUND & PLAY AUDIO (Runs for both phases!)
    let winnerText = "It's a Tie!";

    if (state.bowniTeam) {
        // --- 🔥 HIGH STAKES BOWNI OUTCOMES ---
        if (state.bowniTeam === 'TEAM_A') {
            if (state.teamAScore === 4){
                winnerText = "🔥 TEAM A BOWNI SUCCESSFUL! 🔥";
                if (!hasPlayedVictorySoundThisRound) playSound("sfx-bowni-success");
            } else {
               winnerText = "💥 BOWNI FAILED! TEAM B WINS! 💥";
               if (!hasPlayedVictorySoundThisRound) playSound("sfx-bowni-fail");
             }
        } else if (state.bowniTeam === 'TEAM_B') {
            if (state.teamBScore === 4) {
               winnerText = "🔥 TEAM B BOWNI SUCCESSFUL! 🔥";
               if (!hasPlayedVictorySoundThisRound) playSound("sfx-bowni-success");
             } else {
               winnerText = "💥 BOWNI FAILED! TEAM A WINS! 💥";
               if (!hasPlayedVictorySoundThisRound) playSound("sfx-bowni-fail");
            }
        }
    } else {
        // --- 🟢 NORMAL GAME OUTCOMES ---
        if (state.teamAScore > state.teamBScore) winnerText = "Team A Wins Round!";
        if (state.teamBScore > state.teamAScore) winnerText = "Team B Wins Round!";
        if (state.teamAScore === 4) winnerText = "🏆 TEAM A NATURAL KOT! 🏆";
        if (state.teamBScore === 4) winnerText = "🏆 TEAM B NATURAL KOT! 🏆";
    }

    document.getElementById("victory-title").innerText = winnerText;

    // 3. UI TOGGLES: MATCH OVER vs ROUND OVER
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

    // 4. SHOW THE MODAL
    victoryScreen.style.setProperty("display", "flex", "important");
} else {
              victoryScreen.style.setProperty("display", "none", "important");
          }
      };

      // --- ACTIONS ---
       // ==========================================
      // 🌟 NEW: ROUND LIMIT STEPPER LOGIC
      // ==========================================
      let currentLimit = 5;
      let stepperInterval = null;
      let stepTimeout = null;

      function toggleStepper() {
          const isChecked = document.getElementById("roundLimitToggle").checked;
          document.getElementById("stepper-ui").style.display = isChecked ? "flex" : "none";
      }

      function startStepper(dir) {
          step(dir); // Initial single click step
          // Add a 400ms delay before fast-increment starts (feels like a native mobile app)
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

          // Visual feedback for boundaries
          const btnDown = document.getElementById("step-down");
          const btnUp = document.getElementById("step-up");
          btnDown.classList.toggle("disabled", currentLimit <= 1);
          btnUp.classList.toggle("disabled", currentLimit >= 100);

          // Stop fast-increment if boundary is reached
          if (currentLimit <= 1 || currentLimit >= 100) stopStepper();
      }

      function createRoom() {
    const name = document.getElementById("player-name").value.trim();

    if (!name) {
        alert("Enter your name!");
        return;
    }

    // 🌟 NEW: Check if the user turned on the Even Dehla rule
    // (Make sure the checkbox in your HTML has the id="evenDehlaToggle")
    const isEvenDehla = document.getElementById("evenDehlaToggle").checked;
    const isLimitEnabled = document.getElementById("roundLimitToggle").checked;

    ws.send(JSON.stringify({
        action: "CREATE_ROOM",
        playerName: name,
        // 🌟 NEW: Pass the settings object exactly how Jackson expects it
        settings: {
            strictSweepEnabled: isEvenDehla,
            maxRounds: isLimitEnabled ? currentLimit : null
        }
    }));
}
      // --- SVG CARD PRELOADER ---
// This forces the browser to download all cards in the background immediately
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

      // Start downloading the second the file loads
      preloadCardImages();
      function toggleGameMenu() { const menu = document.getElementById("game-menu-modal"); menu.style.display = (menu.style.display === "none" || menu.style.display === "") ? "flex" : "none"; }
      function toggleScoreboard() { const screen = document.getElementById("scoreboard-screen"); document.getElementById("game-menu-modal").style.display = "none"; screen.style.display = (screen.style.display === "none" || screen.style.display === "") ? "flex" : "none"; }
      function toggleRulesScreen() { const screen = document.getElementById("rules-screen"); document.getElementById("game-menu-modal").style.display = "none"; screen.style.display = (screen.style.display === "none" || screen.style.display === "") ? "flex" : "none";  }
      function joinRoom() { const name = document.getElementById("player-name").value.trim(); const code = document.getElementById("join-code").value.trim().toUpperCase(); if (!name || !code) { alert("Enter Name and Code!"); return; } ws.send(JSON.stringify({ action: "JOIN_ROOM", playerName: name, roomCode: code })); }
      function leaveRoom() { if (ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify({ action: "LEAVE_ROOM" })); } localStorage.removeItem("dehlaToken"); setTimeout(() => { location.reload(); }, 100); }
      function finishGame() { if(confirm("End game?")) ws.send(JSON.stringify({ action: "FINISH_GAME" })); }
      function playAgain() { ws.send(JSON.stringify({ action: "PLAY_AGAIN" })); }
      function playCard(cardString) { const parts = cardString.split(" of "); ws.send(JSON.stringify({ action: "PLAY_CARD", suit: parts[1], rank: parts[0] })); }
      function callBowni() { ws.send(JSON.stringify({ action: "CALL_BOWNI" })); }
