package com.dahla.engine;

import com.dahla.model.Card;
import com.dahla.model.Player;
import com.dahla.model.Suit;
import com.dahla.model.Trick;

import java.util.Map;

public class TrickEvaluator {

    /**
     * Determines the winner of a completed trick.
     */
    public static Player determineWinner(Trick trick, Suit trumpSuit) {
        if (!trick.isComplete()) {
            throw new IllegalStateException("Trick is not complete yet!");
        }

        Player winner = null;
        Card winningCard = null;
        Suit leadSuit = trick.getLeadSuit();

        for (Map.Entry<Player, Card> entry : trick.getTableCards().entrySet()) {
            Player currentPlayer = entry.getKey();
            Card currentCard = entry.getValue();

            // The first card played is automatically the winning card until beaten
            if (winningCard == null) {
                winner = currentPlayer;
                winningCard = currentCard;
                continue;
            }

            boolean isCurrentTrump = (trumpSuit != null && currentCard.getSuit() == trumpSuit);
            boolean isWinningTrump = (trumpSuit != null && winningCard.getSuit() == trumpSuit);

            if (isCurrentTrump && !isWinningTrump) {
                // A trump was played, and it beats the current winning non-trump card
                winner = currentPlayer;
                winningCard = currentCard;
            } else if (isCurrentTrump && isWinningTrump) {
                // Both are trumps; the higher rank wins
                if (currentCard.getRank().value > winningCard.getRank().value) {
                    winner = currentPlayer;
                    winningCard = currentCard;
                }
            } else if (!isCurrentTrump && !isWinningTrump) {
                // Neither is a trump; check if the current card follows the lead suit
                if (currentCard.getSuit() == leadSuit) {
                    // Highest rank of the lead suit wins
                    if (currentCard.getRank().value > winningCard.getRank().value) {
                        winner = currentPlayer;
                        winningCard = currentCard;
                    }
                }
                // If it's not a trump and doesn't follow the lead suit, it has zero power, so ignore it.
            }
        }

        return winner;
    }
    /**
     * Determines who is currently winning an INCOMPLETE trick.
     * Useful for checking if a teammate is winning before forcing a trump play.
     */
    public static Player determineCurrentWinner(Trick trick, Suit trumpSuit) {
        if (trick.getTableCards().isEmpty()) {
            return null; // No one is winning yet
        }

        Player currentWinner = null;
        Card winningCard = null;
        Suit leadSuit = trick.getLeadSuit();

        for (Map.Entry<Player, Card> entry : trick.getTableCards().entrySet()) {
            Player currentPlayer = entry.getKey();
            Card currentCard = entry.getValue();

            if (winningCard == null) {
                currentWinner = currentPlayer;
                winningCard = currentCard;
                continue;
            }

            boolean isCurrentTrump = (trumpSuit != null && currentCard.getSuit() == trumpSuit);
            boolean isWinningTrump = (trumpSuit != null && winningCard.getSuit() == trumpSuit);

            if (isCurrentTrump && !isWinningTrump) {
                currentWinner = currentPlayer;
                winningCard = currentCard;
            } else if (isCurrentTrump && isWinningTrump) {
                if (currentCard.getRank().value > winningCard.getRank().value) {
                    currentWinner = currentPlayer;
                    winningCard = currentCard;
                }
            } else if (!isCurrentTrump && !isWinningTrump) {
                if (currentCard.getSuit() == leadSuit) {
                    if (currentCard.getRank().value > winningCard.getRank().value) {
                        currentWinner = currentPlayer;
                        winningCard = currentCard;
                    }
                }
            }
        }
        return currentWinner;
    }
}