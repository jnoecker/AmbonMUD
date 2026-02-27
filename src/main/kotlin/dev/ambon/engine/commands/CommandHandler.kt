package dev.ambon.engine.commands

/** Marks a class that registers one or more command handlers with a [CommandRouter]. */
interface CommandHandler {
    fun register(router: CommandRouter)
}
