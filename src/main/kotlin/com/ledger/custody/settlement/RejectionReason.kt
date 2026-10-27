package com.ledger.custody.settlement

enum class RejectionReason {
    INSUFFICIENT_FUNDS,
    ACCOUNT_NOT_OPEN,
    CURRENCY_MISMATCH,
    BALANCE_LIMIT,
    ;

    fun detail(): String = when (this) {
        INSUFFICIENT_FUNDS -> "Available balance is below the debit amount"
        ACCOUNT_NOT_OPEN -> "One or more accounts are not open for posting"
        CURRENCY_MISMATCH -> "Instruction currency does not match the account currency"
        BALANCE_LIMIT -> "The posting would push the position above the maximum balance"
    }
}
