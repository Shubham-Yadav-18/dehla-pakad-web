package com.dahla.dto;

import java.util.List;

public class GameStateUpdate {
    public String currentPhase;
    public String trumpSuit;

    public String currentTurnPlayerName; // NEW: Whose turn is it?
    public String myName;                // NEW: Who am I?

    public List<String> currentTrickCards;
    public int accumulatedPileSize;
    public List<String> myHand;
    public int teamAScore;
    public int teamBScore;

    public GameStateUpdate() {}
}