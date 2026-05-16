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
    public int matchScoreA;
    public int matchScoreB;
    // Add these near your matchScore variables
    public List<Integer> historyTeamA;
    public List<Integer> historyTeamB;

    public GameStateUpdate() {}
}