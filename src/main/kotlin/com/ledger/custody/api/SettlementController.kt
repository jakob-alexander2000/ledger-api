package com.ledger.custody.api

import com.ledger.custody.settlement.InstructSettlement
import com.ledger.custody.settlement.SettlementService
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class SettlementController(
    private val settlements: SettlementService,
) {
    @PostMapping("/settlements")
    fun instruct(
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: SettlementRequest,
    ): ResponseEntity<SettlementResponse> {
        val command = InstructSettlement(
            type = request.type,
            debitAccountId = request.debitAccountId,
            creditAccountId = request.creditAccountId,
            money = request.money(),
            externalReference = request.normalizedReference(),
        )
        val outcome = settlements.instruct(command, idempotencyKey)
        val status = if (outcome.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status)
            .header("Idempotency-Replayed", outcome.replayed.toString())
            .header(HttpHeaders.LOCATION, "/settlements/${outcome.instruction.id}")
            .body(SettlementResponse.from(outcome))
    }

    @GetMapping("/settlements/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<SettlementResponse> {
        val outcome = settlements.get(id)
        return ResponseEntity.ok()
            .header("Idempotency-Replayed", "false")
            .body(SettlementResponse.from(outcome))
    }
}
