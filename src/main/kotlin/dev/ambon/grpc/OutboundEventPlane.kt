package dev.ambon.grpc

import dev.ambon.engine.events.OutboundEvent

/**
 * Classifies outbound events for overload policy.
 *
 * Control-plane events are delivery-critical and should use bounded retries/fail-closed behavior.
 * Data-plane events are best-effort and may be dropped under pressure.
 */
fun OutboundEvent.isControlPlane(): Boolean =
    when (this) {
        is OutboundEvent.Close,
        is OutboundEvent.ShowLoginScreen,
        is OutboundEvent.SetAnsi,
        is OutboundEvent.ClearScreen,
        is OutboundEvent.SessionRedirect,
        -> true
        is OutboundEvent.GmcpData,
        is OutboundEvent.SendText,
        is OutboundEvent.SendInfo,
        is OutboundEvent.SendError,
        is OutboundEvent.SendPrompt,
        is OutboundEvent.ShowAnsiDemo,
        -> false
    }
