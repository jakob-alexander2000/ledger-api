package com.ledger.custody.settlement

import com.ledger.custody.ledger.Direction
import com.ledger.custody.ledger.LedgerEntry
import com.ledger.custody.money.Money
import com.ledger.custody.shared.InvalidIdempotencyKeyException
import com.ledger.custody.shared.InvalidSettlementException
import com.ledger.custody.shared.MissingIdempotencyKeyException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class InstructSettlement(
    val type: SettlementType,
    val debitAccountId: UUID?,
    val creditAccountId: UUID?,
    val money: Money,
    val externalReference: String?,
) {
    init {
        when (type) {
            SettlementType.DEPOSIT -> requireShape(
                creditAccountId != null && debitAccountId == null,
                "A deposit credits exactly one account",
            )
            SettlementType.WITHDRAWAL -> requireShape(
                debitAccountId != null && creditAccountId == null,
                "A withdrawal debits exactly one account",
            )
            SettlementType.TRANSFER -> {
                requireShape(
                    debitAccountId != null && creditAccountId != null,
                    "A transfer names a debit account and a credit account",
                )
                requireShape(
                    debitAccountId != creditAccountId,
                    "A transfer requires two different accounts",
                )
            }
        }
        if (externalReference != null) {
            requireShape(
                externalReference.isNotBlank() &&
                    externalReference.length <= 128 &&
                    externalReference.none { it.isISOControl() },
                "External reference must be 1 to 128 characters without control characters",
            )
        }
    }

    fun accountIds(): List<UUID> = listOfNotNull(debitAccountId, creditAccountId).distinct().sorted()

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            type.name,
            debitAccountId?.toString().orEmpty(),
            creditAccountId?.toString().orEmpty(),
            money.currency.value,
            money.minorUnits.toString(),
            externalReference.orEmpty(),
        ).forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireShape(ok: Boolean, message: String) {
        if (!ok) {
            throw InvalidSettlementException(message)
        }
    }
}

data class PostingLeg(val accountId: UUID, val direction: Direction)

class SettlementInstruction private constructor(
    val id: UUID,
    val idempotencyKey: String,
    val requestHash: String,
    val type: SettlementType,
    val debitAccountId: UUID?,
    val creditAccountId: UUID?,
    val money: Money,
    val externalReference: String?,
    val status: SettlementStatus,
    val rejectionReason: RejectionReason?,
    val createdAt: Instant,
    val decidedAt: Instant?,
) {
    init {
        when (status) {
            SettlementStatus.RECEIVED -> check(rejectionReason == null && decidedAt == null)
            SettlementStatus.SETTLED -> check(rejectionReason == null && decidedAt != null)
            SettlementStatus.REJECTED -> check(rejectionReason != null && decidedAt != null)
        }
    }

    fun settle(at: Instant): SettlementInstruction = transition(
        target = SettlementStatus.SETTLED,
        reason = null,
        at = at,
    )

    fun reject(reason: RejectionReason, at: Instant): SettlementInstruction = transition(
        target = SettlementStatus.REJECTED,
        reason = reason,
        at = at,
    )

    fun legs(): List<PostingLeg> {
        check(status != SettlementStatus.REJECTED) { "A rejected instruction has no postings" }
        return when (type) {
            SettlementType.DEPOSIT -> listOf(PostingLeg(creditAccountId!!, Direction.CREDIT))
            SettlementType.WITHDRAWAL -> listOf(PostingLeg(debitAccountId!!, Direction.DEBIT))
            SettlementType.TRANSFER -> listOf(
                PostingLeg(debitAccountId!!, Direction.DEBIT),
                PostingLeg(creditAccountId!!, Direction.CREDIT),
            )
        }
    }

    fun entries(postedAt: Instant): List<LedgerEntry> {
        check(status == SettlementStatus.SETTLED) { "Only a settled instruction posts ledger entries" }
        return legs().map { leg ->
            LedgerEntry.posted(
                id = UUID.randomUUID(),
                settlementInstructionId = id,
                accountId = leg.accountId,
                direction = leg.direction,
                money = money,
                createdAt = postedAt,
            )
        }
    }

    private fun transition(
        target: SettlementStatus,
        reason: RejectionReason?,
        at: Instant,
    ): SettlementInstruction {
        check(status == SettlementStatus.RECEIVED) {
            "Cannot move settlement $id from $status to $target"
        }
        return SettlementInstruction(
            id = id,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            type = type,
            debitAccountId = debitAccountId,
            creditAccountId = creditAccountId,
            money = money,
            externalReference = externalReference,
            status = target,
            rejectionReason = reason,
            createdAt = createdAt,
            decidedAt = at,
        )
    }

    companion object {
        fun receive(
            id: UUID,
            idempotencyKey: String,
            requestHash: String,
            command: InstructSettlement,
            at: Instant,
        ): SettlementInstruction = SettlementInstruction(
            id = id,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            type = command.type,
            debitAccountId = command.debitAccountId,
            creditAccountId = command.creditAccountId,
            money = command.money,
            externalReference = command.externalReference,
            status = SettlementStatus.RECEIVED,
            rejectionReason = null,
            createdAt = at,
            decidedAt = null,
        )

        fun restore(
            id: UUID,
            idempotencyKey: String,
            requestHash: String,
            type: SettlementType,
            debitAccountId: UUID?,
            creditAccountId: UUID?,
            money: Money,
            externalReference: String?,
            status: SettlementStatus,
            rejectionReason: RejectionReason?,
            createdAt: Instant,
            decidedAt: Instant?,
        ): SettlementInstruction = SettlementInstruction(
            id, idempotencyKey, requestHash, type, debitAccountId, creditAccountId,
            money, externalReference, status, rejectionReason, createdAt, decidedAt,
        )
    }
}

class SettlementStatusEvent(
    val instructionId: UUID,
    val sequenceNo: Int,
    val status: SettlementStatus,
    val reason: RejectionReason?,
    val occurredAt: Instant,
)

class SettlementOutcome(
    val instruction: SettlementInstruction,
    val events: List<SettlementStatusEvent>,
    val replayed: Boolean,
)

object IdempotencyKey {
    private val PATTERN = Regex("^[\\x21-\\x7E]{1,200}$")

    fun parse(raw: String?): String {
        if (raw.isNullOrBlank()) {
            throw MissingIdempotencyKeyException()
        }
        val key = raw.trim()
        if (!PATTERN.matches(key)) {
            throw InvalidIdempotencyKeyException()
        }
        return key
    }
}
