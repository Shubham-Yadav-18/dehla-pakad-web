# Dehla Pakad - Project Roadmap & Future Vision

**Purpose:** This document outlines planned features and architectural shifts. When writing code for current tasks, the AI must ensure the implementation is flexible enough to support these future goals without requiring massive refactoring.

**1. Phase 1: Enhanced Casino Experience (Short-Term)**
*   **Dynamic Theming (External JSON):** Adding the ability to swap the table's center image and game sound effects dynamically via an external GitHub Gist/JSON config without redeploying code.
*   **In-Game Text Chat & Emotes:** A small chat drawer where players can send quick messages or emojis to the room. *(Architectural note: Will require a new action like `CHAT_MESSAGE` in the WebSocket payload).*
*   **Turn Timers:** A countdown timer (e.g., 30 seconds) for each player's turn to prevent game stalling. Auto-play or auto-kick logic if the timer expires.

**2. Phase 2: Player Identity & Lobbies (Medium-Term)**
*   **Spectator Mode:** Allowing players to join a room via code, but if it is full (4 players), they are assigned a `SPECTATOR` role. They receive `GameStateUpdate` payloads but cannot send `PlayerAction` payloads.
*   **Public Room Browser:** Moving beyond just private 4-digit codes to an "Active Rooms" list in the lobby so strangers can join open games.
*   **Player Avatars:** Allowing players to select a profile picture or avatar from a preset list when joining a room.

**3. Phase 3: Scaling & Persistence (Long-Term / Major Shifts)**
*   **Persistent Database:** Eventually migrating from purely in-memory `ConcurrentHashMap` to a database (like PostgreSQL or MongoDB) to track permanent player stats, leaderboards, and win/loss ratios.
*   **Authentication:** Integrating Google Login or standard JWT authentication so players can keep their identities across devices.
*   **Redis Pub/Sub:** If the game grows massively, the single Javalin server will need to scale horizontally. State would move to Redis so multiple server nodes can communicate.

**Strict AI Instruction:**
When building new features today, do not hardcode limits that would block these roadmap items. For example, if looping over players, use dynamic arrays instead of assuming there will *only* ever be 4 connections (to leave room for Spectators). Keep the `PlayerAction` and `GameStateUpdate` JSON structures modular so we can easily bolt on "Chat" or "Timer" nodes later.