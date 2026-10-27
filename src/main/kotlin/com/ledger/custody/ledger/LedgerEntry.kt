package com.ledger.custody.ledger

import com.ledger.custody.money.Money
import java.time.Instant
import java.util.UUID

class LedgerEntry private constructor(
    val id: UUID,
    val settlementInstructionId: UUID,
    val accountId: UUID,
    val direction: Direction,
    val money: Money,
    val createdAt: Instant,
) {
    companion object {
        fun posted(
            id: UUID,
            settlementInstructionId: UUID,
            accountId: UUID,
            direction: Direction,
            money: Money,
            createdAt: Instant,
        ): LedgerEntry = LedgerEntry(id, settlementInstructionId, accountId, direction, money, createdAt)

        fun restore(
            id: UUID,
            settlementInstructionId: UUID,
            accountId: UUID,
            direction: Direction,
            money: Money,
            createdAt: Instant,
        ): LedgerEntry = posted(id, settlementInstructionId, accountId, direction, money, createdAt)
    }
}
