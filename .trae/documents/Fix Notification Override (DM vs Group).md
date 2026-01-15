I will fix the notification prioritization issue where an old group message overrides a new direct message (DM) on the Dynamic Island.

**Diagnosis:**
The log shows that a new DM ("A Canmana") arrives, followed immediately (150ms later) by a "Group Summary" update ("Hatay...") from WhatsApp. Since both share the same notification key/ID in WhatsApp's internal handling, the system treats the "Hatay" update as the latest state and overwrites the DM.

**Fix Strategy:**
I will implement a protection mechanism that prevents a "Group" notification from overwriting a recently shown "Direct Message" (DM) notification if they occur within a short time window (3 seconds).

**Implementation Steps:**
1.  **Update `ActiveIsland` Model:**
    *   Modify `com/coni/hyperisle/models/ActiveIslands.kt` to include an `isGroup` boolean field. This allows tracking whether the currently displayed island is a group chat or a DM.

2.  **Update `NotificationReaderService` Logic:**
    *   In `processAndPost`, extract the `isGroup` status from the notification extras (`EXTRA_IS_GROUP_CONVERSATION` or `EXTRA_CONVERSATION_TITLE`).
    *   Add a logic check before updating an existing island:
        *   If the *current* island is a DM (`!isGroup`) and the *new* notification is a Group (`isGroup`), AND the current island was shown less than 3 seconds ago: **SKIP** the update.
    *   Update `ActiveIsland` constructor calls to store the `isGroup` status.

This ensures that when "A Canmana" (DM) is shown, the subsequent "Hatay" (Group) update is ignored, keeping the relevant message visible.