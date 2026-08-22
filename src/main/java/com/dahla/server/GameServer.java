package com.dahla.server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dahla.dto.GameStateUpdate;
import com.dahla.dto.PlayerAction;
import com.dahla.dto.RoomSettings;
import com.dahla.engine.GamePhase;
import com.dahla.engine.GameRoom;
import com.dahla.model.Card;
import com.dahla.model.Player;
import com.dahla.model.Team;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

public class GameServer {

    private static final Logger log = LoggerFactory.getLogger(GameServer.class);

    // 1. Tracks all active rooms by their 4-digit code (e.g., "A7X2" -> GameRoom)
    private static final Map<String, GameRoom> activeRooms = new ConcurrentHashMap<>();

    // 2. Tracks every player on the server by their Secret Token (Token -> Player)
    private static final Map<String, Player> globalPlayers = new ConcurrentHashMap<>();

    // 3. Tracks which Token belongs to which live WebSocket connection
    private static final Map<WsContext, String> connectionToToken = new ConcurrentHashMap<>();

    // 🌟 NEW FIX 1: Reverse tracker to detect and instantly ignore Ghost Connections
    private static final Map<String, WsContext> tokenToConnection = new ConcurrentHashMap<>();

    private static final ObjectMapper jsonMapper = new ObjectMapper();

    // 4. A background timer that won't freeze your web server threads
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // 5. Tracks the 60-second reconnect countdowns for disconnected players
    private static final Map<String, ScheduledFuture<?>> reconnectTimers = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("Starting Dehla Pakad Server on port 7070...");

        // The cloud provider will inject a "PORT" variable. If it's missing, we default to 7070 for local testing.
        String portStr = System.getenv("PORT");
        int port = (portStr != null) ? Integer.parseInt(portStr) : 7070;

        Javalin app = Javalin.create().start(port);

