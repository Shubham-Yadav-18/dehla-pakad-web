# Dehla Pakad - Server Data Flow

**1. The Lobby Flow (GameServer.java)**
*   `CREATE_ROOM` -> Generates 4-digit code -> Creates `GameRoom` with immutable `RoomSettings` -> Generates UUID Token -> Creates `Player` -> Maps Token to WsContext.
*   `JOIN_ROOM` -> Checks if room < 4 players -> Assigns Team A/B based on array size -> Creates Player.

**2. The Trick Flow (GameRoom.java)**
*   Player clicks card -> Sends `PLAY_CARD`.
*   `GameRoom.playCard()` -> Checks turn -> Calls `MoveValidator`.
*   `MoveValidator` -> Checks Follow Suit rule & Strict Trump rule (with Teammate Winning exception).
*   If valid -> Added to `Trick`.
*   If `Trick` is complete (4 cards) -> Table is locked (`isTrickPaused = true`). `ScheduledExecutorService` spawns a 2.5s delay.
*   After 2.5s -> `finalizeTrick()` calls `resolveCompletedTrick()`.
*   `TrickEvaluator` -> Determines winner -> Checks `RoomSettings.strictSweepEnabled` -> Awards Dehlas -> Clears table -> Next turn starts.

**3. The Phase Flow**
1.  `WAITING_FOR_PLAYERS` (Lobby)
2.  `DISCOVERING_TRUMP` (5 cards dealt, playing until someone breaks suit)
3.  `SECOND_DEAL` (Trump found, remaining 8 cards dealt instantly)
4.  `BOWNI_DECLARATION` (10-second timer for KOT declaration)
5.  `MAIN_PLAY` (Playing remaining tricks)
6.  `ROUND_OVER` (13 tricks done, calculates scores, updates Match history)
7.  `MATCH_OVER` (Triggered if `currentRoundNumber >= maxRounds`)