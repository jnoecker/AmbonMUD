package dev.ambon.grpc

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.grpc.proto.ClearScreenProto
import dev.ambon.grpc.proto.CloseProto
import dev.ambon.grpc.proto.ConnectedProto
import dev.ambon.grpc.proto.DisconnectedProto
import dev.ambon.grpc.proto.GmcpDataProto
import dev.ambon.grpc.proto.GmcpReceivedProto
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

/** Converts domain [InboundEvent] to [InboundEventProto]. */
fun InboundEvent.toProto(): InboundEventProto =
    when (this) {
        is InboundEvent.Connected -> inboundProto(sessionId) { setConnected(toConnectedProto()) }
        is InboundEvent.Disconnected -> inboundProto(sessionId) { setDisconnected(toDisconnectedProto()) }
        is InboundEvent.LineReceived -> inboundProto(sessionId) { setLineReceived(toLineReceivedProto()) }
        is InboundEvent.GmcpReceived -> inboundProto(sessionId) { setGmcpReceived(toGmcpReceivedProto()) }
    }

/** Converts [InboundEventProto] to domain [InboundEvent], or null if the oneof is not set. */
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
        InboundEventProto.EventCase.GMCP_RECEIVED ->
            InboundEvent.GmcpReceived(
                sessionId = sid,
                gmcpPackage = gmcpReceived.gmcpPackage,
                jsonData = gmcpReceived.jsonData,
            )
        else -> null
    }
}

/** Converts domain [OutboundEvent] to [OutboundEventProto]. */
fun OutboundEvent.toProto(): OutboundEventProto =
    when (this) {
        is OutboundEvent.SendText -> outboundProto(sessionId) { setSendText(toSendTextProto()) }
        is OutboundEvent.SendInfo -> outboundProto(sessionId) { setSendInfo(toSendInfoProto()) }
        is OutboundEvent.SendError -> outboundProto(sessionId) { setSendError(toSendErrorProto()) }
        is OutboundEvent.SendPrompt -> outboundProto(sessionId) { setSendPrompt(SendPromptProto.getDefaultInstance()) }
        is OutboundEvent.ShowLoginScreen ->
            outboundProto(sessionId) { setShowLoginScreen(ShowLoginScreenProto.getDefaultInstance()) }
        is OutboundEvent.SetAnsi -> outboundProto(sessionId) { setSetAnsi(toSetAnsiProto()) }
        is OutboundEvent.Close -> outboundProto(sessionId) { setClose(toCloseProto()) }
        is OutboundEvent.ClearScreen -> outboundProto(sessionId) { setClearScreen(ClearScreenProto.getDefaultInstance()) }
        is OutboundEvent.ShowAnsiDemo -> outboundProto(sessionId) { setShowAnsiDemo(ShowAnsiDemoProto.getDefaultInstance()) }
        is OutboundEvent.SessionRedirect -> outboundProto(sessionId) { setSessionRedirect(toSessionRedirectProto()) }
        is OutboundEvent.GmcpData -> outboundProto(sessionId) { setGmcpData(toGmcpDataProto()) }
    }

/** Converts [OutboundEventProto] to domain [OutboundEvent], or null if the oneof is not set. */
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
        OutboundEventProto.EventCase.GMCP_DATA ->
            OutboundEvent.GmcpData(
                sessionId = sid,
                gmcpPackage = gmcpData.gmcpPackage,
                jsonData = gmcpData.jsonData,
            )
        else -> null
    }
}

private fun inboundProto(
    sessionId: SessionId,
    block: InboundEventProto.Builder.() -> Unit,
): InboundEventProto =
    InboundEventProto
        .newBuilder()
        .setSessionId(sessionId.value)
        .apply(block)
        .build()

private fun outboundProto(
    sessionId: SessionId,
    block: OutboundEventProto.Builder.() -> Unit,
): OutboundEventProto =
    OutboundEventProto
        .newBuilder()
        .setSessionId(sessionId.value)
        .apply(block)
        .build()

private fun InboundEvent.Connected.toConnectedProto(): ConnectedProto =
    ConnectedProto
        .newBuilder()
        .setDefaultAnsiEnabled(defaultAnsiEnabled)
        .build()

private fun InboundEvent.Disconnected.toDisconnectedProto(): DisconnectedProto =
    DisconnectedProto
        .newBuilder()
        .setReason(reason)
        .build()

private fun InboundEvent.LineReceived.toLineReceivedProto(): LineReceivedProto =
    LineReceivedProto
        .newBuilder()
        .setLine(line)
        .build()

private fun InboundEvent.GmcpReceived.toGmcpReceivedProto(): GmcpReceivedProto =
    GmcpReceivedProto
        .newBuilder()
        .setGmcpPackage(gmcpPackage)
        .setJsonData(jsonData)
        .build()

private fun OutboundEvent.SendText.toSendTextProto(): SendTextProto =
    SendTextProto
        .newBuilder()
        .setText(text)
        .build()

private fun OutboundEvent.SendInfo.toSendInfoProto(): SendInfoProto =
    SendInfoProto
        .newBuilder()
        .setText(text)
        .build()

private fun OutboundEvent.SendError.toSendErrorProto(): SendErrorProto =
    SendErrorProto
        .newBuilder()
        .setText(text)
        .build()

private fun OutboundEvent.SetAnsi.toSetAnsiProto(): SetAnsiProto =
    SetAnsiProto
        .newBuilder()
        .setEnabled(enabled)
        .build()

private fun OutboundEvent.Close.toCloseProto(): CloseProto =
    CloseProto
        .newBuilder()
        .setReason(reason)
        .build()

private fun OutboundEvent.SessionRedirect.toSessionRedirectProto(): SessionRedirectProto =
    SessionRedirectProto
        .newBuilder()
        .setNewEngineId(newEngineId)
        .setNewEngineHost(newEngineHost)
        .setNewEnginePort(newEnginePort)
        .build()

private fun OutboundEvent.GmcpData.toGmcpDataProto(): GmcpDataProto =
    GmcpDataProto
        .newBuilder()
        .setGmcpPackage(gmcpPackage)
        .setJsonData(jsonData)
        .build()
