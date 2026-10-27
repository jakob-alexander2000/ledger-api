package com.ledger.custody.settlement

import java.util.UUID

interface SettlementRepository {
    fun insert(instruction: SettlementInstruction)

    fun appendEvents(events: List<SettlementStatusEvent>)

    fun findById(id: UUID): SettlementInstruction?

    fun findByIdempotencyKey(key: String): SettlementInstruction?

    fun findEvents(instructionId: UUID): List<SettlementStatusEvent>
}
