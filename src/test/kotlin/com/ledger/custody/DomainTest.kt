package com.ledger.custody

import com.ledger.custody.account.Account
import com.ledger.custody.account.AccountStatus
import com.ledger.custody.ledger.Direction
import com.ledger.custody.ledger.Position
import com.ledger.custody.money.CurrencyCode
import com.ledger.custody.money.Money
import com.ledger.custody.settlement.InstructSettlement
import com.ledger.custody.settlement.SettlementInstruction
import com.ledger.custody.settlement.SettlementStatus
import com.ledger.custody.settlement.SettlementType
import com.ledger.custody.shared.BalanceLimitException
import com.ledger.custody.shared.InsufficientFundsException
import com.ledger.custody.shared.InvalidAccountReferenceException
import com.ledger.custody.shared.InvalidCurrencyException
import com.ledger.custody.shared.InvalidMoneyException
import com.ledger.custody.shared.InvalidSettlementException
import com.ledger.custody.shared.InvalidStatusTransitionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DomainTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private val eur = CurrencyCode.parse("EUR")

    @Test
    fun `currency and money reject values that are not a positive minor-unit amount`() {
        assertThrows(InvalidCurrencyException::class.java) { CurrencyCode.parse("eur") }
        assertThrows(InvalidCurrencyException::class.java) { CurrencyCode.parse("EU") }
        assertThrows(InvalidMoneyException::class.java) { Money.of(eur, 0) }
        assertThrows(InvalidMoneyException::class.java) { Money.of(eur, -1) }
        assertThrows(InvalidMoneyException::class.java) { Money.of(eur, Money.MAX_MINOR_UNITS + 1) }
        assertEquals(1_500L, Money.of("EUR", 1_500).minorUnits)
    }

    @Test
    fun `account status moves only along the open frozen closed edges`() {
        val account = Account.open(UUID.randomUUID(), "CUST-1", "Client cash", eur, now)
        assertEquals(AccountStatus.OPEN, account.status)

        val frozen = account.freeze()
        assertEquals(AccountStatus.FROZEN, frozen.status)
        assertEquals(AccountStatus.OPEN, frozen.unfreeze().status)
        assertEquals(AccountStatus.CLOSED, account.close().status)
        assertEquals(AccountStatus.CLOSED, frozen.close().status)

        val closed = account.close()
        assertThrows(InvalidStatusTransitionException::class.java) { closed.unfreeze() }
        assertThrows(InvalidStatusTransitionException::class.java) { closed.freeze() }
        assertThrows(InvalidStatusTransitionException::class.java) { account.freeze().freeze() }
        assertThrows(InvalidAccountReferenceException::class.java) {
            Account.open(UUID.randomUUID(), "has space", "Name", eur, now)
        }
    }

    @Test
    fun `settlement moves from received to one terminal status and a transfer is zero sum`() {
        val debit = UUID.randomUUID()
        val credit = UUID.randomUUID()
        val command = InstructSettlement(
            type = SettlementType.TRANSFER,
            debitAccountId = debit,
            creditAccountId = credit,
            money = Money.of(eur, 400),
            externalReference = "SET-1",
        )
        val received = SettlementInstruction.receive(UUID.randomUUID(), "key-1", command.fingerprint(), command, now)
        assertEquals(SettlementStatus.RECEIVED, received.status)

        val settled = received.settle(now)
        assertEquals(SettlementStatus.SETTLED, settled.status)
        val signed = settled.legs().sumOf { leg ->
            if (leg.direction == Direction.CREDIT) settled.money.minorUnits else -settled.money.minorUnits
        }
        assertEquals(0L, signed)
        assertEquals(2, settled.entries(now).size)
        assertThrows(IllegalStateException::class.java) { settled.settle(now) }
        assertThrows(IllegalStateException::class.java) {
            settled.reject(com.ledger.custody.settlement.RejectionReason.INSUFFICIENT_FUNDS, now)
        }

        val rejected = received.reject(com.ledger.custody.settlement.RejectionReason.ACCOUNT_NOT_OPEN, now)
        assertEquals(SettlementStatus.REJECTED, rejected.status)
        assertThrows(IllegalStateException::class.java) { rejected.legs() }
    }

    @Test
    fun `a transfer of one account to itself is refused before any posting`() {
        val id = UUID.randomUUID()
        assertThrows(InvalidSettlementException::class.java) {
            InstructSettlement(SettlementType.TRANSFER, id, id, Money.of(eur, 10), null)
        }
        assertThrows(InvalidSettlementException::class.java) {
            InstructSettlement(SettlementType.DEPOSIT, id, id, Money.of(eur, 10), null)
        }
        assertThrows(InvalidSettlementException::class.java) {
            InstructSettlement(SettlementType.WITHDRAWAL, null, null, Money.of(eur, 10), null)
        }
    }

    @Test
    fun `position debits stop at zero and credits stop at the maximum`() {
        val accountId = UUID.randomUUID()
        val opened = Position.open(accountId, eur, now)
        val funded = opened.apply(Direction.CREDIT, 500, now)
        assertEquals(500L, funded.balanceMinor)
        assertEquals(200L, funded.apply(Direction.DEBIT, 300, now).balanceMinor)

        val empty = opened
        val insufficient = assertThrows(InsufficientFundsException::class.java) {
            empty.apply(Direction.DEBIT, 1, now)
        }
        assertEquals(0L, insufficient.availableMinor)

        val full = opened.apply(Direction.CREDIT, Money.MAX_MINOR_UNITS, now)
        assertThrows(BalanceLimitException::class.java) {
            full.apply(Direction.CREDIT, 1, now)
        }
    }

    @Test
    fun `fingerprint changes when the request changes and stays put when it does not`() {
        val accountId = UUID.randomUUID()
        val first = deposit(accountId, 100, "REF")
        val same = deposit(accountId, 100, "REF")
        val otherAmount = deposit(accountId, 101, "REF")
        assertEquals(first.fingerprint(), same.fingerprint())
        assert(first.fingerprint() != otherAmount.fingerprint())
    }

    private fun deposit(accountId: UUID, amount: Long, reference: String) = InstructSettlement(
        type = SettlementType.DEPOSIT,
        debitAccountId = null,
        creditAccountId = accountId,
        money = Money.of(eur, amount),
        externalReference = reference,
    )
}
