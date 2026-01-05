\## Unreleased



\- Music Island:

&nbsp; - SYSTEM\_ONLY (default): Uses HyperOS native music island only.

&nbsp; - BLOCK\_SYSTEM (advanced): Suppresses HyperOS music island by cancelling MediaStyle notifications for selected apps (may remove lockscreen controls).



\- System states (DND / Silent):

&nbsp; - App-generated islands are disabled by default when meaningful customization is not possible.

&nbsp; - HyperOS native behavior is preferred.



\- iOS-like Smart UX:

&nbsp; - Smart Silence: Suppresses repeated islands within a configurable time window.

&nbsp; - Focus Mode: Quiet-hours rules to reduce interruptions (override float/timeout; restrict allowed types).

&nbsp; - Notification Summary: Optional daily digest notification.

&nbsp; - Haptics: Light “shown” feedback and distinct “success” feedback on dismiss.

&nbsp; - Standard Actions + Cooldown:

&nbsp;   - Tap opens source app (fallback to App Info if needed).

&nbsp;   - Options opens Quick Actions (mute/block per app).

&nbsp;   - Dismiss closes the correct island and starts cooldown for that app/type.

&nbsp;   - Fix: Dismiss is now per-island safe using unique action keys + explicit receiver binding (prevents wrong-island dismiss). :contentReference\[oaicite:1]{index=1}



\- System Banners (optional):

&nbsp; - AirPods-style short banners for Bluetooth connected and Battery low (default OFF to avoid duplicating HyperOS native UI).



