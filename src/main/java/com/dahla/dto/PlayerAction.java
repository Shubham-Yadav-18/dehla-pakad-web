package com.dahla.dto;

public class PlayerAction {
    // Jackson needs these fields to be public (or have getters/setters) to read the JSON
    public String action; // e.g., "PLAY_CARD"
    public String suit;   // e.g., "SPADES"
    public String rank;   // e.g., "TEN"

    // Default constructor is required by Jackson for JSON deserialization
    public PlayerAction() {}
}