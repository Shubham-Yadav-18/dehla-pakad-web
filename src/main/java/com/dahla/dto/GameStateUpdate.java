package com.dahla.dto;

import java.util.List;

public class GameStateUpdate {
    public String currentPhase;
    public String trumpSuit;

    public String currentTurnPlayerName; // NEW: Whose turn is it?
    public String myName;                // NEW: Who am I?
    public boolean isMyTurn; // The server will tell us exactly if we can play!
    public List<String> currentTrickCards;
    public int accumulatedPileSize;
    public List<String> myHand;
    public int teamAScore;
    public int teamBScore;
    public int matchScoreA;
    public int matchScoreB;
    // --- LOBBY VARIABLES ---
    public String roomCode;
    public String myToken; // The secret ID the browser will save
    public String errorMessage; // Useful for telling the UI "Room is full!"
    // Add these near your matchScore variables
    public List<Integer> historyTeamA;
    public List<Integer> historyTeamB;

    // Add these so the frontend knows who is sitting where!
    public List<String> seatingOrder;
    public List<String> trickPlayerNames;

    public GameStateUpdate() {}
}