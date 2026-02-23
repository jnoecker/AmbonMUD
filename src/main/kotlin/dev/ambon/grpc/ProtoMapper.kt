package dev.ambon.grpc

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.grpc.proto.ClearScreenProto
import dev.ambon.grpc.proto.CloseProto
import dev.ambon.grpc.proto.ConnectedProto
import dev.ambon.grpc.proto.DisconnectedProto
import dev.ambon.grpc.proto.InboundEventProto
import dev.ambon.grpc.proto.LineReceivedProto
import dev.ambon.grpc.proto.OutboundEventProto
import dev.ambon.grpc.proto.SendErrorProto
import dev.ambon.grpc.proto.SendInfoProto
import dev.ambon.grpc.proto.SendPromptProto
import dev.ambon.grpc.proto.SendTextProto
import dev.ambon.grpc.proto.SessionRedirectProto
import dev.ambon.grpc.proto.SetAnsiProto
import dev.ambon.grpc.proto.ShowAnsiDemoProto
import dev.ambon.grpc.proto.ShowLoginScreenProto

/** Converts domain [InboundEvent] → [InboundEventProto]. */
fun InboundEvent.toProto(): InboundEventProto =
    when (this) {
        is InboundEvent.Connected ->
            InboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setConnected(
                    ConnectedProto
                        .newBuilder()
                        .setDefaultAnsiEnabled(defaultAnsiEnabled)
                        .build(),
                ).build()
        is InboundEvent.Disconnected ->
            InboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setDisconnected(
                    DisconnectedProto
                        .newBuilder()
                        .setReason(reason)
                        .build(),
                ).build()
        is InboundEvent.LineReceived ->
            InboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setLineReceived(
                    LineReceivedProto
                        .newBuilder()
                        .setLine(line)
                        .build(),
                ).build()
    }

/** Converts [InboundEventProto] → domain [InboundEvent], or null if the oneof is not set. */
fun InboundEventProto.toDomain(): InboundEvent? {
    val sid = SessionId(sessionId)
    return when (eventCase) {
        InboundEventProto.EventCase.CONNECTED ->
            InboundEvent.Connected(
                sessionId = sid,
                defaultAnsiEnabled = connected.defaultAnsiEnabled,
            )
        InboundEventProto.EventCase.DISCONNECTED ->
            InboundEvent.Disconnected(
                sessionId = sid,
                reason = disconnected.reason,
            )
        InboundEventProto.EventCase.LINE_RECEIVED ->
            InboundEvent.LineReceived(
                sessionId = sid,
                line = lineReceived.line,
            )
        else -> null
    }
}

/** Converts domain [OutboundEvent] → [OutboundEventProto]. */
fun OutboundEvent.toProto(): OutboundEventProto =
    when (this) {
        is OutboundEvent.SendText ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setSendText(SendTextProto.newBuilder().setText(text).build())
                .build()
        is OutboundEvent.SendInfo ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setSendInfo(SendInfoProto.newBuilder().setText(text).build())
                .build()
        is OutboundEvent.SendError ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setSendError(SendErrorProto.newBuilder().setText(text).build())
                .build()
        is OutboundEvent.SendPrompt ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setSendPrompt(SendPromptProto.getDefaultInstance())
                .build()
        is OutboundEvent.ShowLoginScreen ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setShowLoginScreen(ShowLoginScreenProto.getDefaultInstance())
                .build()
        is OutboundEvent.SetAnsi ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setSetAnsi(SetAnsiProto.newBuilder().setEnabled(enabled).build())
                .build()
        is OutboundEvent.Close ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setClose(CloseProto.newBuilder().setReason(reason).build())
                .build()
        is OutboundEvent.ClearScreen ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setClearScreen(ClearScreenProto.getDefaultInstance())
                .build()
        is OutboundEvent.ShowAnsiDemo ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setShowAnsiDemo(ShowAnsiDemoProto.getDefaultInstance())
                .build()
        is OutboundEvent.SessionRedirect ->
            OutboundEventProto
                .newBuilder()
                .setSessionId(sessionId.value)
                .setSessionRedirect(
                    SessionRedirectProto
                        .newBuilder()
                        .setNewEngineId(newEngineId)
                        .setNewEngineHost(newEngineHost)
                        .setNewEnginePort(newEnginePort)
                        .build(),
                ).build()
    }

/** Converts [OutboundEventProto] → domain [OutboundEvent], or null if the oneof is not set. */
fun OutboundEventProto.toDomain(): OutboundEvent? {
    val sid = SessionId(sessionId)
    return when (eventCase) {
        OutboundEventProto.EventCase.SEND_TEXT ->
            OutboundEvent.SendText(sessionId = sid, text = sendText.text)
        OutboundEventProto.EventCase.SEND_INFO ->
            OutboundEvent.SendInfo(sessionId = sid, text = sendInfo.text)
        OutboundEventProto.EventCase.SEND_ERROR ->
            OutboundEvent.SendError(sessionId = sid, text = sendError.text)
        OutboundEventProto.EventCase.SEND_PROMPT ->
            OutboundEvent.SendPrompt(sessionId = sid)
        OutboundEventProto.EventCase.SHOW_LOGIN_SCREEN ->
            OutboundEvent.ShowLoginScreen(sessionId = sid)
        OutboundEventProto.EventCase.SET_ANSI ->
            OutboundEvent.SetAnsi(sessionId = sid, enabled = setAnsi.enabled)
        OutboundEventProto.EventCase.CLOSE ->
            OutboundEvent.Close(sessionId = sid, reason = close.reason)
        OutboundEventProto.EventCase.CLEAR_SCREEN ->
            OutboundEvent.ClearScreen(sessionId = sid)
        OutboundEventProto.EventCase.SHOW_ANSI_DEMO ->
            OutboundEvent.ShowAnsiDemo(sessionId = sid)
        OutboundEventProto.EventCase.SESSION_REDIRECT ->
            OutboundEvent.SessionRedirect(
                sessionId = sid,
                newEngineId = sessionRedirect.newEngineId,
                newEngineHost = sessionRedirect.newEngineHost,
                newEnginePort = sessionRedirect.newEnginePort,
            )
        else -> null
    }
}
