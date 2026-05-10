package com.dahla.model;

import java.util.ArrayList;
import java.util.List;

public class Player {
    private final String id;       // Unique ID (useful later for WebSockets)
    private final String name;
    private final Team team;
    private List<Card> hand;

    public Player(String id, String name, Team team) {
        this.id = id;
        this.name = name;
        this.team = team;
        this.hand = new ArrayList<>();
    }

    public void setHand(List<Card> hand) {
        this.hand = hand;
    }

    public List<Card> getHand() {
        return hand;
    }

    public String getName() {
        return name;
    }

    public Team getTeam() {
        return team;
    }

    // A method to actually play a card from their hand
    public Card playCard(Card cardToPlay) {
        if (hand.contains(cardToPlay)) {
            hand.remove(cardToPlay);
            return cardToPlay;
        }
        throw new IllegalArgumentException("Player does not have this card!");
    }
}