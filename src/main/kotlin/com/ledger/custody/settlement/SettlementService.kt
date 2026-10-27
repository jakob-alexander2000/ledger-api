package com.ledger.custody.settlement

import com.ledger.custody.account.Account
import com.ledger.custody.account.AccountRepository
import com.ledger.custody.account.AccountStatus
import com.ledger.custody.ledger.Direction
import com.ledger.custody.ledger.LedgerRepository
import com.ledger.custody.ledger.Position
import com.ledger.custody.persistence.required
import com.ledger.custody.shared.BalanceLimitException
import com.ledger.custody.shared.IdempotencyConflictException
import com.ledger.custody.shared.InsufficientFundsException
import com.ledger.custody.shared.SettlementRejectedException
import com.ledger.custody.shared.UnknownAccountException
import com.ledger.custody.shared.UnknownSettlementException
import com.ledger.custody.shared.now
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * Posts one settlement instruction.
 *
 * Account rows are locked in id order before the idempotency key is read, so two
 * movements on the same accounts cannot both pass the balance check. The instruction
 * is committed as settled or rejected. Ledger rows are written only when it settles.
 */
@Service
class SettlementService(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val settlements: SettlementRepository,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SettlementService::class.java)

    fun instruct(command: InstructSettlement, rawIdempotencyKey: String?): SettlementOutcome {
        val key = IdempotencyKey.parse(rawIdempotencyKey)
        val hash = command.fingerprint()
        val outcome = try {
            tx.required { postLocked(command, key, hash) }
        } catch (ex: DuplicateKeyException) {
            replayCommitted(key, hash)
        }
        if (outcome.instruction.status == SettlementStatus.REJECTED) {
            log.info(
                "settlement.rejected instructionId={} type={} reason={} amountMinor={} currency={} replayed={}",
                outcome.instruction.id,
                outcome.instruction.type,
                outcome.instruction.rejectionReason,
                outcome.instruction.money.minorUnits,
                outcome.instruction.money.currency,
                outcome.replayed,
            )
            throw SettlementRejectedException(outcome.instruction, outcome.replayed)
        }
        log.info(
            "settlement.settled instructionId={} type={} amountMinor={} currency={} replayed={}",
            outcome.instruction.id,
            outcome.instruction.type,
            outcome.instruction.money.minorUnits,
            outcome.instruction.money.currency,
            outcome.replayed,
        )
        return outcome
    }

    fun get(id: UUID): SettlementOutcome {
        val instruction = settlements.findById(id) ?: throw UnknownSettlementException(id)
        return SettlementOutcome(instruction, settlements.findEvents(id), replayed = false)
    }

    private fun postLocked(command: InstructSettlement, key: String, hash: String): SettlementOutcome {
        val lockedAccounts = LinkedHashMap<UUID, Account>()
        val lockedPositions = LinkedHashMap<UUID, Position>()
        for (accountId in command.accountIds()) {
            lockedAccounts[accountId] = accounts.lockById(accountId) ?: throw UnknownAccountException(accountId)
            lockedPositions[accountId] = ledger.lockPosition(accountId)
                ?: error("Account $accountId has no position")
        }

        val existing = settlements.findByIdempotencyKey(key)
        if (existing != null) {
            return existingOutcome(existing, hash)
        }

        val now = clock.now()
        val received = SettlementInstruction.receive(UUID.randomUUID(), key, hash, command, now)
        val reason = rejectionOf(received, lockedAccounts, lockedPositions)
        val finished = if (reason == null) received.settle(now) else received.reject(reason, now)
        val events = listOf(
            SettlementStatusEvent(received.id, 1, SettlementStatus.RECEIVED, null, received.createdAt),
            SettlementStatusEvent(finished.id, 2, finished.status, finished.rejectionReason, now),
        )
        settlements.insert(finished)
        settlements.appendEvents(events)
        if (finished.status == SettlementStatus.SETTLED) {
            ledger.append(finished.entries(now))
            for (leg in finished.legs()) {
                val updated = lockedPositions.getValue(leg.accountId)
                    .apply(leg.direction, finished.money.minorUnits, now)
                ledger.updatePosition(updated)
            }
        }
        return SettlementOutcome(finished, events, replayed = false)
    }

    private fun rejectionOf(
        instruction: SettlementInstruction,
        lockedAccounts: Map<UUID, Account>,
        lockedPositions: Map<UUID, Position>,
    ): RejectionReason? {
        for (leg in instruction.legs()) {
            val account = lockedAccounts.getValue(leg.accountId)
            if (account.status != AccountStatus.OPEN) {
                return RejectionReason.ACCOUNT_NOT_OPEN
            }
            if (account.currency != instruction.money.currency) {
                return RejectionReason.CURRENCY_MISMATCH
            }
        }
        for (leg in instruction.legs()) {
            val position = lockedPositions.getValue(leg.accountId)
            try {
                position.apply(leg.direction, instruction.money.minorUnits, instruction.createdAt)
            } catch (_: InsufficientFundsException) {
                return RejectionReason.INSUFFICIENT_FUNDS
            } catch (_: BalanceLimitException) {
                return RejectionReason.BALANCE_LIMIT
            }
        }
        return null
    }

    private fun replayCommitted(key: String, hash: String): SettlementOutcome {
        val existing = settlements.findByIdempotencyKey(key) ?: throw IllegalStateException(
            "Idempotency key collided but no instruction was stored",
        )
        return existingOutcome(existing, hash)
    }

    private fun existingOutcome(existing: SettlementInstruction, hash: String): SettlementOutcome {
        if (existing.requestHash != hash) {
            throw IdempotencyConflictException()
        }
        return SettlementOutcome(existing, settlements.findEvents(existing.id), replayed = true)
    }
}
