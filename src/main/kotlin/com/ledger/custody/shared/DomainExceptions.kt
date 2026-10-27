package com.ledger.custody.shared

import com.ledger.custody.account.AccountStatus
import com.ledger.custody.settlement.SettlementInstruction
import java.util.UUID

sealed class DomainException(message: String) : RuntimeException(message)

class UnknownAccountException(val accountId: UUID) :
    DomainException("No account exists for id $accountId")

class UnknownSettlementException(val instructionId: UUID) :
    DomainException("No settlement instruction exists for id $instructionId")

class DuplicateAccountReferenceException(val reference: String) :
    DomainException("Account reference already exists: $reference")

class InvalidMoneyException(message: String) : DomainException(message)

class InvalidCurrencyException(raw: String) :
    DomainException("Currency must be three uppercase letters, not '$raw'")

class InvalidAccountReferenceException(raw: String) :
    DomainException("Account reference '$raw' must be 1 to 64 characters: letters, digits, '.', '_' or '-'")

class InvalidAccountNameException :
    DomainException("Display name must be 1 to 200 characters")

class InvalidSettlementException(message: String) : DomainException(message)

class MissingIdempotencyKeyException :
    DomainException("Idempotency-Key header is required")

class InvalidIdempotencyKeyException :
    DomainException("Idempotency-Key must be 1 to 200 printable ASCII characters without spaces")

class IdempotencyConflictException :
    DomainException("Idempotency key was already used with a different request")

class InvalidStatusTransitionException(
    val accountId: UUID,
    val from: AccountStatus,
    val to: AccountStatus,
) : DomainException("Cannot move account $accountId from $from to $to")

class AccountNotZeroException(val accountId: UUID, val balanceMinor: Long) :
    DomainException("Cannot close account $accountId while the available balance is $balanceMinor")

class InsufficientFundsException(
    val accountId: UUID,
    val availableMinor: Long,
    val requestedMinor: Long,
) : DomainException("Insufficient funds for account $accountId")

class AccountNotOpenException(val accountId: UUID, val status: AccountStatus) :
    DomainException("Account $accountId is $status and cannot be posted")

class CurrencyMismatchException(
    val accountId: UUID,
    val accountCurrency: String,
    val requested: String,
) : DomainException("Account $accountId is $accountCurrency; instruction is $requested")

class BalanceLimitException(val accountId: UUID) :
    DomainException("Posting would push account $accountId above the maximum balance")

class SettlementRejectedException(
    val instruction: SettlementInstruction,
    val replayed: Boolean,
) : DomainException(instruction.rejectionReason?.detail() ?: "Settlement rejected")
