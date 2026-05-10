package com.dahla.engine;

public enum GamePhase {
    WAITING_FOR_PLAYERS, // Room created, waiting for 4 people to join
    FIRST_DEAL,          // Dealing 5 cards
    DISCOVERING_TRUMP,   // Playing tricks until someone breaks suit
    SECOND_DEAL,         // Trump found, dealing remaining 8 cards
    MAIN_PLAY,           // Playing the rest of the tricks
    ROUND_OVER           // 13 tricks done, calculating score
}