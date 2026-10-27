package com.ledger.custody.ledger

import com.ledger.custody.money.CurrencyCode
import com.ledger.custody.money.Money
import com.ledger.custody.shared.BalanceLimitException
import com.ledger.custody.shared.InsufficientFundsException
import java.time.Instant
import java.util.UUID

class Position private constructor(
    val accountId: UUID,
    val currency: CurrencyCode,
    val balanceMinor: Long,
    val updatedAt: Instant,
) {
    init {
        require(balanceMinor >= 0L) { "Position cannot be negative" }
        require(balanceMinor <= Money.MAX_MINOR_UNITS) { "Position exceeds the maximum" }
    }

    fun apply(direction: Direction, amountMinor: Long, at: Instant): Position {
        val next = when (direction) {
            Direction.CREDIT -> addCredit(amountMinor)
            Direction.DEBIT -> subtractDebit(amountMinor)
        }
        return Position(accountId, currency, next, at)
    }

    private fun addCredit(amountMinor: Long): Long {
        val next = try {
            Math.addExact(balanceMinor, amountMinor)
        } catch (_: ArithmeticException) {
            throw BalanceLimitException(accountId)
        }
        if (next > Money.MAX_MINOR_UNITS) {
            throw BalanceLimitException(accountId)
        }
        return next
    }

    private fun subtractDebit(amountMinor: Long): Long {
        if (balanceMinor < amountMinor) {
            throw InsufficientFundsException(accountId, balanceMinor, amountMinor)
        }
        return balanceMinor - amountMinor
    }

    companion object {
        fun open(accountId: UUID, currency: CurrencyCode, at: Instant): Position =
            Position(accountId, currency, 0L, at)

        fun restore(
            accountId: UUID,
            currency: CurrencyCode,
            balanceMinor: Long,
            updatedAt: Instant,
        ): Position = Position(accountId, currency, balanceMinor, updatedAt)
    }
}
