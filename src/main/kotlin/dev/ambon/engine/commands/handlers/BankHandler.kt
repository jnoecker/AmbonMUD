package dev.ambon.engine.commands.handlers

import dev.ambon.config.BankConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class BankHandler(
    ctx: EngineContext,
    private val bankConfig: BankConfig = BankConfig(),
    private val markVitalsDirty: ((SessionId) -> Unit)? = null,
) : CommandHandler {
    private val players = ctx.players
    private val world = ctx.world
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val metrics = ctx.metrics

    override fun register(router: CommandRouter) {
        router.on<Command.Bank.DepositGold> { sid, cmd -> handleDepositGold(sid, cmd) }
        router.on<Command.Bank.DepositItem> { sid, cmd -> handleDepositItem(sid, cmd) }
        router.on<Command.Bank.WithdrawGold> { sid, cmd -> handleWithdrawGold(sid, cmd) }
        router.on<Command.Bank.WithdrawItem> { sid, cmd -> handleWithdrawItem(sid, cmd) }
        router.on<Command.Bank.Balance> { sid, _ -> handleBalance(sid) }
    }

    private suspend fun requireBank(sessionId: SessionId, me: dev.ambon.engine.PlayerState): Boolean =
        requireRoomFlag(sessionId, me, world, outbound, "You need to be at a bank to do that.") { it.bank }

    private suspend fun handleDepositGold(sessionId: SessionId, cmd: Command.Bank.DepositGold) {
        val me = players.get(sessionId) ?: return
        if (!requireBank(sessionId, me)) return

        val amount = if (cmd.amount == Long.MAX_VALUE) me.gold else cmd.amount
        if (amount <= 0) {
            outbound.send(OutboundEvent.SendError(sessionId, "Deposit how much?"))
            return
        }
        if (me.gold < amount) {
            outbound.send(OutboundEvent.SendError(sessionId, "You only have ${me.gold} gold."))
            return
        }
        me.gold -= amount
        me.bankGold += amount
        markVitalsDirty?.invoke(sessionId)
        metrics.onGameEvent("bank", "deposit_gold")
        outbound.send(OutboundEvent.SendInfo(sessionId, "You deposit $amount gold. Bank balance: ${me.bankGold} gold."))
        emitBankState(sessionId, me)
    }

    private suspend fun handleWithdrawGold(sessionId: SessionId, cmd: Command.Bank.WithdrawGold) {
        val me = players.get(sessionId) ?: return
        if (!requireBank(sessionId, me)) return

        val amount = if (cmd.amount == Long.MAX_VALUE) me.bankGold else cmd.amount
        if (amount <= 0) {
            outbound.send(OutboundEvent.SendError(sessionId, "Withdraw how much?"))
            return
        }
        if (me.bankGold < amount) {
            outbound.send(OutboundEvent.SendError(sessionId, "Your bank only has ${me.bankGold} gold."))
            return
        }
        me.bankGold -= amount
        me.gold += amount
        markVitalsDirty?.invoke(sessionId)
        metrics.onGameEvent("bank", "withdraw_gold")
        outbound.send(OutboundEvent.SendInfo(sessionId, "You withdraw $amount gold. Bank balance: ${me.bankGold} gold."))
        emitBankState(sessionId, me)
    }

    private suspend fun handleDepositItem(sessionId: SessionId, cmd: Command.Bank.DepositItem) {
        val me = players.get(sessionId) ?: return
        if (!requireBank(sessionId, me)) return

        if (me.bankItems.size >= bankConfig.maxItems) {
            outbound.send(OutboundEvent.SendError(sessionId, "Your bank vault is full (${bankConfig.maxItems} items)."))
            return
        }

        val removed = items.removeFromInventory(sessionId, cmd.keyword)
        if (removed == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't have '${cmd.keyword}'."))
            return
        }

        me.bankItems.add(removed)
        metrics.onGameEvent("bank", "deposit_item")
        outbound.send(OutboundEvent.SendInfo(sessionId, "You deposit ${removed.item.displayName} into your bank vault."))
        syncItemsGmcp(sessionId, items, gmcpEmitter)
        emitBankState(sessionId, me)
    }

    private suspend fun handleWithdrawItem(sessionId: SessionId, cmd: Command.Bank.WithdrawItem) {
        val me = players.get(sessionId) ?: return
        if (!requireBank(sessionId, me)) return

        val idx = me.bankItems.indexOfFirst {
            it.item.keyword.equals(cmd.keyword, ignoreCase = true) ||
                (cmd.keyword.length >= 3 && it.item.displayName.contains(cmd.keyword, ignoreCase = true))
        }
        if (idx < 0) {
            outbound.send(OutboundEvent.SendError(sessionId, "Your bank vault doesn't contain '${cmd.keyword}'."))
            return
        }

        val withdrawn = me.bankItems.removeAt(idx)
        items.addToInventory(sessionId, withdrawn)
        metrics.onGameEvent("bank", "withdraw_item")
        outbound.send(OutboundEvent.SendInfo(sessionId, "You withdraw ${withdrawn.item.displayName} from your bank vault."))
        syncItemsGmcp(sessionId, items, gmcpEmitter)
        emitBankState(sessionId, me)
    }

    private suspend fun handleBalance(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Bank Balance ]"))
        outbound.send(OutboundEvent.SendInfo(sessionId, "  Gold: ${me.bankGold}"))
        if (me.bankItems.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Vault: empty"))
        } else {
            val list = me.bankItems.map { it.item.displayName }.sorted().joinToString(", ")
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  Vault (${me.bankItems.size}/${bankConfig.maxItems}): $list",
                ),
            )
        }
    }

    private suspend fun emitBankState(sessionId: SessionId, me: dev.ambon.engine.PlayerState) {
        gmcpEmitter?.sendBankState(
            sessionId,
            gold = me.bankGold,
            items = me.bankItems,
            maxItems = bankConfig.maxItems,
        )
    }
}