        app.ws("/game", ws -> {

            ws.onConnect(ctx -> {
                ctx.session.setIdleTimeout(Duration.ofMinutes(30));
                // We no longer assign them to a room immediately. We wait for them to click Create or Join!
            });

            ws.onMessage(ctx -> {
                try {
                    PlayerAction action = jsonMapper.readValue(ctx.message(), PlayerAction.class);

                    // ==========================================
                    // ==========================================
                    // 1. LOBBY SYSTEM: CREATE & JOIN
                    // ==========================================
                    if ("CREATE_ROOM".equals(action.action)) {
                        // 🛡️ IDEMPOTENCY GUARD: Ignore duplicate requests
                        if (connectionToToken.containsKey(ctx)) {
                            log.warn("roomId={} event=DUPLICATE_CREATE_IGNORED connectionId={}", "PENDING", getLogConnectionId(ctx));
                            return;
                        }

                        log.debug("roomId={} event=CREATE_ROOM_REQUEST connectionId={}", "PENDING", getLogConnectionId(ctx));
                        RoomSettings roomRules = action.settings != null ? action.settings : new RoomSettings();

                        String newCode;
                        GameRoom newRoom;
                        int attempts = 0;

                        // 🛡️ ATOMIC CREATE: Ensure room code is 100% unique
                        while (true) {
                            newCode = generateRoomCode();
                            newRoom = new GameRoom(newCode, roomRules);
                            // putIfAbsent ensures thread-safety without locking the whole map
                            if (activeRooms.putIfAbsent(newCode, newRoom) == null) break;
                            if (++attempts > 100) {
                                ctx.send("{\"errorMessage\": \"Server at capacity. Could not generate unique code.\"}");
                                return;
                            }
                        }

                        log.info("roomId={} event=ROOM_CREATED", newRoom.getRoomId());

                        String token = generateToken();
                        String id = generateToken();
                        Player host = new Player(id, action.playerName, Team.TEAM_A);
                        log.debug("roomId={} event=PLAYER_CREATED playerId={} name={} team={}", newRoom.getRoomId(), host.getId(), host.getName(), host.getTeam());

                        globalPlayers.put(token, host);
                        connectionToToken.put(ctx, token);
                        tokenToConnection.put(token, ctx);

                        newRoom.addPlayer(host);
                        log.info("roomId={} event=PLAYER_JOIN playerId={} name={} team={}", newRoom.getRoomId(), host.getId(), host.getName(), host.getTeam());
                        logRoomState(newRoom, "AFTER_PLAYER_JOIN");
                        log.debug("roomId={} event=CONNECTION_MAPPED playerId={} connectionId={}", newRoom.getRoomId(), host.getId(), getLogConnectionId(ctx));

                        broadcastToRoom(newRoom);
                    }
                    else if ("JOIN_ROOM".equals(action.action)) {
                        // 🛡️ IDEMPOTENCY GUARD: Ignore duplicate join clicks
                        if (connectionToToken.containsKey(ctx)) {
                            log.warn("roomId={} event=DUPLICATE_JOIN_IGNORED connectionId={}", action.roomCode, getLogConnectionId(ctx));
                            return;
                        }

                        log.debug("roomId={} event=JOIN_ROOM_REQUEST connectionId={}", action.roomCode, getLogConnectionId(ctx));
                        GameRoom targetRoom = activeRooms.get(action.roomCode);
                        if (targetRoom == null) {
                            ctx.send("{\"errorMessage\": \"Room not found!\"}");
                            return;
                        }

                        // 🛡️ THE LOCK: Check size, check duplicates, and add player atomically
                        synchronized (targetRoom) {
                            if (targetRoom.getPlayers().size() >= 4) {
                                ctx.send("{\"errorMessage\": \"Room is full!\"}");
                                return;
                            }

                            // 🛡️ IDEMPOTENCY GUARD 2: Prevent the same name from joining twice
                            boolean nameExists = targetRoom.getPlayers().stream()
                                    .anyMatch(p -> p.getName().equalsIgnoreCase(action.playerName));
                            if (nameExists) {
                                log.warn("roomId={} event=DUPLICATE_PLAYER_NAME_REJECTED name={}", targetRoom.getRoomId(), action.playerName);
                                ctx.send("{\"errorMessage\": \"A player with this name is already in the room!\"}");
                                return;
                            }
                            // Determine team (A, B, A, B)
                            Team assignedTeam = (targetRoom.getPlayers().size() % 2 == 0) ? Team.TEAM_A : Team.TEAM_B;
                            String token = generateToken();
                            String id = generateToken();
                            Player joinedPlayer = new Player(id, action.playerName, assignedTeam);

                            log.debug("roomId={} event=PLAYER_CREATED playerId={} name={} team={}", targetRoom.getRoomId(), joinedPlayer.getId(), joinedPlayer.getName(), joinedPlayer.getTeam());

                            globalPlayers.put(token, joinedPlayer);
                            connectionToToken.put(ctx, token);
                            tokenToConnection.put(token, ctx);

                            targetRoom.addPlayer(joinedPlayer);

                            log.info("roomId={} event=PLAYER_JOIN playerId={} name={} team={}", targetRoom.getRoomId(), joinedPlayer.getId(), joinedPlayer.getName(), joinedPlayer.getTeam());
                            logRoomState(targetRoom, "AFTER_PLAYER_JOIN");
                            log.debug("roomId={} event=CONNECTION_MAPPED playerId={} connectionId={}", targetRoom.getRoomId(), joinedPlayer.getId(), getLogConnectionId(ctx));

                            if (targetRoom.getPlayers().size() == 4) {
                                targetRoom.startGame();
                            }
                        } // 🔓 LOCK RELEASED

                        broadcastToRoom(targetRoom);
                    }
                    // ==========================================
                    // 2. RECONNECTION SYSTEM
                    // ==========================================
                    else if ("RECONNECT".equals(action.action)) {
                        log.debug("roomId={} event=RECONNECT_REQUEST connectionId={}", "UNKNOWN", getLogConnectionId(ctx));
                        Player returningPlayer = globalPlayers.get(action.playerToken);
                        if (returningPlayer != null) {

                            // 🌟 NEW FIX 2: Destroy the old ghost connection immediately!
                            WsContext oldCtx = tokenToConnection.get(action.playerToken);
                            if (oldCtx != null && oldCtx != ctx) {
                                connectionToToken.remove(oldCtx);
                                log.debug("roomId={} event=OLD_CONNECTION_REPLACED playerId={} oldConnectionId={} newConnectionId={}", "UNKNOWN", returningPlayer.getId(), getLogConnectionId(oldCtx), getLogConnectionId(ctx));
                            }

                            // Link their fresh browser connection as the only real one
                            tokenToConnection.put(action.playerToken, ctx);
                            connectionToToken.put(ctx, action.playerToken);
                            log.debug("roomId={} event=CONNECTION_REASSIGNED playerId={} connectionId={}", "UNKNOWN", returningPlayer.getId(), getLogConnectionId(ctx));

                            GameRoom theirRoom = findRoomForPlayer(returningPlayer);
                            if (theirRoom != null) {
                                // 🛡️ THE LOCK: Safely disable the disconnect timer
                                synchronized (theirRoom) {
                                    log.info("roomId={} event=PLAYER_RECONNECTED playerId={} name={} connectionId={}", theirRoom.getRoomId(), returningPlayer.getId(), returningPlayer.getName(), getLogConnectionId(ctx));
                                    // Cancel the doomsday clock if it exists!
                                    ScheduledFuture<?> timer = reconnectTimers.remove(action.playerToken);
                                    if (timer != null) {

                                        timer.cancel(false);
                                        log.info("roomId={} event=RECONNECT_TIMER_CANCELLED playerId={} reason=PLAYER_RECONNECTED", theirRoom.getRoomId(), returningPlayer.getId());
                                        log.info("roomId={} event=RECONNECT_SUCCESS playerId={} name={} connectionId={}", theirRoom.getRoomId(), returningPlayer.getId(), returningPlayer.getName(), getLogConnectionId(ctx));
                                        System.out.println(returningPlayer.getName() + " reconnected successfully!");
                                    }
                                    theirRoom.isNetworkPaused = false; // Unfreeze the table!
                                }
                                broadcastToRoom(theirRoom);
                                return;
                            }
                        }
                        ctx.send("{\"errorMessage\": \"Session expired. Please rejoin.\"}");
                        log.warn("roomId={} event=RECONNECT_FAILED connectionId={} reason=SESSION_NOT_FOUND", "UNKNOWN", getLogConnectionId(ctx));
                    }
                    // ==========================================
                    // 3. IN-GAME ACTIONS (Play Card, Play Again, Finish, Leave)
                    // ==========================================
                    else {
                        String token = connectionToToken.get(ctx);
                        if (token == null) return; // Unregistered user trying to play

                        Player player = globalPlayers.get(token);
                        GameRoom room = findRoomForPlayer(player);
                        if (room == null) return;

                        boolean sendError = false;
                        String errorMessage = "";
                        boolean shouldBroadcast = false;

                        // 🛡️ THE MASTER LOCK: All in-game interactions must serialize here
                        synchronized (room) {
                            if ("PLAY_CARD".equals(action.action)) {
                                try {
                                    Card cardToPlay = player.getHand().stream()
                                            .filter(c -> c.getSuit().name().equals(action.suit) && c.getRank().name().equals(action.rank))
                                            .findFirst()
                                            .orElseThrow(() -> new IllegalArgumentException("Card not found!"));

                                    room.playCard(player, cardToPlay);
                                    shouldBroadcast = true; // State changed, mark for broadcast

                                    // 🛡️ THE LOCK: Only spawn the timer if we aren't already resolving!
                                    if (room.isTrickPaused && !room.isTrickResolving) {
                                        room.isTrickResolving = true;
                                        System.out.println("[TIMER] Trick finished. Starting 2.5s animation timer for Room: " + room.getRoomId());

                                        ScheduledFuture<?> trickTask = scheduler.schedule(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    // 🛡️ THE TIMER LOCK: Thread re-enters lock to safely modify game state
                                                    synchronized (room) {
                                                        if (room.isNetworkPaused) {
                                                            System.out.println("[TIMER] Room " + room.getRoomId() + " is frozen. Trick resolution paused. Waiting 1s...");
                                                            room.setActiveTrickTimer(scheduler.schedule(this, 1000, TimeUnit.MILLISECONDS));
                                                            return;
                                                        }

                                                        System.out.println("[TIMER] 2.5s passed. Resolving trick for Room: " + room.getRoomId());
                                                        room.finalizeTrick();
                                                        room.isTrickResolving = false; // Unlock the door

                                                        // Check Bowni Phase
                                                        if (room.getCurrentPhase() == GamePhase.BOWNI_DECLARATION && !room.isBowniTimerStarted) {
                                                            System.out.println("[TIMER] Bowni Phase hit! Starting 10s countdown for Room: " + room.getRoomId());
                                                            room.isBowniTimerStarted = true;

                                                            ScheduledFuture<?> bowniTask = scheduler.schedule(new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    try {
                                                                        synchronized (room) {
                                                                            if (room.isNetworkPaused) {
                                                                                System.out.println("[TIMER] Room " + room.getRoomId() + " is frozen. Bowni countdown paused. Waiting 1s...");
                                                                                scheduler.schedule(this, 1000, TimeUnit.MILLISECONDS);
                                                                                return;
                                                                            }

                                                                            if (room.getCurrentPhase() == GamePhase.BOWNI_DECLARATION) {
                                                                                System.out.println("[TIMER] 10s passed. Nobody called Bowni. Auto-starting Main Play.");
                                                                                room.setCurrentPhase(GamePhase.MAIN_PLAY);
                                                                            }
                                                                        }
                                                                        broadcastToRoom(room); // Broadcast outside inner lock
                                                                    } catch (Exception e) {
                                                                        System.err.println("[CRITICAL ERROR] Bowni Timer Crashed: " + e.getMessage());
                                                                        e.printStackTrace();
                                                                    }
                                                                }
                                                            }, 10, TimeUnit.SECONDS);
                                                            room.setActiveBowniTimer(bowniTask);
                                                        }
                                                    } // End inner synchronized block
                                                    broadcastToRoom(room); // Broadcast outside inner lock
                                                } catch (Exception e) {
                                                    System.err.println("[CRITICAL ERROR] Trick Resolution Timer Crashed! " + e.getMessage());
                                                    e.printStackTrace();
                                                    synchronized (room) {
                                                        room.isTrickPaused = false;
                                                        room.isTrickResolving = false;
                                                    }
                                                    broadcastToRoom(room);
                                                }
                                            }
                                        }, 2500, TimeUnit.MILLISECONDS);
                                        room.setActiveTrickTimer(trickTask);
                                    }
                                } catch (IllegalArgumentException | IllegalStateException e) {
                                    sendError = true;
                                    errorMessage = e.getMessage().replace("\"", "\\\"");
                                }
                            }
                            else if ("CALL_BOWNI".equals(action.action)) {
                                if (room.getCurrentPhase() == GamePhase.BOWNI_DECLARATION) {
                                    room.setTeamWhoCalledKot(player.getTeam());
                                    room.setCurrentPhase(GamePhase.MAIN_PLAY);
                                    shouldBroadcast = true;
                                }
                            }
                            else if ("PLAY_AGAIN".equals(action.action)) {
                                if (room.getPlayers().size() < 4) {
                                    sendError = true;
                                    errorMessage = "Cannot start round. A player is missing! Please rejoin.";
                                } else {
                                    room.playAnotherRound();
                                    shouldBroadcast = true;
                                }
                            }
                            else if ("FINISH_GAME".equals(action.action)) {
                                // dissolveRoom handles its own internal locking, broadcasts, and cleanup.
                                dissolveRoom(room, "The match has ended. Please rejoin to play a new game.");
                                return;
                            }
                            else if ("LEAVE_ROOM".equals(action.action)) {
                                log.info("roomId={} event=PLAYER_LEAVE_REQUEST playerId={} name={} phase={}", room.getRoomId(), player.getId(), player.getName(), room.getCurrentPhase());
                                if (room.getCurrentPhase() == GamePhase.WAITING_FOR_PLAYERS) {
                                    room.removePlayer(player);
                                    log.info("roomId={} event=PLAYER_REMOVED playerId={} name={} reason=PLAYER_LEFT_LOBBY", room.getRoomId(), player.getId(), player.getName());

                                    globalPlayers.remove(token);
                                    connectionToToken.remove(ctx);
                                    tokenToConnection.remove(token); // Cleanup
                                    log.debug("roomId={} event=PLAYER_SESSION_CLEANED playerId={} connectionId={}", room.getRoomId(), player.getId(), getLogConnectionId(ctx));

                                    if (room.getPlayers().isEmpty()) {
                                        activeRooms.remove(room.getRoomId());
                                        log.info("roomId={} event=ROOM_CLOSED reason=EMPTY_AFTER_PLAYER_LEAVE", room.getRoomId());
                                        return; // Room is gone, do not broadcast
                                    } else {
                                        logRoomState(room, "AFTER_PLAYER_LEAVE");
                                        shouldBroadcast = true;
                                    }
                                } else {
                                    log.info("roomId={} event=PLAYER_LEAVE_DURING_GAME playerId={} name={} action=ROOM_DISSOLVE", room.getRoomId(), player.getId(), player.getName());
                                    dissolveRoom(room, player.getName() + " left the table. The room has been dissolved. Please rejoin.");
                                    return; // Room is gone, do not broadcast
                                }
                            }
                        } // 🔓 MASTER LOCK RELEASED

                        // Send errors OR broadcast new state safely outside the lock
                        if (sendError) {
                            ctx.send(String.format("{\"errorMessage\": \"%s\", \"errorType\": \"RULE_VIOLATION\"}", errorMessage));
                        } else if (shouldBroadcast) {
                            broadcastToRoom(room);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Server Error: " + e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                log.debug("roomId={} event=WS_CLOSE connectionId={}", "UNKNOWN", getLogConnectionId(ctx));
                String token = connectionToToken.remove(ctx);
                log.debug("roomId={} event=WS_CLOSE_TOKEN_RESOLVED connectionId={} tokenKnown={}", "UNKNOWN", getLogConnectionId(ctx), token != null);
                System.out.println("[NETWORK] A WebSocket connection dropped.");

                if (token != null) {
                    // 🌟 NEW FIX 5: Is this a Ghost connection dropping?
                    WsContext activeCtx = tokenToConnection.get(token);
                    if (activeCtx != null && activeCtx != ctx) {
                        log.debug("roomId={} event=GHOST_CONNECTION_CLOSE_IGNORED connectionId={} activeConnectionId={}", "UNKNOWN", getLogConnectionId(ctx), getLogConnectionId(activeCtx));
                        System.out.println("[NETWORK] Ghost connection safely ignored. Player is still active in the game.");
                        return; // 🛡️ ABORT! Do not pause the game.
                    }

                    // Not a ghost. Proceed with normal disconnect.
                    tokenToConnection.remove(token);
                    Player player = globalPlayers.get(token);

                    if (player != null) {
                        GameRoom room = findRoomForPlayer(player);

                        if (room != null) {
                            // 🛡️ THE LOCK: Handle dropping player safely
                            synchronized (room) {
                            log.info("roomId={} event=PLAYER_DISCONNECTED playerId={} name={} connectionId={}", room.getRoomId(), player.getId(), player.getName(), getLogConnectionId(ctx));
                            if (room.getCurrentPhase() == GamePhase.WAITING_FOR_PLAYERS) {
                                room.removePlayer(player);
                                globalPlayers.remove(token);
                                System.out.println(player.getName() + " left the lobby.");

                                if (room.getPlayers().isEmpty()) {
                                    activeRooms.remove(room.getRoomId());
                                    return;
                                }
                            }
                            else if (room.getCurrentPhase() == GamePhase.MATCH_OVER) {
                                globalPlayers.remove(token);
                            }
                            else {
                                room.isNetworkPaused = true;
                                log.info("roomId={} event=NETWORK_PAUSED playerId={} reason=PLAYER_DISCONNECTED", room.getRoomId(), player.getId());
                                System.out.println("[ROOM " + room.getRoomId() + "] " + player.getName() + " disconnected! Freezing table. Starting 60s timer...");

                                log.info("roomId={} event=RECONNECT_TIMER_STARTED playerId={} timeoutSeconds=60", room.getRoomId(), player.getId());
                                ScheduledFuture<?> timer = scheduler.schedule(() -> {
                                    synchronized (room) { // Ensure dissolve is locked
                                        try {
                                            log.info("roomId={} event=RECONNECT_TIMER_EXPIRED playerId={}", room.getRoomId(), player.getId());
                                            log.info("roomId={} event=ROOM_DISSOLVE reason=RECONNECT_TIMEOUT", room.getRoomId());
                                            System.out.println("[TIMER] 60s expired. Dissolving room " + room.getRoomId());
                                            dissolveRoom(room, player.getName() + " lost connection. The room has been dissolved. Please rejoin.");
                                        } catch (Exception e) {
                                            System.err.println("[CRITICAL ERROR] Doomsday Timer Crashed: " + e.getMessage());
                                            e.printStackTrace();
                                        }
                                    }
                                }, 60, TimeUnit.SECONDS);

                                reconnectTimers.put(token, timer);
                            }
                            } // 🔓 LOCK RELEASED
                            // Only broadcast if the room wasn't destroyed
                            if (activeRooms.containsKey(room.getRoomId())) {
                                broadcastToRoom(room);
                            }
                        }
                    }
                }
            });
        });
    }

    private static void broadcastToRoom(GameRoom room) {
        for (Map.Entry<WsContext, String> entry : connectionToToken.entrySet()) {
            WsContext connection = entry.getKey();
            String token = entry.getValue();
            Player player = globalPlayers.get(token);

            if (player == null || !room.getPlayers().contains(player)) {
                continue;
            }

            GameStateUpdate update = new GameStateUpdate();

            update.roomCode = room.getRoomId();
            update.myToken = token;
            update.errorMessage = null;

            update.currentPhase = room.getCurrentPhase().name();
            update.trumpSuit = room.getTrumpSuit() != null ? room.getTrumpSuit().name() : "NOT YET DISCOVERED";
            update.myName = player.getName();
            update.myPlayerId = player.getId();
            update.currentTurnPlayerName = room.getCurrentTurnPlayer() != null ? room.getCurrentTurnPlayer().getName() : "Waiting...";
            update.currentTurnPlayerId =
                    room.getCurrentTurnPlayer() != null
                            ? room.getCurrentTurnPlayer().getId()
                            : null;
            update.isPaused = (room.isTrickPaused || room.isNetworkPaused);
            update.bowniTeam = room.getTeamWhoCalledKot() != null ? room.getTeamWhoCalledKot().name() : null;
            update.isMyTurn = (room.getCurrentTurnPlayer() != null && room.getCurrentTurnPlayer().equals(player));

            update.seatingOrder = room.getPlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            update.seatingPlayerIds = room.getPlayers().stream()
                    .map(Player::getId)
                    .collect(Collectors.toList());

            update.currentTrickCards = room.getCurrentTrick().getTableCards().values().stream()
                    .map(Card::toString)
                    .collect(Collectors.toList());

            update.trickPlayerNames = room.getCurrentTrick().getTableCards().keySet().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            update.trickPlayerIds = room.getCurrentTrick()
                    .getTableCards()
                    .keySet()
                    .stream()
                    .map(Player::getId)
                    .collect(Collectors.toList());
            update.accumulatedPileSize = room.getTableAccumulator().size();

            update.myHand = player.getHand().stream()
                    .map(Card::toString)
                    .collect(Collectors.toList());

            update.teamAScore = room.getTeamADehlasCount();
            update.teamBScore = room.getTeamBDehlasCount();
            update.matchScoreA = room.getMatchPointsTeamA();
            update.matchScoreB = room.getMatchPointsTeamB();
            update.historyTeamA = room.getHistoryTeamA();
            update.historyTeamB = room.getHistoryTeamB();

            // 🌟 NEW: UI Display Data
            update.isEvenDehla = room.getRules().strictSweepEnabled;
            update.maxRounds = room.getRules().maxRounds;

            update.playerTeams = room.getPlayers().stream()
                    .collect(Collectors.toMap(
                            Player::getName,
                            p -> p.getTeam().name(),
                            (existing, ignored) -> existing
                    ));
            update.playerTeamsById = room.getPlayers().stream()
                    .collect(Collectors.toMap(
                            Player::getId,
                            p -> p.getTeam().name()
                    ));

            try {
                if (connection.session.isOpen()) {
                    connection.send(jsonMapper.writeValueAsString(update));
                }
            } catch (Exception e) {
                System.err.println("Failed to send state to a player.");
            }
        }
    }

    private static String generateRoomCode() {
        return String.format("%04d", new java.util.Random().nextInt(10000));
    }

    private static String generateToken() {
        return java.util.UUID.randomUUID().toString();
    }

    private static GameRoom findRoomForPlayer(Player player) {
        for (GameRoom room : activeRooms.values()) {
            if (room.getPlayers().contains(player)) return room;
        }
        return null;
    }

    private static void dissolveRoom(GameRoom room, String reasonMessage) {
        if (room == null) return;

        // 🛡️ THE LOCK: Stop timers and destroy room state
        synchronized (room) {
            room.cancelAllTimers();
            activeRooms.remove(room.getRoomId());

            for (Player player : room.getPlayers()) {
                // Find token and clean connections safely
                for (Map.Entry<String, Player> entry : globalPlayers.entrySet()) {
                    if (entry.getValue().getId().equals(player.getId())) {
                        String token = entry.getKey();
                        WsContext connection = tokenToConnection.remove(token);

                        if (connection != null) {
                            connectionToToken.remove(connection);
                            if (connection.session.isOpen()) {
                                try {
                                    connection.send(jsonMapper.writeValueAsString(Map.of("errorMessage", reasonMessage)));
                                } catch (Exception e) {
                                    System.err.println("Failed to send dissolve message.");
                                }
                            }
                        }
                        globalPlayers.remove(token);
                        break;
                    }
                }
            }
        }
        System.out.println("Room " + room.getRoomId() + " dissolved: " + reasonMessage);
    }

    // Methods for loggers
    private static String getLogConnectionId(WsContext ctx) {
        return Integer.toHexString(System.identityHashCode(ctx));
    }

    private static void logRoomState(GameRoom room, String event) {
        if (room == null) {
            log.warn("roomId={} event={} room=null", "UNKNOWN", event);
            return;
        }

        String players = room.getPlayers().stream().map(player -> "playerId=" + player.getId() + ",name=" + player.getName() + ",team=" + player.getTeam()).collect(Collectors.joining(" | "));
        log.debug("roomId={} event={} playerCount={} players=[{}]", room.getRoomId(), event, room.getPlayers().size(), players);
    }

    private static void logRoomInvariantViolation(GameRoom room, String event, String details) {
        String roomId = room != null ? room.getRoomId() : "UNKNOWN";
        log.warn("roomId={} event={} invariantViolation={}", roomId, event, details);
    }
}