package com.dahla.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class Trick {
    // We use LinkedHashMap because it Remembers the exact order cards were played
    private final Map<Player, Card> tableCards;
    private Suit leadSuit;

    public Trick() {
        this.tableCards = new LinkedHashMap<>();
        this.leadSuit = null;
    }

    /**
     * Attempts to play a card onto the table.
     */
    public void addCard(Player player, Card cardToPlay) {
        // 1. Prevent more than 4 cards from being played
        if (tableCards.size() >= 4) {
            throw new IllegalStateException("The trick is already full (4 cards played)!");
        }

        // 2. The first person to play sets the "Lead Suit"
        if (tableCards.isEmpty()) {
            this.leadSuit = cardToPlay.getSuit();
        }

        // 3. If all checks pass, actually remove the card from the player and put it on the table
        player.playCard(cardToPlay);
        tableCards.put(player, cardToPlay);
    }

    /**
     * Helper method to scan a player's hand to see if they are hiding a specific suit
     */
    private boolean playerHasSuit(Player player, Suit suit) {
        for (Card c : player.getHand()) {
            if (c.getSuit() == suit) {
                return true;
            }
        }
        return false;
    }

    // Getters for the Game Engine to use later
    public Suit getLeadSuit() { return leadSuit; }
    public Map<Player, Card> getTableCards() { return tableCards; }
    public boolean isComplete() { return tableCards.size() == 4; }
}