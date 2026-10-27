package com.ledger.custody.persistence

import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

fun <T> TransactionTemplate.required(block: () -> T): T =
    requireNotNull(execute { block() }) { "Transaction callback returned null" }

fun requireActiveTransaction() {
    check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "This write must run inside a transaction"
    }
}
