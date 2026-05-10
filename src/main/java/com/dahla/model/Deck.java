package com.dahla.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Deck {
    private final List<Card> cards;

    public Deck() {
        cards = new ArrayList<>();
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                cards.add(new Card(suit, rank));
            }
        }
    }

    public void shuffle() {
        Collections.shuffle(cards);
    }

    public List<List<Card>> deal() {
        List<List<Card>> hands = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            hands.add(new ArrayList<>(cards.subList(i * 13, (i + 1) * 13)));
        }
        return hands;
    }

    /**
     * Deals a single card from the top of the deck.
     * Removes the card from the deck so it cannot be dealt again.
     */
    public Card dealOneCard() {
        if (cards.isEmpty()) {
            throw new IllegalStateException("Cannot deal from an empty deck!");
        }
        // Remove and return the last card in the list (simulating the top of the deck)
        return cards.remove(cards.size() - 1);
    }
}