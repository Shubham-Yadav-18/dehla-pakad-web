package com.dahla.server;

import com.dahla.dto.GameStateUpdate;
import com.dahla.engine.GameRoom;
import com.dahla.model.Card;
import com.dahla.model.Player;
import com.dahla.model.Spectator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SpectatorManager {

    // 🌟 DDOS Protection: Hard cap on spectators to prevent RAM exhaustion
    public static final int MAX_SPECTATORS_PER_ROOM = 20;

    // 🌟 Concurrency-Safe Routing Maps
    private static final Map<String, Spectator> globalSpectators = new ConcurrentHashMap<>();
    private static final Map<String, GameRoom> spectatorRooms = new ConcurrentHashMap<>();
    private static final Map<WsContext, String> connectionToToken = new ConcurrentHashMap<>();

    public static boolean isSpectator(String token) {
        return token != null && globalSpectators.containsKey(token);
    }

    public static boolean isSpectatorContext(WsContext ctx) {
        return connectionToToken.containsKey(ctx);
    }

    public static String getToken(WsContext ctx) {
        return connectionToToken.get(ctx);
    }

    public static boolean joinAsSpectator(String token, String id, String name, GameRoom room, WsContext ctx) {
        if (room.getSpectators().size() >= MAX_SPECTATORS_PER_ROOM) {
            return false; // Safely block entry if room is full
        }

        Spectator spectator = new Spectator(id, name);
        globalSpectators.put(token, spectator);
        spectatorRooms.put(token, room);
        connectionToToken.put(ctx, token);

        room.addSpectator(spectator);
        System.out.println("[SPECTATOR] " + name + " joined room " + room.getRoomId());
        return true;
    }

    public static GameRoom leaveRoom(String token) {
        if (token == null) return null;
        Spectator spectator = globalSpectators.remove(token);
        GameRoom room = spectatorRooms.remove(token);

        // Find and remove connection
        connectionToToken.entrySet().removeIf(entry -> entry.getValue().equals(token));

        if (spectator != null && room != null) {
            room.removeSpectator(spectator);
            System.out.println("[SPECTATOR] " + spectator.getName() + " left room " + room.getRoomId());
            return room;
        }
        return null;
    }

    /**
     * 🌟 THE O(1) LAG-FREE BROADCAST & AGGRESSIVE PRUNING
     */
    public static void pruneAndBroadcast(GameRoom room, Map<String, Long> clientHeartbeats, ObjectMapper jsonMapper, GameStateUpdate spectatorBase) {
        List<Spectator> currentSpectators = room.getSpectators();
        if (currentSpectators.isEmpty()) return;

        long now = System.currentTimeMillis();

        // 1. Aggressive Pruning
        for (Spectator s : currentSpectators) {
            String token = getTokenForSpectator(s);
            if (token != null) {
                long lastSeen = clientHeartbeats.getOrDefault(token, 0L);
                if (lastSeen == 0L || (now - lastSeen) > 10000) {
                    System.out.println("[SPECTATOR] Pruning dead connection for: " + s.getName());
                    leaveRoom(token);
                    clientHeartbeats.remove(token);
                }
            }
        }

        currentSpectators = room.getSpectators();
        if (currentSpectators.isEmpty()) return;

        // 2. O(1) Serialization: Take the base payload and add the spectator-specific fields.
        try {
            spectatorBase.isSpectator = true;
            spectatorBase.myHand = new ArrayList<>(); // GUARANTEED EMPTY
            spectatorBase.isMyTurn = false;
            spectatorBase.spectatorCount = currentSpectators.size();
            spectatorBase.spectatorNames = currentSpectators.stream().map(Spectator::getName).collect(Collectors.toList());

            String jsonPayload = jsonMapper.writeValueAsString(spectatorBase);

            // 3. Blast to all spectators
            for (Map.Entry<WsContext, String> entry : connectionToToken.entrySet()) {
                String token = entry.getValue();
                if (spectatorRooms.get(token) != null && spectatorRooms.get(token).getRoomId().equals(room.getRoomId())) {
                    WsContext ctx = entry.getKey();
                    if (ctx.session.isOpen()) {
                        ctx.send(jsonPayload);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[SPECTATOR] Broadcast failed: " + e.getMessage());
        }
    }



    private static String getTokenForSpectator(Spectator s) {
        for (Map.Entry<String, Spectator> entry : globalSpectators.entrySet()) {
            if (entry.getValue().equals(s)) return entry.getKey();
        }
        return null;
    }

    public static void cleanUpRoom(GameRoom room, ObjectMapper mapper, String reason) {
        for (Spectator s : room.getSpectators()) {
            String token = getTokenForSpectator(s);
            if (token != null) {
                // Find connection and send dissolve message
                for (Map.Entry<WsContext, String> entry : connectionToToken.entrySet()) {
                    if (entry.getValue().equals(token)) {
                        if (entry.getKey().session.isOpen()) {
                            try { entry.getKey().send(mapper.writeValueAsString(Map.of("errorMessage", reason))); } catch (Exception ignored) {}
                        }
                    }
                }
                leaveRoom(token);
            }
        }
    }
}