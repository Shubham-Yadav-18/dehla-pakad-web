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

public class GameServer {

    private static final Map<WsContext, Player> playerConnections = new ConcurrentHashMap<>();
    private static final GameRoom gameRoom = new GameRoom("Room-Production");
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("Starting Dehla Pakad Server on port 7070...");

        // Replace the broken config block with this simple line:
        Javalin app = Javalin.create().start(7070);

        app.ws("/game", ws -> {

            ws.onConnect(ctx -> {
                ctx.session.setIdleTimeout(Duration.ofMinutes(30));
                if (playerConnections.size() >= 4) {
                    ctx.session.close(1000, "Room is full!");
                    return;
                }

                Team assignedTeam = (playerConnections.size() % 2 == 0) ? Team.TEAM_A : Team.TEAM_B;
                Player newPlayer = new Player(ctx.getSessionId(), "Player " + (playerConnections.size() + 1), assignedTeam);

                playerConnections.put(ctx, newPlayer);
                gameRoom.addPlayer(newPlayer);
                System.out.println(newPlayer.getName() + " connected!");

                if (playerConnections.size() == 4) {
                    System.out.println("4 Players joined. Starting game!");
                    gameRoom.startGame();
                    broadcastGameState();
                }
            });

            ws.onMessage(ctx -> {
                if (gameRoom.getCurrentPhase() == GamePhase.WAITING_FOR_PLAYERS) return;

                Player player = playerConnections.get(ctx);
                try {
                    PlayerAction action = jsonMapper.readValue(ctx.message(), PlayerAction.class);

                    if ("PLAY_CARD".equals(action.action)) {
                        Card cardToPlay = player.getHand().stream()
                                .filter(c -> c.getSuit().name().equals(action.suit) && c.getRank().name().equals(action.rank))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("Card not found in hand!"));

                        gameRoom.playCard(player, cardToPlay);
                        broadcastGameState();
                    }
                    else if ("PLAY_AGAIN".equals(action.action)) {
                        gameRoom.playAnotherRound();
                        broadcastGameState();
                    }
                    else if ("FINISH_GAME".equals(action.action)) {
                        gameRoom.finishMatch();
                        broadcastGameState();
                    }
                } catch (Exception e) {
                    System.err.println("Move error from " + player.getName() + ": " + e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                Player disconnected = playerConnections.remove(ctx);
                if (disconnected != null) System.out.println(disconnected.getName() + " disconnected.");
            });
        });
    }

    private static void broadcastGameState() {
        for (Map.Entry<WsContext, Player> entry : playerConnections.entrySet()) {
            WsContext connection = entry.getKey();
            Player player = entry.getValue();

            GameStateUpdate update = new GameStateUpdate();
            update.currentPhase = gameRoom.getCurrentPhase().name();
            update.trumpSuit = gameRoom.getTrumpSuit() != null ? gameRoom.getTrumpSuit().name() : "NOT YET DISCOVERED";
            update.myName = player.getName();
            update.currentTurnPlayerName = gameRoom.getCurrentTurnPlayer() != null ?
                    gameRoom.getCurrentTurnPlayer().getName() : "Waiting...";
            // Send the cards currently being played in the middle of the table!
            update.currentTrickCards = gameRoom.getCurrentTrick().getTableCards().values().stream()
                    .map(Card::toString)
                    .collect(Collectors.toList());

            // Send the size of the un-swept pile
            update.accumulatedPileSize = gameRoom.getTableAccumulator().size();

            update.myHand = player.getHand().stream()
                    .map(Card::toString)
                    .collect(Collectors.toList());

            update.teamAScore = gameRoom.getTeamADehlasCount();
            update.teamBScore = gameRoom.getTeamBDehlasCount();
            update.matchScoreA = gameRoom.getMatchPointsTeamA();
            update.matchScoreB = gameRoom.getMatchPointsTeamB();

            update.historyTeamA = gameRoom.getHistoryTeamA();
            update.historyTeamB = gameRoom.getHistoryTeamB();

            try {
                // BUG FIX: Only send data if the tab is still open to prevent server crashes
                if (connection.session.isOpen()) {
                    connection.send(jsonMapper.writeValueAsString(update));
                }
            } catch (Exception e) {
                System.err.println("Failed to send state to a player.");
            }
        }
    }
}