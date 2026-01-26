package dev.ambon.engine.auth

import dev.ambon.persistence.PlayerId

sealed interface ConnState

data object Unauthed : ConnState

data object Menu : ConnState

data object LoginUsername : ConnState

data class LoginPassword(
    val username: String,
) : ConnState

data object SignupUsername : ConnState

data class SignupPassword(
    val username: String,
) : ConnState

data class SignupPasswordConfirm(
    val username: String,
    val pass1: String,
) : ConnState

data class Authed(
    val playerId: PlayerId,
) : ConnState
