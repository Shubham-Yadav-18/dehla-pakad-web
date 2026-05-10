package com.dahla;

import com.dahla.engine.GamePhase;
import com.dahla.engine.GameRoom;
import com.dahla.engine.ScoreManager;
import com.dahla.model.Card;
import com.dahla.model.Player;
import com.dahla.model.Suit;
import com.dahla.model.Team;

import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) {
        System.out.println("=== STARTING FULL DEHLA PAKAD MATCH ===\n");

        GameRoom room = new GameRoom("Room-101");

        // Team A: Aman & Rahul | Team B: Vikram & Suraj
        Player p1 = new Player("1", "Aman", Team.TEAM_A);
        Player p2 = new Player("2", "Vikram", Team.TEAM_B);
        Player p3 = new Player("3", "Rahul", Team.TEAM_A);
        Player p4 = new Player("4", "Suraj", Team.TEAM_B);

        room.addPlayer(p1);
        room.addPlayer(p2);
        room.addPlayer(p3);
        room.addPlayer(p4);

        room.startGame();
        System.out.println("Game Started. Initial 5 cards dealt.\n");

        Player[] turnOrder = {p1, p2, p3, p4};
        boolean trumpAnnounced = false;

        // --- THE FULL 13 TRICK LOOP ---
        int trickNumber = 1;
        while (trickNumber <= 13) {
            System.out.println("--- TRICK " + trickNumber + " ---");

            // 1. Set Seating Order based on previous winner
            Player lastWinner = room.getLastTrickWinner();
            if (lastWinner != null) {
                turnOrder = rotateTurnOrder(turnOrder, lastWinner);
                System.out.println(">>> " + lastWinner.getName() + " leads the trick! <<<");
            }

            Suit leadSuitForThisTrick = null;

            // 2. Play 4 Cards
            for (Player currentPlayer : turnOrder) {
                Card cardToPlay = getValidCardToPlay(currentPlayer, leadSuitForThisTrick);

                if (leadSuitForThisTrick == null) {
                    leadSuitForThisTrick = cardToPlay.getSuit();
                }

                System.out.println(currentPlayer.getName() + " plays: " + cardToPlay);
                room.playCard(currentPlayer, cardToPlay);

                // Check for Trump Discovery
                if (!trumpAnnounced && room.getCurrentPhase() == GamePhase.SECOND_DEAL) {
                    System.out.println("\n*** TRUMP DISCOVERED! ***");
                    System.out.println(currentPlayer.getName() + " broke suit. Trump is now: " + room.getTrumpSuit());
                    trumpAnnounced = true;
                }
            }

            System.out.println("Trick complete. Cards accumulated on table: " + room.getTableAccumulator().size() + "\n");
            trickNumber++;
        }

        // --- END OF GAME SCORING ---
        System.out.println("=== MATCH OVER ===");
        System.out.println("Team A (Aman & Rahul) captured Dehlas: " + room.getTeamADehlasCount());
        System.out.println("Team B (Vikram & Suraj) captured Dehlas: " + room.getTeamBDehlasCount());

        // Calculate Final Points (Assuming no one called Kot for this simulation)
        Map<Team, Integer> finalScores = ScoreManager.calculateRoundScore(
                room.getTeamADehlasCount(),
                room.getTeamBDehlasCount(),
                null
        );

        System.out.println("\n=== FINAL POINTS ===");
        System.out.println("Team A Points: " + finalScores.get(Team.TEAM_A));
        System.out.println("Team B Points: " + finalScores.get(Team.TEAM_B));
    }

    // --- AI BOTS AND HELPERS ---
    private static Card getValidCardToPlay(Player player, Suit leadSuit) {
        List<Card> hand = player.getHand();
        if (leadSuit == null || hand.isEmpty()) return hand.get(0);

        for (Card card : hand) {
            if (card.getSuit() == leadSuit) return card;
        }
        return hand.get(0);
    }

    private static Player[] rotateTurnOrder(Player[] currentOrder, Player winner) {
        Player[] newOrder = new Player[4];
        int winnerIndex = 0;
        for (int i = 0; i < 4; i++) {
            if (currentOrder[i].equals(winner)) {
                winnerIndex = i;
                break;
            }
        }
        for (int i = 0; i < 4; i++) {
            newOrder[i] = currentOrder[(winnerIndex + i) % 4];
        }
        return newOrder;
    }
}