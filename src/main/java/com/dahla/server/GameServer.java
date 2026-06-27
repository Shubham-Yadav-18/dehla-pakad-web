package com.dahla.server;

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
                    // 1. LOBBY SYSTEM: CREATE & JOIN
                    // ==========================================
                    if ("CREATE_ROOM".equals(action.action)) {
                        String newCode = generateRoomCode();

                        // 🌟 ARCHITECTURE UPDATE: Safely parse settings or use defaults
                        RoomSettings roomRules = action.settings != null ? action.settings : new RoomSettings();
                        GameRoom newRoom = new GameRoom(newCode, roomRules);

                        activeRooms.put(newCode, newRoom);

                        String token = generateToken();
                        Player host = new Player(token, action.playerName, Team.TEAM_A);

                        globalPlayers.put(token, host);
                        connectionToToken.put(ctx, token);
                        tokenToConnection.put(token, ctx);
                        newRoom.addPlayer(host);

                        broadcastToRoom(newRoom);
                    }
                    else if ("JOIN_ROOM".equals(action.action)) {
                        GameRoom targetRoom = activeRooms.get(action.roomCode);
                        if (targetRoom == null) {
                            ctx.send("{\"errorMessage\": \"Room not found!\"}");
                            return;
                        }
                        if (targetRoom.getPlayers().size() >= 4) {
                            ctx.send("{\"errorMessage\": \"Room is full!\"}");
                            return;
                        }

                        // Determine team (A, B, A, B)
                        Team assignedTeam = (targetRoom.getPlayers().size() % 2 == 0) ? Team.TEAM_A : Team.TEAM_B;
                        String token = generateToken();
                        Player joinedPlayer = new Player(token, action.playerName, assignedTeam);

                        globalPlayers.put(token, joinedPlayer);
                        connectionToToken.put(ctx, token);
                        tokenToConnection.put(token, ctx); // 🌟 Track the real connection
                        targetRoom.addPlayer(joinedPlayer);

                        if (targetRoom.getPlayers().size() == 4) {
                            targetRoom.startGame();
                        }
                        broadcastToRoom(targetRoom);
                    }
                    // ==========================================
                    // 2. RECONNECTION SYSTEM
                    // ==========================================
                    else if ("RECONNECT".equals(action.action)) {
                        Player returningPlayer = globalPlayers.get(action.playerToken);
                        if (returningPlayer != null) {

                            // 🌟 NEW FIX 2: Destroy the old ghost connection immediately!
                            WsContext oldCtx = tokenToConnection.get(action.playerToken);
                            if (oldCtx != null && oldCtx != ctx) {
                                connectionToToken.remove(oldCtx);
                            }

                            // Link their fresh browser connection as the only real one
                            tokenToConnection.put(action.playerToken, ctx);
                            connectionToToken.put(ctx, action.playerToken);

                            GameRoom theirRoom = findRoomForPlayer(returningPlayer);
                            if (theirRoom != null) {
                                // Cancel the doomsday clock if it exists!
                                ScheduledFuture<?> timer = reconnectTimers.remove(action.playerToken);
                                if (timer != null) {
                                    timer.cancel(false);
                                    theirRoom.isNetworkPaused = false; // Unfreeze the table!
                                    System.out.println(returningPlayer.getName() + " reconnected successfully!");
                                }
                                broadcastToRoom(theirRoom);
                                return;
                            }
                        }
                        ctx.send("{\"errorMessage\": \"Session expired. Please rejoin.\"}");
                    }
                    // ==========================================
                    // 3. IN-GAME ACTIONS (Play Card, Play Again, Finish)
                    // ==========================================
                    else {
                        String token = connectionToToken.get(ctx);
                        if (token == null) return; // Unregistered user trying to play

                        Player player = globalPlayers.get(token);
                        GameRoom room = findRoomForPlayer(player);
                        if (room == null) return;

                        if ("PLAY_CARD".equals(action.action)) {
                            Card cardToPlay = player.getHand().stream()
                                    .filter(c -> c.getSuit().name().equals(action.suit) && c.getRank().name().equals(action.rank))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException("Card not found!"));

                            room.playCard(player, cardToPlay);
                            broadcastToRoom(room); // Instantly broadcast so everyone sees the 4th card land

                            if (room.isTrickPaused) {
                                System.out.println("[TIMER] Trick finished. Starting 2.5s animation timer for Room: " + room.getRoomId());

                                // 🌟 NEW FIX 3: Self-Polling Runnable to prevent Timer Collisions
                                scheduler.schedule(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            // 🛡️ THE ARMOR: If someone disconnected, DO NOT sweep the trick! Wait 1s and check again.
                                            if (room.isNetworkPaused) {
                                                System.out.println("[TIMER] Room " + room.getRoomId() + " is frozen. Trick resolution paused. Waiting 1s...");
                                                scheduler.schedule(this, 1000, TimeUnit.MILLISECONDS);
                                                return;
                                            }

                                            System.out.println("[TIMER] 2.5s passed. Resolving trick for Room: " + room.getRoomId());
                                            room.finalizeTrick();
                                            broadcastToRoom(room);

                                            // If we just entered Bowni Phase, start the 10s clock!
                                            if (room.getCurrentPhase() == GamePhase.BOWNI_DECLARATION && !room.isBowniTimerStarted) {
                                                System.out.println("[TIMER] Bowni Phase hit! Starting 10s countdown for Room: " + room.getRoomId());
                                                room.isBowniTimerStarted = true;

                                                // 🌟 NEW FIX 4: Self-Polling Runnable for the Bowni Timer
                                                scheduler.schedule(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            // 🛡️ THE ARMOR: Pause the countdown if the room is frozen
                                                            if (room.isNetworkPaused) {
                                                                System.out.println("[TIMER] Room " + room.getRoomId() + " is frozen. Bowni countdown paused. Waiting 1s...");
                                                                scheduler.schedule(this, 1000, TimeUnit.MILLISECONDS);
                                                                return;
                                                            }

                                                            // If nobody clicked it after 10s, silently start the game
                                                            if (room.getCurrentPhase() == GamePhase.BOWNI_DECLARATION) {
                                                                System.out.println("[TIMER] 10s passed. Nobody called Bowni. Auto-starting Main Play.");
                                                                room.setCurrentPhase(GamePhase.MAIN_PLAY);
                                                                broadcastToRoom(room);
                                                            }
                                                        } catch (Exception e) {
                                                            System.err.println("[CRITICAL ERROR] Bowni Timer Crashed: " + e.getMessage());
                                                            e.printStackTrace();
                                                        }
                                                    }
                                                }, 10, TimeUnit.SECONDS);
                                            }
                                        } catch (Exception e) {
                                            System.err.println("[CRITICAL ERROR] Trick Resolution Timer Crashed! " + e.getMessage());
                                            e.printStackTrace();
                                            // Emergency unfreeze so players can keep playing
                                            room.isTrickPaused = false;
                                            broadcastToRoom(room);
                                        }
                                    }
                                }, 2500, TimeUnit.MILLISECONDS);
                            }
                        }
                        else if ("CALL_BOWNI".equals(action.action)) {
                            if (room.getCurrentPhase() == GamePhase.BOWNI_DECLARATION) {
                                room.setTeamWhoCalledKot(player.getTeam());
                                room.setCurrentPhase(GamePhase.MAIN_PLAY);
                                broadcastToRoom(room);
                            }
                        }
                        else if ("PLAY_AGAIN".equals(action.action)) {
                            if (room.getPlayers().size() < 4) {
                                ctx.send("{\"errorMessage\": \"Cannot start round. A player is missing! Please rejoin.\"}");
                                return;
                            }
                            room.playAnotherRound();
                            broadcastToRoom(room);
                        }
                        else if ("FINISH_GAME".equals(action.action)) {
                            dissolveRoom(room, "The match has ended. Please rejoin to play a new game.");
                        }
                        else if ("LEAVE_ROOM".equals(action.action)) {
                            if (room.getCurrentPhase() == GamePhase.WAITING_FOR_PLAYERS) {
                                room.removePlayer(player);
                                globalPlayers.remove(token);
                                connectionToToken.remove(ctx);
                                tokenToConnection.remove(token); // Cleanup

                                if (room.getPlayers().isEmpty()) {
                                    activeRooms.remove(room.getRoomId());
                                    System.out.println("Room " + room.getRoomId() + " closed (empty).");
                                } else {
                                    broadcastToRoom(room);
                                }
                            } else {
                                dissolveRoom(room, player.getName() + " left the table. The room has been dissolved. Please rejoin.");
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Server Error: " + e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                String token = connectionToToken.remove(ctx);
                System.out.println("[NETWORK] A WebSocket connection dropped.");

                if (token != null) {
                    // 🌟 NEW FIX 5: Is this a Ghost connection dropping?
                    WsContext activeCtx = tokenToConnection.get(token);
                    if (activeCtx != null && activeCtx != ctx) {
                        System.out.println("[NETWORK] Ghost connection safely ignored. Player is still active in the game.");
                        return; // 🛡️ ABORT! Do not pause the game.
                    }

                    // Not a ghost. Proceed with normal disconnect.
                    tokenToConnection.remove(token);
                    Player player = globalPlayers.get(token);

                    if (player != null) {
                        GameRoom room = findRoomForPlayer(player);

                        if (room != null) {
                            if (room.getCurrentPhase() == GamePhase.WAITING_FOR_PLAYERS) {
                                room.removePlayer(player);
                                globalPlayers.remove(token);
                                System.out.println(player.getName() + " left the lobby.");

                                if (room.getPlayers().isEmpty()) activeRooms.remove(room.getRoomId());
                                else broadcastToRoom(room);
                            }
                            else if (room.getCurrentPhase() == GamePhase.MATCH_OVER) {
                                globalPlayers.remove(token);
                            }
                            else {
                                room.isNetworkPaused = true;
                                System.out.println("[ROOM " + room.getRoomId() + "] " + player.getName() + " disconnected! Freezing table. Starting 60s timer...");
                                broadcastToRoom(room);

                                ScheduledFuture<?> timer = scheduler.schedule(() -> {
                                    try {
                                        System.out.println("[TIMER] 60s expired. Dissolving room " + room.getRoomId());
                                        dissolveRoom(room, player.getName() + " lost connection. The room has been dissolved. Please rejoin.");
                                    } catch (Exception e) {
                                        System.err.println("[CRITICAL ERROR] Doomsday Timer Crashed: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }, 60, TimeUnit.SECONDS);

                                reconnectTimers.put(token, timer);
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
            update.currentTurnPlayerName = room.getCurrentTurnPlayer() != null ? room.getCurrentTurnPlayer().getName() : "Waiting...";
            update.isPaused = (room.isTrickPaused || room.isNetworkPaused);
            update.bowniTeam = room.getTeamWhoCalledKot() != null ? room.getTeamWhoCalledKot().name() : null;
            update.isMyTurn = (room.getCurrentTurnPlayer() != null && room.getCurrentTurnPlayer().equals(player));

            update.seatingOrder = room.getPlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());

            update.currentTrickCards = room.getCurrentTrick().getTableCards().values().stream()
                    .map(Card::toString)
                    .collect(Collectors.toList());

            update.trickPlayerNames = room.getCurrentTrick().getTableCards().keySet().stream()
                    .map(Player::getName)
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
        activeRooms.remove(room.getRoomId());

        for (Map.Entry<WsContext, String> entry : connectionToToken.entrySet()) {
            WsContext connection = entry.getKey();
            String token = entry.getValue();
            Player player = globalPlayers.get(token);

            if (player != null && room.getPlayers().contains(player)) {
                try {
                    if (connection.session.isOpen()) {
                        connection.send(jsonMapper.writeValueAsString(Map.of("errorMessage", reasonMessage)));
                    }
                } catch (Exception e) {
                    System.err.println("Failed to send dissolve message.");
                }
                globalPlayers.remove(token);
                tokenToConnection.remove(token); // Cleanup
            }
        }
        System.out.println("Room " + room.getRoomId() + " dissolved: " + reasonMessage);
    }
}