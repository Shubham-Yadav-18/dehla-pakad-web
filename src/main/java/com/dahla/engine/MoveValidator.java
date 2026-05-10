package com.dahla.engine;

import com.dahla.model.Card;
import com.dahla.model.Player;
import com.dahla.model.Suit;
import com.dahla.model.Trick;

public class MoveValidator {

    // --- GAME RULE TOGGLES ---
    public static boolean IS_STRICT_TRUMP_RULE_ENABLED = true;
    public static boolean ALLOW_DISCARD_IF_TEAMMATE_WINNING = true; // The new flexible toggle!

    public static void validateFollowSuitAndTrump(Player player, Card cardToPlay, Trick currentTrick, Suit trumpSuit, GamePhase phase) {
        Suit leadSuit = currentTrick.getLeadSuit();

        if (leadSuit == null) {
            return;
        }

        boolean hasLeadSuit = player.getHand().stream().anyMatch(c -> c.getSuit() == leadSuit);

        // RULE 1: STANDARD FOLLOW SUIT
        if (hasLeadSuit && cardToPlay.getSuit() != leadSuit) {
            throw new IllegalArgumentException("ILLEGAL MOVE: You must follow suit! You still have " + leadSuit + " in your hand.");
        }

        // RULE 2: STRICT TRUMP FORCING
        if (IS_STRICT_TRUMP_RULE_ENABLED) {
            if (!hasLeadSuit && trumpSuit != null && phase != GamePhase.DISCOVERING_TRUMP) {

                // --- NEW TEAMMATE EXCEPTION ---
                if (ALLOW_DISCARD_IF_TEAMMATE_WINNING && !currentTrick.getTableCards().isEmpty()) {
                    Player currentWinner = TrickEvaluator.determineCurrentWinner(currentTrick, trumpSuit);

                    // If the person currently winning the trick is on your team, you are off the hook!
                    if (currentWinner != null && currentWinner.getTeam() == player.getTeam()) {
                        return; // Exit the validator entirely. You can play any card.
                    }
                }

                // If your teammate is NOT winning, check if you have a trump to force it
                boolean hasTrump = player.getHand().stream().anyMatch(c -> c.getSuit() == trumpSuit);
                if (hasTrump && cardToPlay.getSuit() != trumpSuit) {
                    throw new IllegalArgumentException("STRICT RULE: You do not have the lead suit and your teammate is not winning. You MUST play a Trump card!");
                }
            }
        }
    }
}