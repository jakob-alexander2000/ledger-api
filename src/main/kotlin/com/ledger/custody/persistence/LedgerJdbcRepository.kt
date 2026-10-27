package com.ledger.custody.persistence

import com.ledger.custody.ledger.Direction
import com.ledger.custody.ledger.LedgerEntry
import com.ledger.custody.ledger.LedgerRepository
import com.ledger.custody.ledger.Position
import com.ledger.custody.money.CurrencyCode
import com.ledger.custody.money.Money
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class LedgerJdbcRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : LedgerRepository {
    private val positionMapper = RowMapper { rs, _ -> rs.toPosition() }
    private val entryMapper = RowMapper { rs, _ -> rs.toEntry() }

    override fun insertPosition(position: Position) {
        requireActiveTransaction()
        jdbc.update(
            """
            INSERT INTO positions (account_id, currency, balance_minor, updated_at)
            VALUES (:accountId, :currency, :balanceMinor, :updatedAt)
            """.trimIndent(),
            position.params(),
        )
    }

    override fun updatePosition(position: Position) {
        requireActiveTransaction()
        val rows = jdbc.update(
            """
            UPDATE positions
            SET balance_minor = :balanceMinor, updated_at = :updatedAt
            WHERE account_id = :accountId
            """.trimIndent(),
            position.params(),
        )
        check(rows == 1) { "Position ${position.accountId} was not updated" }
    }

    override fun findPosition(accountId: UUID): Position? =
        jdbc.query(
            "SELECT * FROM positions WHERE account_id = :accountId",
            mapOf("accountId" to accountId.toString()),
            positionMapper,
        ).firstOrNull()

    override fun lockPosition(accountId: UUID): Position? {
        requireActiveTransaction()
        return jdbc.query(
            "SELECT * FROM positions WHERE account_id = :accountId FOR UPDATE",
            mapOf("accountId" to accountId.toString()),
            positionMapper,
        ).firstOrNull()
    }

    override fun append(entries: List<LedgerEntry>) {
        requireActiveTransaction()
        for (entry in entries) {
            jdbc.update(
                """
                INSERT INTO ledger_entries (
                    id, settlement_instruction_id, account_id, currency, direction, amount_minor, created_at
                ) VALUES (
                    :id, :settlementInstructionId, :accountId, :currency, :direction, :amountMinor, :createdAt
                )
                """.trimIndent(),
                mapOf(
                    "id" to entry.id.toString(),
                    "settlementInstructionId" to entry.settlementInstructionId.toString(),
                    "accountId" to entry.accountId.toString(),
                    "currency" to entry.money.currency.value,
                    "direction" to entry.direction.name,
                    "amountMinor" to entry.money.minorUnits,
                    "createdAt" to Timestamp.from(entry.createdAt),
                ),
            )
        }
    }

    override fun findEntries(accountId: UUID): List<LedgerEntry> =
        jdbc.query(
            """
            SELECT * FROM ledger_entries
            WHERE account_id = :accountId
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
            mapOf("accountId" to accountId.toString()),
            entryMapper,
        )

    private fun Position.params(): Map<String, Any> = mapOf(
        "accountId" to accountId.toString(),
        "currency" to currency.value,
        "balanceMinor" to balanceMinor,
        "updatedAt" to Timestamp.from(updatedAt),
    )
}

internal fun ResultSet.toPosition(): Position = Position.restore(
    accountId = UUID.fromString(getString("account_id")),
    currency = CurrencyCode.parse(getString("currency").trim()),
    balanceMinor = getLong("balance_minor"),
    updatedAt = getTimestamp("updated_at").toInstant(),
)

internal fun ResultSet.toEntry(): LedgerEntry = LedgerEntry.restore(
    id = UUID.fromString(getString("id")),
    settlementInstructionId = UUID.fromString(getString("settlement_instruction_id")),
    accountId = UUID.fromString(getString("account_id")),
    direction = Direction.valueOf(getString("direction")),
    money = Money.of(getString("currency").trim(), getLong("amount_minor")),
    createdAt = getTimestamp("created_at").toInstant(),
)
