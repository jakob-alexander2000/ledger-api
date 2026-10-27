package com.ledger.custody.account

import com.ledger.custody.money.CurrencyCode
import com.ledger.custody.shared.InvalidAccountNameException
import com.ledger.custody.shared.InvalidAccountReferenceException
import com.ledger.custody.shared.InvalidStatusTransitionException
import java.time.Instant
import java.util.UUID

class Account private constructor(
    val id: UUID,
    val reference: String,
    val displayName: String,
    val currency: CurrencyCode,
    val status: AccountStatus,
    val createdAt: Instant,
) {
    fun freeze(): Account = transition(AccountStatus.FROZEN)

    fun unfreeze(): Account = transition(AccountStatus.OPEN)

    fun close(): Account = transition(AccountStatus.CLOSED)

    private fun transition(target: AccountStatus): Account {
        val allowed = when (status) {
            AccountStatus.OPEN -> setOf(AccountStatus.FROZEN, AccountStatus.CLOSED)
            AccountStatus.FROZEN -> setOf(AccountStatus.OPEN, AccountStatus.CLOSED)
            AccountStatus.CLOSED -> emptySet()
        }
        if (target !in allowed) {
            throw InvalidStatusTransitionException(id, status, target)
        }
        return Account(id, reference, displayName, currency, target, createdAt)
    }

    companion object {
        private val REFERENCE = Regex("^[A-Za-z0-9._-]{1,64}$")

        fun open(
            id: UUID,
            reference: String,
            displayName: String,
            currency: CurrencyCode,
            openedAt: Instant,
        ): Account {
            val ref = reference.trim()
            val name = displayName.trim()
            if (!REFERENCE.matches(ref)) {
                throw InvalidAccountReferenceException(reference)
            }
            if (name.isEmpty() || name.length > 200) {
                throw InvalidAccountNameException()
            }
            return Account(id, ref, name, currency, AccountStatus.OPEN, openedAt)
        }

        fun restore(
            id: UUID,
            reference: String,
            displayName: String,
            currency: CurrencyCode,
            status: AccountStatus,
            createdAt: Instant,
        ): Account = Account(id, reference, displayName, currency, status, createdAt)
    }
}
