## [Unreleased]


## [v0.9.2] - Action Diagnostics & Safety Hardening

### Added

- **Debug Action Diagnostics (debug-only):**
  - Toggle in Smart Features → Debug section.
  - In-memory counters for action routing (Activity / Broadcast / Service / fallback).
  - Ring buffer for last action routing events (PII-safe).
  - One-tap “Copy diagnostics summary” for field debugging.

### Improved

- **PendingIntent handling:**
  - Best-effort inference of intent type (Activity / Broadcast / Service).
  - Defensive fallback to Activity to avoid crashes or broken actions.
  - Full safety guards around OEM- or ROM-specific edge cases.

- **Focus action handling:**
  - Centralized focus action string building and parsing.
  - Hardened parsing with safe early-return on invalid IDs.
  - Prevents accidental wrong-notification operations.

- **Action translation robustness:**
  - Conservative deduplication of visually identical actions.
  - Preserves order and behavior unless actions are clearly duplicates.

### Notes

- All diagnostics are **debug-only** and **disabled by default**.
- No notification content (title/text) is logged.
- No impact on release performance or behavior.





## [v0.9.1] - Summary Digest (Planned Hotfix)

**Not yet implemented. Planned improvements:**

1) Navigation / entrypoint
- Ensure Settings screen has a clear entry "Notification Summary" that opens NotificationSummaryScreenV2.kt.
- If navigation graph exists, add route and hook from settings list item.
- If using simple Compose navigation in MainActivity, add route there.

2) Digest recording for suppressed notifications
- In NotificationReaderService.kt, identify all early-return suppression points:
  - Cooldown deny
  - Focus deny
  - Context deny
  - Priority deny (burst/throttle)
  - Spoiler protection deny


## [v0.9.0] - Context Presets & Summary Upgrades

### Added

- **Context Presets:** Quick notification filters for different scenarios
  - OFF: No filtering (default)
  - MEETING: Only calls, timers, and navigation
  - DRIVING: Only calls, timers, and navigation
  - HEADPHONES: Block calls, enjoy content
  - Focus Mode is the strongest override (presets do not weaken Focus rules)
  - Applied to non-media notifications only

- **Summary V2 Upgrades:**
  - Time-bucket grouping in UI (Morning / Afternoon / Evening)
  - Top 3 apps by notification count (last 24 hours)
  - Inline actions in summary list: mute/block app (reuses existing Quick Actions logic)

### Changed

- SmartFeaturesScreen now includes Context Presets selector with segmented buttons
- NotificationReaderService applies preset rules before Focus mode check


## [v0.8.0] - POLISHES

1) Live Activity final polish:
- minVisibleMs (default 700ms)
- same-content dedupe window (default 1500ms)
2) Smart Priority learning improvements:
- weighted decay across last 3 days (1.0/0.6/0.3)
- quiet-hours bias (22:00–07:00): stronger short-term throttle, weaker long-term penalty
- per-type multipliers (CALL/TIMER/NAV lenient, STANDARD stricter)
- Decision reasonCodes list for debugging (in-memory only)



\## \[v0.7.0] - CONTEXT-AWARE ISLANDS

\### Added

\- Context-Aware Islands support (screen-off filtering for important notification types).

\- Battery-charging suppression for HyperIsle battery banners.

\- ContextSignalsReceiver for screen and power broadcasts.

\- Settings UI for context-aware options.

\- Persistent context state via AppPreferences.



\### Changed

\- NotificationReaderService respects context before posting islands.

\- Smart Features updated with context toggles and descriptions.



---



\## \[v0.6.0] - SMART PRIORITY ENGINE

\### Added

\- PriorityEngine for spam detection and throttling.

\- Smart Priority settings + aggressiveness slider.

\- Auto-throttle option in Quick Actions per app.

\- Dismiss counters integrated with PriorityEngine.



\### Changed

\- NotificationReaderService consults PriorityEngine before posting.

\- IslandActionReceiver increments dismiss counters.



---



\## \[v0.5.0] - LIVE ACTIVITY \& ADAPTIVE VISUALS

\### Added

\- Live Activity state machine (CREATED → UPDATED → COMPLETED).

\- IslandActivityStateMachine to manage island lifecycle.

\- Adaptive accent color extraction from app icons.

\- Progress smoothing and completion visuals + haptics.



\### Changed

\- ProgressTranslator improved for smoother updates.

\- StandardTranslator integrates adaptive visuals.



---



\## \[Unreleased Before v0.5.0]

\- Music Island:

&nbsp; - SYSTEM\_ONLY (default): Uses HyperOS native music island.

&nbsp; - BLOCK\_SYSTEM (advanced): Cancels MediaStyle notifications for selected apps.

\- System states (DND / Silent):

&nbsp; - App islands disabled when HyperOS native behavior is preferred.

