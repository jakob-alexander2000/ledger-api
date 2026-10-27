package com.ledger.custody.persistence

import com.ledger.custody.account.Account
import com.ledger.custody.account.AccountRepository
import com.ledger.custody.account.AccountStatus
import com.ledger.custody.money.CurrencyCode
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class AccountJdbcRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : AccountRepository {
    private val mapper = RowMapper { rs, _ -> rs.toAccount() }

    override fun insert(account: Account) {
        requireActiveTransaction()
        jdbc.update(
            """
            INSERT INTO accounts (id, reference, display_name, currency, status, created_at)
            VALUES (:id, :reference, :displayName, :currency, :status, :createdAt)
            """.trimIndent(),
            account.params(),
        )
    }

    override fun updateStatus(account: Account) {
        requireActiveTransaction()
        val rows = jdbc.update(
            "UPDATE accounts SET status = :status WHERE id = :id",
            mapOf("status" to account.status.name, "id" to account.id.toString()),
        )
        check(rows == 1) { "Account ${account.id} was not updated" }
    }

    override fun findById(id: UUID): Account? =
        jdbc.query("SELECT * FROM accounts WHERE id = :id", mapOf("id" to id.toString()), mapper).firstOrNull()

    override fun lockById(id: UUID): Account? {
        requireActiveTransaction()
        return jdbc.query(
            "SELECT * FROM accounts WHERE id = :id FOR UPDATE",
            mapOf("id" to id.toString()),
            mapper,
        ).firstOrNull()
    }

    private fun Account.params(): Map<String, Any> = mapOf(
        "id" to id.toString(),
        "reference" to reference,
        "displayName" to displayName,
        "currency" to currency.value,
        "status" to status.name,
        "createdAt" to Timestamp.from(createdAt),
    )
}

internal fun ResultSet.toAccount(): Account = Account.restore(
    id = UUID.fromString(getString("id")),
    reference = getString("reference"),
    displayName = getString("display_name"),
    currency = CurrencyCode.parse(getString("currency").trim()),
    status = AccountStatus.valueOf(getString("status")),
    createdAt = getTimestamp("created_at").toInstant(),
)
