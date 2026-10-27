package com.ledger.custody.ledger

import java.util.UUID

interface LedgerRepository {
    fun insertPosition(position: Position)

    fun updatePosition(position: Position)

    fun findPosition(accountId: UUID): Position?

    fun lockPosition(accountId: UUID): Position?

    fun append(entries: List<LedgerEntry>)

    fun findEntries(accountId: UUID): List<LedgerEntry>
}
