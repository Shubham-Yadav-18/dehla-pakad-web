package com.dahla.engine;

import com.dahla.model.Team;

import java.util.EnumMap;
import java.util.Map;

public class ScoreManager {

    private static final int KOT_CALL_POINTS = 52;
    private static final int NATURAL_KOT_POINTS = 10;
    private static final int DEHLA_POINT = 1;

    /**
     * Calculates the score for a completed round.
     *
     * @param teamADehlas The number of 10s captured by Team A (0 to 4)
     * @param teamBDehlas The number of 10s captured by Team B (0 to 4)
     * @param callingTeam The team that confidently declared Kot (null if no one declared)
     * @return Map containing the points awarded to each team this round
     */
    public static Map<Team, Integer> calculateRoundScore(int teamADehlas, int teamBDehlas, Team callingTeam) {

        // We use EnumMap because it is highly optimized and faster than HashMap for Enums
        Map<Team, Integer> scores = new EnumMap<>(Team.class);
        int scoreA = 0;
        int scoreB = 0;

        // SCENARIO 1: A team confidently DECLARED a Kot (The 52-Point High Stakes Rule)
        if (callingTeam != null) {
            if (callingTeam == Team.TEAM_A) {
                if (teamADehlas == 4) {
                    scoreA = KOT_CALL_POINTS; // They succeeded!
                } else {
                    scoreB = KOT_CALL_POINTS; // They failed! Opponents get 52 points.
                }
            } else if (callingTeam == Team.TEAM_B) {
                if (teamBDehlas == 4) {
                    scoreB = KOT_CALL_POINTS; // They succeeded!
                } else {
                    scoreA = KOT_CALL_POINTS; // They failed! Opponents get 52 points.
                }
            }
        }
        // SCENARIO 2: Normal Gameplay (No one declared Kot beforehand)
        else {
            // Check for a "Natural" Kot (Edge Case 3 from earlier)
            if (teamADehlas == 4) {
                scoreA = NATURAL_KOT_POINTS;
            } else if (teamBDehlas == 4) {
                scoreB = NATURAL_KOT_POINTS;
            } else {
                // Standard scoring: 1 point per Dehla captured
                scoreA = teamADehlas * DEHLA_POINT;
                scoreB = teamBDehlas * DEHLA_POINT;
            }
        }

        scores.put(Team.TEAM_A, scoreA);
        scores.put(Team.TEAM_B, scoreB);

        return scores;
    }
}