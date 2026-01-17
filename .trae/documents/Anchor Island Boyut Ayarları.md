# Adjust Anchor Island Dimensions

Based on your feedback, the island with `30dp` height and `44dp` width was too small/tight around the camera. I will increase these dimensions slightly to provide a better fit that comfortably surrounds the camera cutout without being too bulky.

## Proposed Changes
I will modify `AnchorPill.kt` with the following values:

1.  **Height (`pillHeight`):** Increase from `30.dp` to **`34.dp`**.
    *   *Reason:* This provides enough vertical clearance to cover the camera cutout completely while staying more compact than the original `37.dp`.
2.  **Minimum Width (`slotMinWidth`):** Increase from `44.dp` to **`48.dp`**.
    *   *Reason:* This ensures the island has enough width to frame the camera symmetrically and hold content, without looking cramped.
3.  **Gap (`cutoutGapWidth`):** Keep as `cutoutInfo.width + 12.dp`.
    *   *Reason:* This maintains a safe buffer around the physical camera hole.

## Target File
`c:\Users\bekir\HyperIsle\app\src\main\java\com\coni\hyperisle\overlay\anchor\AnchorPill.kt`

This should result in an island that is "slightly larger" than your test values, appearing perfectly centered and sized around the camera.
