package com.ledger.custody.persistence

import org.h2.api.Trigger
import java.sql.Connection
import java.sql.SQLException

/**
 * H2 guard for the local and test database. PostgreSQL uses the trigger in
 * db/vendor/postgres. Both refuse updates and deletes of ledger rows.
 * On delete, H2 passes a null new row, so the parameters stay nullable.
 */
class RejectLedgerMutation : Trigger {
    override fun fire(conn: Connection, oldRow: Array<out Any>?, newRow: Array<out Any>?) {
        throw SQLException("ledger_entries is append-only")
    }
}
