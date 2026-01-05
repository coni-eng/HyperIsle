\## \[Unreleased]



\## \[v0.8.0] - POLISHES

A) Live Activity final polish:
- minVisibleMs (default 700ms)
- same-content dedupe window (default 1500ms)
B) Smart Priority learning improvements:
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

