package com.ledger.custody.money

import com.ledger.custody.shared.InvalidCurrencyException

@JvmInline
value class CurrencyCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[A-Z]{3}$")

        fun parse(raw: String): CurrencyCode {
            if (!PATTERN.matches(raw)) {
                throw InvalidCurrencyException(raw)
            }
            return CurrencyCode(raw)
        }
    }
}
