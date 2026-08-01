# Dehla Pakad - UI & Styling Guide

**1. Core Frameworks & Fonts:**
*   **CSS Framework:** Bootstrap 5 (via CDN). *Rule: Always prefer native Bootstrap utility classes (e.g., `d-flex`, `mt-3`, `text-center`) before writing custom CSS.*
*   **Typography:** Google Fonts 'Poppins' (`font-family: 'Poppins', sans-serif;`).
*   **Icons:** Native Unicode/Emojis (♥️, ♦️, ♣️, ♠️, 🔥, 🏆). Do not add FontAwesome or external icon libraries.

**2. CSS Variables (The Theme):**
*   *Backgrounds:* `--bg-gradient-start`, `--bg-gradient-end`
*   *Glassmorphism:* `--glass-bg`, `--glass-border`, `--glass-shadow`
*   *Accents:* `--accent-primary` (Blue), `--accent-secondary`, `--accent-hover`
*   *Text:* `--text-main` (Light Gray), `--text-highlight` (Pure White)

**3. Primary Custom CSS Classes:**
*   `.glass-panel`: Used for all main modal/screen containers. Adds blur and border.
*   `.glass-inner`: Used for nested containers inside a glass-panel (darker, inset shadow).
*   `.premium-input`: Custom styling for text inputs.
*   `.btn-premium`: Primary action button (Blue gradient, rounded pills).
*   `.btn-premium-danger`: Destructive action button (Red gradient, rounded pills).
*   `.glass-table`: Custom table styling (sticky headers, custom borders) used in Scoreboards.
*   `.hide-inner-scroll`: Utility to hide the ugly scrollbar on scrollable divs (like modals) across all browsers.
*   `.hud-badge`: Small, dark semi-transparent badges used on the game screen (Trump indicator, Bowni alert).
*   `.player-pod`: The container for player names on the table. Glows yellow (`.active-turn`) when it is their turn.

**4. Responsive Design & Layout Rules:**
*   **Preventing Text Selection:** The body uses `user-select: none;` to stop double-taps from highlighting text on mobile.
*   **Card Layout:** Cards use viewport units (`vw`, `vmin`) to scale perfectly across screens.
*   **Orientation Media Queries:**
    *   `@media screen and (orientation: portrait)`: Stacks side-pods vertically (`writing-mode: vertical-rl`).
    *   `@media screen and (orientation: landscape) and (max-height: 600px)`: Squishes the table, hides vertical scroll on modals, anchors the "Me" pod securely to the bottom.
    *   `@media (min-width: 768px) and (orientation: landscape)`: Standard Desktop/Tablet view.

**Strict AI Instruction:** If asked to build a new UI element, construct it using the existing `.glass-panel` or `.glass-inner` structures. Do not invent new color schemes or overwrite the `.playing-card` animations.