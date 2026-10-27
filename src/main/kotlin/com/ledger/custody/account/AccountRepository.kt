package com.ledger.custody.account

import java.util.UUID

interface AccountRepository {
    fun insert(account: Account)

    fun updateStatus(account: Account)

    fun findById(id: UUID): Account?

    fun lockById(id: UUID): Account?
}
