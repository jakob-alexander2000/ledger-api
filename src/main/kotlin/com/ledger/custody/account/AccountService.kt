package com.ledger.custody.account

import com.ledger.custody.ledger.LedgerRepository
import com.ledger.custody.ledger.Position
import com.ledger.custody.money.CurrencyCode
import com.ledger.custody.persistence.required
import com.ledger.custody.shared.AccountNotZeroException
import com.ledger.custody.shared.DuplicateAccountReferenceException
import com.ledger.custody.shared.UnknownAccountException
import com.ledger.custody.shared.now
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)

    fun open(reference: String, displayName: String, currency: CurrencyCode): Account {
        val now = clock.now()
        val account = Account.open(UUID.randomUUID(), reference, displayName, currency, now)
        val position = Position.open(account.id, account.currency, now)
        try {
            tx.required {
                accounts.insert(account)
                ledger.insertPosition(position)
            }
        } catch (ex: DuplicateKeyException) {
            val text = ex.message.orEmpty()
            if (text.contains("reference", ignoreCase = true)) {
                throw DuplicateAccountReferenceException(account.reference)
            }
            throw ex
        }
        log.info("account.opened accountId={} currency={} status={}", account.id, account.currency, account.status)
        return account
    }

    fun get(id: UUID): Account = accounts.findById(id) ?: throw UnknownAccountException(id)

    fun position(id: UUID): Position {
        get(id)
        return ledger.findPosition(id) ?: error("Account $id has no position")
    }

    fun transactions(id: UUID) = ledger.findEntries(get(id).id)

    fun freeze(id: UUID): Account = changeStatus(id) { it.freeze() }

    fun unfreeze(id: UUID): Account = changeStatus(id) { it.unfreeze() }

    fun close(id: UUID): Account = tx.required {
        val account = accounts.lockById(id) ?: throw UnknownAccountException(id)
        val position = ledger.lockPosition(id) ?: error("Account $id has no position")
        if (position.balanceMinor != 0L) {
            throw AccountNotZeroException(id, position.balanceMinor)
        }
        val closed = account.close()
        accounts.updateStatus(closed)
        log.info("account.status accountId={} status={}", closed.id, closed.status)
        closed
    }

    private fun changeStatus(id: UUID, change: (Account) -> Account): Account = tx.required {
        val account = accounts.lockById(id) ?: throw UnknownAccountException(id)
        val updated = change(account)
        accounts.updateStatus(updated)
        log.info("account.status accountId={} status={}", updated.id, updated.status)
        updated
    }
}
