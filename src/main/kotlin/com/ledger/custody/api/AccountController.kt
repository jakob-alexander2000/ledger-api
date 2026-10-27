package com.ledger.custody.api

import com.ledger.custody.account.AccountService
import com.ledger.custody.ledger.LedgerEntry
import com.ledger.custody.money.CurrencyCode
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class AccountController(
    private val accounts: AccountService,
) {
    @PostMapping("/accounts")
    fun open(@Valid @RequestBody request: OpenAccountRequest): ResponseEntity<AccountResponse> {
        val account = accounts.open(request.reference, request.displayName, CurrencyCode.parse(request.currency))
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.LOCATION, "/accounts/${account.id}")
            .body(AccountResponse.from(account))
    }

    @GetMapping("/accounts/{id}")
    fun get(@PathVariable id: UUID): AccountResponse = AccountResponse.from(accounts.get(id))

    @GetMapping("/accounts/{id}/balance")
    fun balance(@PathVariable id: UUID): BalanceResponse = BalanceResponse.from(accounts.position(id))

    @GetMapping("/accounts/{id}/transactions")
    fun transactions(@PathVariable id: UUID): TransactionListResponse {
        val entries: List<LedgerEntry> = accounts.transactions(id)
        return TransactionListResponse(id, entries.map(LedgerEntryResponse::from))
    }

    @PostMapping("/accounts/{id}/freeze")
    fun freeze(@PathVariable id: UUID): AccountResponse = AccountResponse.from(accounts.freeze(id))

    @PostMapping("/accounts/{id}/unfreeze")
    fun unfreeze(@PathVariable id: UUID): AccountResponse = AccountResponse.from(accounts.unfreeze(id))

    @PostMapping("/accounts/{id}/close")
    fun close(@PathVariable id: UUID): AccountResponse = AccountResponse.from(accounts.close(id))
}
