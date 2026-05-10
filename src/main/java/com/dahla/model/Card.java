package com.dahla.model;

public class Card {
    private final Suit suit;
    private final Rank rank;

    public Card(Suit suit, Rank rank) {
        this.suit = suit;
        this.rank = rank;
    }

    public Suit getSuit() { return suit; }
    public Rank getRank() { return rank; }

    public boolean isDehla() {
        return this.rank == Rank.TEN;
    }

    @Override
    public String toString() {
        return rank + " of " + suit;
    }
}