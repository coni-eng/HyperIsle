package com.coni.hyperisle.models



/**
 * Music Island behavior mode.
 * 
 * SYSTEM_ONLY: Let HyperOS handle music islands natively. HyperIsle does not generate any islands for MediaStyle.
 * BLOCK_SYSTEM: Cancel MediaStyle notifications from selected apps to suppress HyperOS native music island.
 *               WARNING: This removes the notification from shade/lockscreen.
 */
enum class MusicIslandMode {
    SYSTEM_ONLY,
    BLOCK_SYSTEM
}
