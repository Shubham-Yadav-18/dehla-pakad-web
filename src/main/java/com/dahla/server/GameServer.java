package com.dahla.server;

import com.dahla.dto.GameStateUpdate;
import com.dahla.dto.PlayerAction;
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

public class GameServer {

    // 1. Tracks all active rooms by their 4-digit code (e.g., "A7X2" -> GameRoom)
    private static final Map<String, GameRoom> activeRooms = new ConcurrentHashMap<>();

    // 2. Tracks every player on the server by their Secret Token (Token -> Player)
    private static final Map<String, Player> globalPlayers = new ConcurrentHashMap<>();

    // 3. Tracks which Token belongs to which live WebSocket connection
    private static final Map<WsContext, String> connectionToToken = new ConcurrentHashMap<>();
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    // 4. A background timer that won't freeze your web server threads
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

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
                        GameRoom newRoom = new GameRoom(newCode);
                        activeRooms.put(newCode, newRoom);

                        String token = generateToken();
                        Player host = new Player(token, action.playerName, Team.TEAM_A);

                        globalPlayers.put(token, host);
                        connectionToToken.put(ctx, token);
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
                            // Link their new browser connection to their old token!
                            connectionToToken.put(ctx, action.playerToken);
                            GameRoom theirRoom = findRoomForPlayer(returningPlayer);
                            if (theirRoom != null) {
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

                            // If that 4th card paused the room, start the 2.5 second stopwatch!
                            if (room.isPaused) {
                                scheduler.schedule(() -> {
                                    room.finalizeTrick(); // Sweep the cards and unlock
                                    broadcastToRoom(room); // Broadcast the clean table
                                }, 2500, TimeUnit.MILLISECONDS);
                            }
                        }
                        else if ("PLAY_AGAIN".equals(action.action)) {
                            room.playAnotherRound();
                            broadcastToRoom(room);
                        }
                        else if ("FINISH_GAME".equals(action.action)) {
                            room.finishMatch();
                            broadcastToRoom(room);
                        }
                        else if ("LEAVE_ROOM".equals(action.action)) {
                            room.removePlayer(player);
                            globalPlayers.remove(token);
                            connectionToToken.remove(ctx);

                            // Memory cleanup: If the room is now empty, delete the room from the server!
                            if (room.getPlayers().isEmpty()) {
                                activeRooms.remove(room.getRoomId());
                                System.out.println("Room " + room.getRoomId() + " closed (empty).");
                            } else {
                                broadcastToRoom(room); // Update the remaining players' screens
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Server Error: " + e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                String token = connectionToToken.remove(ctx); // Always drop the walkie-talkie

                if (token != null) {
                    Player player = globalPlayers.get(token);
                    if (player != null) {
                        GameRoom room = findRoomForPlayer(player);

                        // AUTO-KICK LOGIC: If the game hasn't started yet, delete them completely!
                        if (room != null && room.getCurrentPhase() == GamePhase.WAITING_FOR_PLAYERS) {
                            room.removePlayer(player);
                            globalPlayers.remove(token);
                            System.out.println(player.getName() + " left the lobby.");

                            if (room.getPlayers().isEmpty()) {
                                activeRooms.remove(room.getRoomId());
                            } else {
                                broadcastToRoom(room);
                            }
                        } else {
                            // If the game HAS started, keep their seat warm!
                            System.out.println(player.getName() + " disconnected (Seat saved for reconnect).");
                        }
                    }
                }
            });
        });
    }

    private static void broadcastToRoom(GameRoom room) {
        // Loop through every active internet connection on the server
        for (Map.Entry<WsContext, String> entry : connectionToToken.entrySet()) {
            WsContext connection = entry.getKey();
            String token = entry.getValue();
            Player player = globalPlayers.get(token);

            // SECURITY CHECK: Only send data if this connection belongs to a player in THIS specific room
            if (player == null || !room.getPlayers().contains(player)) {
                continue;
            }

            GameStateUpdate update = new GameStateUpdate();

            // --- NEW LOBBY DATA ---
            update.roomCode = room.getRoomId();
            update.myToken = token; // Sending the secret ID back so their browser can save it!
            update.errorMessage = null;

            // --- EXISTING GAME DATA ---
            update.currentPhase = room.getCurrentPhase().name();
            update.trumpSuit = room.getTrumpSuit() != null ? room.getTrumpSuit().name() : "NOT YET DISCOVERED";
            update.myName = player.getName();
            update.currentTurnPlayerName = room.getCurrentTurnPlayer() != null ? room.getCurrentTurnPlayer().getName() : "Waiting...";
            update.isPaused = room.isPaused;
            // Only the player whose object matches the current turn player gets "true"
            update.isMyTurn = (room.getCurrentTurnPlayer() != null && room.getCurrentTurnPlayer().equals(player));

            // Send the master seating order
            update.seatingOrder = room.getPlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());

            update.currentTrickCards = room.getCurrentTrick().getTableCards().values().stream()
                    .map(Card::toString)
                    .collect(Collectors.toList());

            // NEW: Send the exact names of the people who played those cards
            update.trickPlayerNames = room.getCurrentTrick().getTableCards().keySet().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            update.accumulatedPileSize = room.getTableAccumulator().size();

            update.myHand = player.getHand().stream()
                    .map(Card::toString)
                    .collect(Collectors.toList());

            // --- SCORES & HISTORY ---
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
    // Generates a random 4-digit code (e.g., "7392")
    private static String generateRoomCode() {
        return String.format("%04d", new java.util.Random().nextInt(10000));
    }

    // Generates an un-guessable secret token for the player
    private static String generateToken() {
        return java.util.UUID.randomUUID().toString();
    }

    // Finds which room a specific player is sitting in
    private static GameRoom findRoomForPlayer(Player player) {
        for (GameRoom room : activeRooms.values()) {
            if (room.getPlayers().contains(player)) return room;
        }
        return null;
    }
}