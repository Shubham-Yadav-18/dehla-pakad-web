package com.dahla.engine;

import com.dahla.model.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class GameRoom {
    private final String roomId;
    private final List<Player> players;
    private GamePhase currentPhase;

    public boolean isTrickPaused = false;   // For the 2.5s animation
    public boolean isNetworkPaused = false; // For the 60s disconnect wait
    public boolean isBowniTimerStarted = false;
    private Suit trumpSuit;
    private Trick currentTrick;
    private Player lastTrickWinner;
    private Player lastWinningPlayerForSweep; // Now we track the specific player!
    private int teamAFinalPoints;
    private int teamBFinalPoints;


    private int matchPointsTeamA = 0;
    private int matchPointsTeamB = 0;
    private final List<Card> tableAccumulator;

    private List<Integer> historyTeamA = new java.util.ArrayList<>();
    private List<Integer> historyTeamB = new java.util.ArrayList<>();



    private int teamADehlasCount;
    private int teamBDehlasCount;
    private Team teamWhoCalledKot;

    // Added a deck to manage the cards for this specific room
    private Deck roomDeck;
    private Player currentTurnPlayer;

    public GameRoom(String roomId) {
        this.roomId = roomId;
        this.players = new ArrayList<>();
        this.currentPhase = GamePhase.WAITING_FOR_PLAYERS;
        this.tableAccumulator = new ArrayList<>();
        this.currentTrick = new Trick();
    }

    public void addPlayer(Player player) {
        if (players.size() < 4) {
            players.add(player);
        } else {
            throw new IllegalStateException("Room is full!");
        }
    }

    /**
     * PHASE 1: Starts the game and deals the first 5 cards to everyone.
     */
    public void startGame() {
        if (players.size() != 4) {
            throw new IllegalStateException("Need exactly 4 players to start.");
        }

        this.roomDeck = new Deck();
        this.roomDeck.shuffle();

        // Deal exactly 5 cards to each player
        for (Player player : players) {
            List<Card> initialHand = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                initialHand.add(roomDeck.dealOneCard());
            }
            player.setHand(initialHand);
        }


        this.currentPhase = GamePhase.DISCOVERING_TRUMP;
        this.currentTurnPlayer = players.get(0); // ADD THIS: Player 1 starts the game
    }

    /**
     * The main entry point for a player making a move.
     */
    public void playCard(Player player, Card card) {

        // 0. SECURITY: Reject clicks if the game is paused for the 2.5s delay
        if (isTrickPaused || isNetworkPaused) {
            return;
        }

        // 1. Check Turn Order
        if (currentTurnPlayer != null && !currentTurnPlayer.equals(player)) {
            throw new IllegalStateException("It is not your turn!");
        }

        // 2. DELEGATE TO OUR SEPARATE RULE ENGINE
        MoveValidator.validateFollowSuitAndTrump(player, card, currentTrick, trumpSuit, currentPhase);

        // 3. Add card to trick (It is now 100% verified as legal)
        currentTrick.addCard(player, card);

        // 4. TRUMP DISCOVERY LOGIC
        if (currentPhase == GamePhase.DISCOVERING_TRUMP) {
            if (currentTrick.getLeadSuit() != card.getSuit()) {
                this.trumpSuit = card.getSuit();
                //now Second Deal phase will be set in ResolveCompletedtrick after current tricks get completed
             //   this.currentPhase = GamePhase.SECOND_DEAL;
            }
        }

        // 5. RESOLVE TRICK OR PASS TURN
        if (currentTrick.isComplete()) {
            this.isTrickPaused = true; // Lock the table! The Server will clean it up in 2.5s.
            this.currentTurnPlayer = null; // Nobody's turn while we wait
        } else {
            int currentIndex = players.indexOf(player);
            this.currentTurnPlayer = players.get((currentIndex + 1) % 4);
        }
    }

    /**
     * Called by the Server's timer 2.5 seconds after the 4th card is played.
     */
    public void finalizeTrick() {
        resolveCompletedTrick(); // Your existing method that sweeps cards and scores
        this.currentTurnPlayer = this.lastTrickWinner; // Give turn to the winner
        this.isTrickPaused = false; // ONLY unlock the trick timer!
    }
    /**
     * Resolves the trick and checks for Edge Case 1.
     */
    public void resolveCompletedTrick() {
        if (!currentTrick.isComplete()) return;

        Player trickWinner = TrickEvaluator.determineWinner(currentTrick, trumpSuit);

        // Add the 4 played cards to the table pile
        tableAccumulator.addAll(currentTrick.getTableCards().values());

        // --- NEW STRICT PLAYER-BASED SWEEP LOGIC ---
        if (lastWinningPlayerForSweep != null && lastWinningPlayerForSweep.equals(trickWinner) && (this.currentPhase == GamePhase.MAIN_PLAY)) {
            // The EXACT SAME player won two in a row! Sweep the table!
            sweepTableForTeam(trickWinner.getTeam()); // The cards still go to the team's total score

            // RESET THE STREAK: They must win two NEW tricks to sweep again.
            lastWinningPlayerForSweep = null;
        } else {
            // No sweep yet. Remember this specific player for the next trick.
            lastWinningPlayerForSweep = trickWinner;
        }

        // Keep this so Main.java knows who leads the next trick!
        this.lastTrickWinner = trickWinner;

        Suit finalLeadSuitOfThisTrick = currentTrick.getLeadSuit();
        this.currentTrick = new Trick();

        // Check if players have run out of cards
        boolean forceEndgame = players.get(0).getHand().isEmpty();

        // --- 🌟 NEW: SUDDEN DEATH BOWNI CHECK ---
        if (teamWhoCalledKot != null) {
            boolean callingTeamWon = (teamWhoCalledKot == Team.TEAM_A && teamADehlasCount == 4) ||
                    (teamWhoCalledKot == Team.TEAM_B && teamBDehlasCount == 4);
            boolean callingTeamLost = (teamWhoCalledKot == Team.TEAM_A && teamBDehlasCount > 0) ||
                    (teamWhoCalledKot == Team.TEAM_B && teamADehlasCount > 0);

            if (callingTeamWon || callingTeamLost) {
                forceEndgame = true; // Instantly trigger the round-over logic below!
            }
        }
// 🌟 THE BACKEND FIX: Safely transition the phase ONLY after the 4th card is swept
        if (this.currentPhase == GamePhase.DISCOVERING_TRUMP && this.trumpSuit != null) {
            this.currentPhase = GamePhase.SECOND_DEAL;
        }

        // EDGE CASE 1: 5 tricks done, no trump found yet
        if (currentPhase == GamePhase.DISCOVERING_TRUMP && forceEndgame) {
            this.trumpSuit = finalLeadSuitOfThisTrick;
            this.currentPhase = GamePhase.SECOND_DEAL;
        }

        // Transition Phases
        if (currentPhase == GamePhase.SECOND_DEAL) {
            dealRemainingCards();
        }
        // === BUG FIX: THE 13TH TRICK & FINAL SCORING ===
        else if (currentPhase == GamePhase.MAIN_PLAY && forceEndgame) {

            // 1. The winner of the 13th trick takes whatever is left on the table!
            if (!tableAccumulator.isEmpty()) {
                sweepTableForTeam(trickWinner.getTeam());
            }

            // 2. Change phase to tell the UI the game is over
            this.currentPhase = GamePhase.ROUND_OVER;

            // 3. Convert Dehlas into actual Game Points using our ScoreManager!
            Map<Team, Integer> finalScores = ScoreManager.calculateRoundScore(
                    teamADehlasCount,
                    teamBDehlasCount,
                    teamWhoCalledKot // (null if no one called it)
            );

            this.teamAFinalPoints = finalScores.get(Team.TEAM_A);
            this.teamBFinalPoints = finalScores.get(Team.TEAM_B);
            // ADD TO THE TOTAL MATCH SCORE:
            this.matchPointsTeamA += this.teamAFinalPoints;
            this.matchPointsTeamB += this.teamBFinalPoints;


            // ADD TO HISTORY LOG:
            this.historyTeamA.add(this.teamAFinalPoints);
            this.historyTeamB.add(this.teamBFinalPoints);

            System.out.println("Round Over! Final Points -> Team A: " + matchPointsTeamA + " | Team B: " + matchPointsTeamB);
        }
    }

    /**
     * PHASE 3: Deals the remaining 8 cards after Trump is found.
     */
    private void dealRemainingCards() {
        for (Player player : players) {
            List<Card> remainingHand = player.getHand(); // Get their current hand (which might have 1-4 cards left)
            for (int i = 0; i < 8; i++) {
                remainingHand.add(roomDeck.dealOneCard());
            }
        }
        this.currentPhase = GamePhase.BOWNI_DECLARATION;
    }

    private void sweepTableForTeam(Team winningTeam) {
        int dehlasInPile = 0;
        for (Card card : tableAccumulator) {
            if (card.isDehla()) {
                dehlasInPile++;
            }
        }

        if (winningTeam == Team.TEAM_A) {
            teamADehlasCount += dehlasInPile;
        } else {
            teamBDehlasCount += dehlasInPile;
        }
        tableAccumulator.clear();
    }
    public void playAnotherRound() {
        if (this.currentPhase != GamePhase.ROUND_OVER) return;

        // --- RESET ALL ROUND VARIABLES ---
        this.teamADehlasCount = 0;
        this.teamBDehlasCount = 0;

        // ADD THESE TWO LINES: Wipe the round points clean for the new hand!
        this.teamAFinalPoints = 0;
        this.teamBFinalPoints = 0;

        this.trumpSuit = null;
        this.teamWhoCalledKot = null;
        this.isBowniTimerStarted = false;
        this.lastTrickWinner = null;
        this.lastWinningPlayerForSweep = null;
        this.tableAccumulator.clear();
        this.currentTrick = new Trick();

        // Deal a brand new deck and start!
        startGame();
    }
    public void finishMatch() {
        this.currentPhase = GamePhase.MATCH_OVER; // We are adding a new phase!
    }
    public void removePlayer(Player player) {
        this.players.remove(player);
    }

    // Getters
    public GamePhase getCurrentPhase() { return currentPhase; }
    public Suit getTrumpSuit() { return trumpSuit; }
    public List<Card> getTableAccumulator() { return tableAccumulator; }
    public Player getLastTrickWinner() { return lastTrickWinner; }
    public int getTeamADehlasCount() { return teamADehlasCount; }
    public int getTeamBDehlasCount() { return teamBDehlasCount; }
    public Trick getCurrentTrick() { return currentTrick; }
    public Player getCurrentTurnPlayer() { return currentTurnPlayer; }
    public String getRoomId(){ return  roomId;}
        public int getTeamAFinalPoints() { return teamAFinalPoints; }
        public int getTeamBFinalPoints() { return teamBFinalPoints; }
       public int getMatchPointsTeamA(){ return matchPointsTeamA;}
       public int getMatchPointsTeamB(){ return matchPointsTeamB;}
    // Add their getters at the very bottom of the file:
    public List<Integer> getHistoryTeamA() { return historyTeamA; }
    public List<Integer> getHistoryTeamB() { return historyTeamB; }

    public List<Player> getPlayers() {return players;}

    public void setTeamWhoCalledKot(Team team) { this.teamWhoCalledKot = team; }
    public Team getTeamWhoCalledKot() { return teamWhoCalledKot; }
    public void setCurrentPhase(GamePhase phase) { this.currentPhase = phase; }
}