package dev.ambon.engine

import dev.ambon.config.CurrenciesConfig
import dev.ambon.config.CurrencyDefinitionConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Manages secondary currencies (quest points, honor, crafting tokens, etc.).
 *
 * Currency definitions come from config. Balances are stored on [PlayerState.currencies]
 * and persisted via the normal player-record pipeline.
 */
class CurrencySystem(
    private val config: CurrenciesConfig,
) {
    fun definitions(): Map<String, CurrencyDefinitionConfig> = config.definitions

    fun getDefinition(currencyId: String): CurrencyDefinitionConfig? =
        config.definitions[currencyId]

    /** Returns the player's balance for a given currency (0 if unset). */
    fun balance(player: PlayerState, currencyId: String): Long =
        player.currencies.getOrDefault(currencyId, 0L)

    /** Returns all currency balances for a player, including zero entries for defined currencies. */
    fun allBalances(player: PlayerState): Map<String, Long> =
        config.definitions.keys.associateWith { balance(player, it) }

    /**
     * Awards [amount] of [currencyId] to the player.
     * Returns `true` if the amount was credited. Returns `false` — without
     * mutating anything — if the currency is not defined or amount is not
     * positive, so callers can suppress player-facing award announcements.
     */
    fun award(player: PlayerState, currencyId: String, amount: Long): Boolean {
        if (amount <= 0) return false
        if (currencyId !in config.definitions) {
            log.warn { "Attempted to award unknown currency '$currencyId' to ${player.name}" }
            return false
        }
        val current = player.currencies.getOrDefault(currencyId, 0L)
        player.currencies[currencyId] = current + amount
        log.debug { "Currency awarded: ${player.name} +$amount $currencyId (new balance: ${current + amount})" }
        return true
    }

    /**
     * Attempts to spend [amount] of [currencyId] from the player.
     * Returns `true` if the player had sufficient balance and the amount was deducted.
     * Returns `false` if the currency is not defined, amount is not positive, or
     * the player has insufficient balance.
     */
    fun spend(player: PlayerState, currencyId: String, amount: Long): Boolean {
        if (amount <= 0) return false
        if (currencyId !in config.definitions) return false
        val current = player.currencies.getOrDefault(currencyId, 0L)
        if (current < amount) return false
        player.currencies[currencyId] = current - amount
        log.debug { "Currency spent: ${player.name} -$amount $currencyId (new balance: ${current - amount})" }
        return true
    }

    /** Honor points to award per PvP kill, from config. */
    val honorPerPvpKill: Long get() = config.honorPerPvpKill

    /** Crafting tokens to award per successful craft, from config. */
    val tokensPerCraft: Long get() = config.tokensPerCraft
}
