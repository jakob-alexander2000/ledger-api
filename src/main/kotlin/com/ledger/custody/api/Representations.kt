package com.ledger.custody.api

import com.ledger.custody.account.Account
import com.ledger.custody.ledger.LedgerEntry
import com.ledger.custody.ledger.Position
import com.ledger.custody.money.CurrencyCode
import com.ledger.custody.money.Money
import com.ledger.custody.settlement.SettlementOutcome
import com.ledger.custody.settlement.SettlementStatusEvent
import com.ledger.custody.settlement.SettlementType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class OpenAccountRequest(
    @field:NotBlank
    @field:Size(max = 64)
    @field:Pattern(regexp = "^[A-Za-z0-9._-]+$")
    val reference: String,
    @field:NotBlank
    @field:Size(max = 200)
    val displayName: String,
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currency: String,
)

data class MoneyRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currency: String,
    @field:Positive
    val minorUnits: Long,
)

data class SettlementRequest(
    val type: SettlementType,
    val debitAccountId: UUID?,
    val creditAccountId: UUID?,
    @field:Valid
    val amount: MoneyRequest,
    @field:Size(max = 128)
    val externalReference: String?,
) {
    fun money(): Money = Money.of(CurrencyCode.parse(amount.currency), amount.minorUnits)

    fun normalizedReference(): String? =
        externalReference?.trim()?.takeIf { it.isNotEmpty() }
}

data class AccountResponse(
    val id: UUID,
    val reference: String,
    val displayName: String,
    val currency: String,
    val status: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(account: Account) = AccountResponse(
            id = account.id,
            reference = account.reference,
            displayName = account.displayName,
            currency = account.currency.value,
            status = account.status.name,
            createdAt = account.createdAt,
        )
    }
}

data class BalanceResponse(
    val accountId: UUID,
    val currency: String,
    val availableMinorUnits: Long,
    val updatedAt: Instant,
) {
    companion object {
        fun from(position: Position) = BalanceResponse(
            accountId = position.accountId,
            currency = position.currency.value,
            availableMinorUnits = position.balanceMinor,
            updatedAt = position.updatedAt,
        )
    }
}

data class MoneyResponse(val currency: String, val minorUnits: Long) {
    companion object {
        fun from(money: Money) = MoneyResponse(money.currency.value, money.minorUnits)
    }
}

data class LedgerEntryResponse(
    val id: UUID,
    val settlementInstructionId: UUID,
    val direction: String,
    val amount: MoneyResponse,
    val createdAt: Instant,
) {
    companion object {
        fun from(entry: LedgerEntry) = LedgerEntryResponse(
            id = entry.id,
            settlementInstructionId = entry.settlementInstructionId,
            direction = entry.direction.name,
            amount = MoneyResponse.from(entry.money),
            createdAt = entry.createdAt,
        )
    }
}

data class TransactionListResponse(
    val accountId: UUID,
    val entries: List<LedgerEntryResponse>,
)

data class StatusEventResponse(
    val sequenceNo: Int,
    val status: String,
    val reason: String?,
    val occurredAt: Instant,
) {
    companion object {
        fun from(event: SettlementStatusEvent) = StatusEventResponse(
            sequenceNo = event.sequenceNo,
            status = event.status.name,
            reason = event.reason?.name,
            occurredAt = event.occurredAt,
        )
    }
}

data class SettlementResponse(
    val id: UUID,
    val type: String,
    val status: String,
    val debitAccountId: UUID?,
    val creditAccountId: UUID?,
    val amount: MoneyResponse,
    val externalReference: String?,
    val rejectionReason: String?,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val replayed: Boolean,
    val statusHistory: List<StatusEventResponse>,
) {
    companion object {
        fun from(outcome: SettlementOutcome): SettlementResponse {
            val instruction = outcome.instruction
            return SettlementResponse(
                id = instruction.id,
                type = instruction.type.name,
                status = instruction.status.name,
                debitAccountId = instruction.debitAccountId,
                creditAccountId = instruction.creditAccountId,
                amount = MoneyResponse.from(instruction.money),
                externalReference = instruction.externalReference,
                rejectionReason = instruction.rejectionReason?.name,
                createdAt = instruction.createdAt,
                decidedAt = instruction.decidedAt,
                replayed = outcome.replayed,
                statusHistory = outcome.events.map(StatusEventResponse::from),
            )
        }
    }
}
