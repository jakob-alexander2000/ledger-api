package com.ledger.custody.persistence

import com.ledger.custody.money.Money
import com.ledger.custody.settlement.RejectionReason
import com.ledger.custody.settlement.SettlementInstruction
import com.ledger.custody.settlement.SettlementRepository
import com.ledger.custody.settlement.SettlementStatus
import com.ledger.custody.settlement.SettlementStatusEvent
import com.ledger.custody.settlement.SettlementType
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class SettlementJdbcRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SettlementRepository {
    private val instructionMapper = RowMapper { rs, _ -> rs.toInstruction() }
    private val eventMapper = RowMapper { rs, _ -> rs.toEvent() }

    override fun insert(instruction: SettlementInstruction) {
        requireActiveTransaction()
        jdbc.update(
            """
            INSERT INTO settlement_instructions (
                id, idempotency_key, request_hash, type, debit_account_id, credit_account_id,
                currency, amount_minor, external_reference, status, rejection_reason, created_at, decided_at
            ) VALUES (
                :id, :idempotencyKey, :requestHash, :type, :debitAccountId, :creditAccountId,
                :currency, :amountMinor, :externalReference, :status, :rejectionReason, :createdAt, :decidedAt
            )
            """.trimIndent(),
            mapOf(
                "id" to instruction.id.toString(),
                "idempotencyKey" to instruction.idempotencyKey,
                "requestHash" to instruction.requestHash,
                "type" to instruction.type.name,
                "debitAccountId" to instruction.debitAccountId?.toString(),
                "creditAccountId" to instruction.creditAccountId?.toString(),
                "currency" to instruction.money.currency.value,
                "amountMinor" to instruction.money.minorUnits,
                "externalReference" to instruction.externalReference,
                "status" to instruction.status.name,
                "rejectionReason" to instruction.rejectionReason?.name,
                "createdAt" to Timestamp.from(instruction.createdAt),
                "decidedAt" to Timestamp.from(instruction.decidedAt!!),
            ),
        )
    }

    override fun appendEvents(events: List<SettlementStatusEvent>) {
        requireActiveTransaction()
        for (event in events) {
            jdbc.update(
                """
                INSERT INTO settlement_status_events (
                    instruction_id, sequence_no, status, reason, occurred_at
                ) VALUES (
                    :instructionId, :sequenceNo, :status, :reason, :occurredAt
                )
                """.trimIndent(),
                mapOf(
                    "instructionId" to event.instructionId.toString(),
                    "sequenceNo" to event.sequenceNo,
                    "status" to event.status.name,
                    "reason" to event.reason?.name,
                    "occurredAt" to Timestamp.from(event.occurredAt),
                ),
            )
        }
    }

    override fun findById(id: UUID): SettlementInstruction? =
        jdbc.query(
            "SELECT * FROM settlement_instructions WHERE id = :id",
            mapOf("id" to id.toString()),
            instructionMapper,
        ).firstOrNull()

    override fun findByIdempotencyKey(key: String): SettlementInstruction? =
        jdbc.query(
            "SELECT * FROM settlement_instructions WHERE idempotency_key = :key",
            mapOf("key" to key),
            instructionMapper,
        ).firstOrNull()

    override fun findEvents(instructionId: UUID): List<SettlementStatusEvent> =
        jdbc.query(
            """
            SELECT * FROM settlement_status_events
            WHERE instruction_id = :instructionId
            ORDER BY sequence_no ASC
            """.trimIndent(),
            mapOf("instructionId" to instructionId.toString()),
            eventMapper,
        )
}

internal fun ResultSet.toInstruction(): SettlementInstruction = SettlementInstruction.restore(
    id = UUID.fromString(getString("id")),
    idempotencyKey = getString("idempotency_key"),
    requestHash = getString("request_hash"),
    type = SettlementType.valueOf(getString("type")),
    debitAccountId = getString("debit_account_id")?.let(UUID::fromString),
    creditAccountId = getString("credit_account_id")?.let(UUID::fromString),
    money = Money.of(getString("currency").trim(), getLong("amount_minor")),
    externalReference = getString("external_reference"),
    status = SettlementStatus.valueOf(getString("status")),
    rejectionReason = getString("rejection_reason")?.let(RejectionReason::valueOf),
    createdAt = getTimestamp("created_at").toInstant(),
    decidedAt = getTimestamp("decided_at").toInstant(),
)

internal fun ResultSet.toEvent(): SettlementStatusEvent = SettlementStatusEvent(
    instructionId = UUID.fromString(getString("instruction_id")),
    sequenceNo = getInt("sequence_no"),
    status = SettlementStatus.valueOf(getString("status")),
    reason = getString("reason")?.let(RejectionReason::valueOf),
    occurredAt = getTimestamp("occurred_at").toInstant(),
)
