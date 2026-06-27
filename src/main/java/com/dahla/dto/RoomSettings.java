package com.dahla.dto;

public class RoomSettings {
    // Rule 1: The strict Even-Dehla sweep mechanic
    public boolean strictSweepEnabled;

    // Default constructor required by Jackson for JSON deserialization
    public RoomSettings() {
        // Industry Standard: Always set safe, default casual rules
        this.strictSweepEnabled = false;
    }
}