package com.dahla.dto;

public class RoomSettings {
    // Rule 1: The strict Even-Dehla sweep mechanic
    public boolean strictSweepEnabled;

    // Rule 2: The round limit (Uses Integer so it can be null for infinite games)
    public Integer maxRounds;

    // Default constructor required by Jackson for JSON deserialization
    public RoomSettings() {
        // Industry Standard: Always set safe, default casual rules
        this.strictSweepEnabled = false;
        this.maxRounds = null; // Default to infinite play
    }
}