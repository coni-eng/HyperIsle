[Unreleased]

## [v0.9.6] - Permission Guidance & Setup Health
### Added
- Setup Health screen with required and recommended permission guidance.
- Setup incomplete banner that highlights missing required permissions.
- Debug/QA "Mark Issue" action to log a diagnostics timeline marker.
### Improved
- Locale-aware formatting for diagnostics sizes and timer durations.
- Shade-cancel hint posting now checks notification permission and handles security exceptions.

## [v0.9.5] - iOS Pill Overlay & Shade Cancel Clarity
### Added
- Optional iOS-style pill overlay alongside the system island (requires overlay permission).
- Dedicated notification channel for the overlay service.
- Per-app shade-cancel hint for persistent notifications that Android/MIUI cannot clear.

##[v0.9.4] — Debug Export & Smart Priority Transparency
Added

Diagnostics time range filtering:

Last 10 minutes, 30 minutes, 1 hour, 6 hours, 12 hours, and 24 hours.

Diagnostics export options (debug builds only):

Share as plain text

Save as .txt

Optional JSON format for structured debugging.

Per-app Smart Priority profiles:

Normal (default)

Lenient (allow more notifications)

Strict (reduce noise aggressively)

Improved

Smart Priority explainability:

Diagnostics now clearly indicate when a per-app profile affected a decision.

New reason tags for profile bias (e.g. STRICT / LENIENT).

Debug UX:

Clearer summaries with selected time range and build information.

Export output explicitly confirms that no notification content is included.

Security & Performance

All diagnostics features are debug-only and fully gated from release builds.

No notification titles or message text are ever logged or exported.

No background I/O or performance impact unless diagnostics are explicitly used.


## [v0.9.3] - Smart Priority Intelligence

### Added

- **Burst control:**
  - When multiple notifications arrive from the same app in a short time,
    only the latest one is shown as an island.
  - Suppressed notifications are still recorded in the Summary Digest.

- **Adaptive learning signals:**
  - Fast dismisses reduce future priority.
  - Tapping an island to open the app increases its priority.
  - Mute or block actions apply a strong negative signal.

- **Context-aware Smart Priority:**
  - During **MEETING** or **DRIVING** presets:
    - Calls, timers, and navigation are never throttled.
    - Standard notifications are filtered more aggressively.
  - Preset **OFF** keeps previous behavior unchanged.

### Improved

- **Performance and stability:**
  - Priority decisions now use a “cheap-first” evaluation order,
    reducing CPU usage under heavy notification load.
  - Learning signals are bounded with caps and decay to prevent runaway behavior.

### Debug

- **Priority decision diagnostics (debug-only):**
  - View and copy recent Smart Priority decisions and reason codes
    (e.g. BURST, FAST_DISMISS, TAP_OPEN, PRESET_BIAS).
  - Includes a debug-only option to reset Smart Priority learning state.

### Notes

- No notification content (title or text) is logged.
- No database schema changes.
- Release performance and behavior remain unaffected when debug tools are disabled.



## [v0.9.2] - Action Diagnostics & Debug UX
Added

Debug Action Diagnostics (debug-only):

Toggle in Smart Features → Debug section.

In-memory counters for action routing (Activity / Broadcast / Service / fallback).

Ring buffer for recent action routing events (PII-safe).

One-tap “Copy diagnostics summary” for field debugging.

Debug Action UX:

Optional long-press on island actions to display routing info
(Activity / Broadcast / Service).

Disabled by default and available only in debug builds.

Improved

PendingIntent handling:

Best-effort inference of intent type with defensive fallback.

Full safety guards for OEM- or ROM-specific edge cases.

Focus action handling:

Centralized focus action string creation and parsing.

Hardened parsing with safe early-return on invalid IDs.

Action translation robustness:

Conservative deduplication of visually identical actions.

No behavior changes unless actions are clearly duplicates.

Notes

All diagnostics are debug-only and disabled by default.

No notification content (title/text) is logged.

No impact on release performance or behavior.



## [v0.9.1] - Summary Digest Hardening
Added

Notification Summary navigation entrypoint:

Clear entry in Settings to open NotificationSummaryScreenV2.

Improved

Digest reliability for suppressed notifications:

Notifications suppressed before island rendering
(Focus, Context Presets, Cooldown, Priority throttling, spoiler protection)
are now reliably recorded in the Summary Digest.

Ensures notification history is complete even when islands are not shown.

Suppression-aware digest recording:

Digest insertion occurs before all early-return suppression points.

Duplicate digest entries are prevented.

Debug

Suppression diagnostics (debug-only):

When diagnostics are enabled, suppressed notifications recorded
into the digest emit a diagnostic entry with suppression reason.

Helps troubleshoot “missing notification” reports without logging content.

Notes

Media notifications are never recorded into the digest.

No database schema changes.

Normal (non-suppressed) notification behavior is unchanged.


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

