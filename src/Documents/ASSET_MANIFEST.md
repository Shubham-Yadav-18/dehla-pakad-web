# Dehla Pakad - Asset Manifest

**1. Audio Engine Overview:**
The game uses native HTML5 `<audio>` tags hidden at the bottom of `index.html`.
*Rule:* Sounds are played using a custom JavaScript function `playSound(soundId, allowOverlap)`.
*   If `allowOverlap` is true, the audio node is cloned so rapid sounds (like cards snapping) don't cut each other off.
*   The game respects a global `isMuted` boolean toggle.

**2. Registered Audio Assets (DOM IDs & File Paths):**
*   `sfx-card-snap` (`sounds/card-snap.mp3`): Plays whenever the trick pile size increases. Allows overlap.
*   `sfx-dehla-capture` (`sounds/dehla-capture.mp3`): Plays when Team A or Team B's Dehla score increases.
*   `sfx-error` (`sounds/error.mp3`): Plays when the server returns an `errorType: "RULE_VIOLATION"`.
*   `sfx-bowni-call` (`sounds/bowni-call.mp3`): Plays once when a team clicks the KOT/Bowni button.
*   `sfx-bowni-success` (`sounds/bowni-success.mp3`): Plays on the Victory Screen if a team caught all 4 Dehlas after calling Bowni.
*   `sfx-bowni-fail` (`sounds/bowni-fail.mp3`): Plays on the Victory Screen if a team failed their Bowni declaration.

**3. Image Assets:**
*   Currently, the game relies entirely on CSS rendering and Unicode characters.
*   *Future Note:* If table backgrounds or custom avatars are added, they must be implemented using CSS `background-image` linked to an external JSON configuration file (Gist), to preserve the zero-database architecture.

**Strict AI Instruction:** Do not write code attempting to fetch or play sounds that are not on this list. If a new sound is required for a feature, explicitly tell the developer to add a new `<audio>` tag to `index.html` first.