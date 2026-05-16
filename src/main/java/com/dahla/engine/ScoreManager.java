package com.dahla.engine;

import com.dahla.model.Team;
import java.util.HashMap;
import java.util.Map;

public class ScoreManager {

    /**
     * Calculates the points awarded at the end of a 13-trick round.
     * Returns a map containing the points earned by Team A and Team B.
     */
    public static Map<Team, Integer> calculateRoundScore(int teamADehlas, int teamBDehlas, Team teamWhoCalledKot) {
        int pointsForTeamA = 0;
        int pointsForTeamB = 0;

        if (teamWhoCalledKot != null) {
            // --- HIGH STAKES: SOMEONE CALLED KOT ---
            if (teamWhoCalledKot == Team.TEAM_A) {
                if (teamADehlas == 4) {
                    pointsForTeamA = 52; // Team A called Kot and succeeded
                } else {
                    pointsForTeamB = 52; // Team A called Kot and failed! Team B gets the points!
                }
            } else if (teamWhoCalledKot == Team.TEAM_B) {
                if (teamBDehlas == 4) {
                    pointsForTeamB = 52; // Team B called Kot and succeeded
                } else {
                    pointsForTeamA = 52; // Team B called Kot and failed! Team A gets the points!
                }
            }
        } else {
            // --- NORMAL GAME: NO KOT CALLED ---
            if (teamADehlas == 4) {
                pointsForTeamA = 1; // Team A captured all 4 normally
            } else if (teamBDehlas == 4) {
                pointsForTeamB = 1; // Team B captured all 4 normally
            }
            // Note: If the Dehlas are split 3-1 or 2-2, both teams stay at 0 points!
        }

        Map<Team, Integer> finalScores = new HashMap<>();
        finalScores.put(Team.TEAM_A, pointsForTeamA);
        finalScores.put(Team.TEAM_B, pointsForTeamB);
        return finalScores;
    }
}