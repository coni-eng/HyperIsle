package com.coni.hyperisle.overlay

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow



/**
 * Singleton event bus for overlay events.
 * Uses MutableSharedFlow to emit events from NotificationReaderService
 * and collect them in IslandOverlayService.
 */
object OverlayEventBus {

    private val _events = MutableSharedFlow<OverlayEvent>(
        replay = 1,
        extraBufferCapacity = 10
    )

    /**
     * Flow of overlay events for collectors (IslandOverlayService).
     */
    val events: SharedFlow<OverlayEvent> = _events.asSharedFlow()

    /**
     * Emit an overlay event. Call this from NotificationReaderService.
     * This is a suspend function but uses tryEmit internally for non-blocking emission.
     */
    suspend fun emit(event: OverlayEvent) {
        _events.emit(event)
    }

    /**
     * Try to emit an event without suspending.
     * Returns true if the event was emitted, false if the buffer is full.
     */
    fun tryEmit(event: OverlayEvent): Boolean {
        return _events.tryEmit(event)
    }

    /**
     * Emit a call event.
     */
    fun emitCall(model: IosCallOverlayModel): Boolean {
        return tryEmit(OverlayEvent.CallEvent(model))
    }

    /**
     * Emit a notification event.
     */
    fun emitNotification(model: IosNotificationOverlayModel): Boolean {
        return tryEmit(OverlayEvent.NotificationEvent(model))
    }

    /**
     * Emit a media event.
     */
    fun emitMedia(model: MediaOverlayModel): Boolean {
        return tryEmit(OverlayEvent.MediaEvent(model))
    }

    /**
     * Emit a timer event.
     */
    fun emitTimer(model: TimerOverlayModel): Boolean {
        return tryEmit(OverlayEvent.TimerEvent(model))
    }

    /**
     * Emit a navigation event.
     */
    fun emitNavigation(model: NavigationOverlayModel): Boolean {
        return tryEmit(OverlayEvent.NavigationEvent(model))
    }

    /**
     * Emit a dismiss event for a specific notification.
     */
    fun emitDismiss(notificationKey: String? = null): Boolean {
        return tryEmit(OverlayEvent.DismissEvent(notificationKey))
    }

    /**
     * Emit a dismiss all event.
     */
    fun emitDismissAll(): Boolean {
        return tryEmit(OverlayEvent.DismissAllEvent)
    }
}
