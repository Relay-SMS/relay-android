package com.filestech.sms.sync.calls

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A controllable [CallStateObserver]: tests emit events into it by hand. */
internal class FakeCallStateObserver : CallStateObserver {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    var startCount = 0
        private set
    var stopCount = 0
        private set

    val started: Boolean
        get() = startCount > stopCount

    /** Emits one event to whoever is collecting (the server's broadcast job). */
    fun emit(event: CallEvent) {
        _events.tryEmit(event)
    }

    override fun start() {
        startCount++
    }

    override fun stop() {
        stopCount++
    }
}
