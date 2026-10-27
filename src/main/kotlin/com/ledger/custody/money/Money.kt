package com.ledger.custody.money

import com.ledger.custody.shared.InvalidMoneyException

class Money private constructor(
    val currency: CurrencyCode,
    val minorUnits: Long,
) {
    init {
        require(minorUnits > 0L && minorUnits <= MAX_MINOR_UNITS) {
            "Money must be constructed with a positive amount up to $MAX_MINOR_UNITS"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Money && currency == other.currency && minorUnits == other.minorUnits

    override fun hashCode(): Int = 31 * currency.hashCode() + minorUnits.hashCode()

    override fun toString(): String = "${currency.value} $minorUnits"

    companion object {
        const val MAX_MINOR_UNITS: Long = 1_000_000_000_000L

        fun of(currency: CurrencyCode, minorUnits: Long): Money {
            if (minorUnits <= 0L || minorUnits > MAX_MINOR_UNITS) {
                throw InvalidMoneyException(
                    "Amount must be a positive number of minor units up to $MAX_MINOR_UNITS",
                )
            }
            return Money(currency, minorUnits)
        }

        fun of(currency: String, minorUnits: Long): Money = of(CurrencyCode.parse(currency), minorUnits)
    }
}
