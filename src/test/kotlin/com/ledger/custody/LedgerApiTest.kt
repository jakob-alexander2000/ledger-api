package com.ledger.custody

import com.fasterxml.jackson.databind.ObjectMapper
import com.ledger.custody.account.AccountService
import com.ledger.custody.money.CurrencyCode
import com.ledger.custody.money.Money
import com.ledger.custody.settlement.InstructSettlement
import com.ledger.custody.settlement.RejectionReason
import com.ledger.custody.settlement.SettlementOutcome
import com.ledger.custody.settlement.SettlementService
import com.ledger.custody.settlement.SettlementType
import com.ledger.custody.shared.IdempotencyConflictException
import com.ledger.custody.shared.SettlementRejectedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val accounts: AccountService,
    private val settlements: SettlementService,
    private val jdbc: JdbcTemplate,
) {
    @Test
    fun `health liveness and readiness are up`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.groups[?(@ == 'liveness')]").exists())
            .andExpect(jsonPath("$.groups[?(@ == 'readiness')]").exists())
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `open account then deposit withdrawal and transfer`() {
        val cash = openAccount("Client cash", "EUR")
        val other = openAccount("Other cash", "EUR")

        val deposit = postSettlement(
            key = "dep-${cash.id}",
            body = depositBody(cash.id, 1_500, "EXT-1"),
        )
        deposit.andExpect(status().isCreated)
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("SETTLED"))
            .andExpect(jsonPath("$.type").value("DEPOSIT"))
            .andExpect(jsonPath("$.amount.minorUnits").value(1_500))
            .andExpect(jsonPath("$.statusHistory[0].status").value("RECEIVED"))
            .andExpect(jsonPath("$.statusHistory[1].status").value("SETTLED"))
            .andExpect(jsonPath("$.replayed").value(false))

        assertEquals(1_500L, balanceOf(cash.id))

        mockMvc.perform(get("/accounts/${cash.id}/transactions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
            .andExpect(jsonPath("$.entries[0].amount.currency").value("EUR"))

        postSettlement(key = "wd-${cash.id}", body = withdrawalBody(cash.id, 400))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("SETTLED"))
        assertEquals(1_100L, balanceOf(cash.id))

        postSettlement(key = "tf-${cash.id}", body = transferBody(cash.id, other.id, 250))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("TRANSFER"))
        assertEquals(850L, balanceOf(cash.id))
        assertEquals(250L, balanceOf(other.id))
        assertEquals(1_100L, balanceOf(cash.id) + balanceOf(other.id))
        assertReconciles(cash.id)
        assertReconciles(other.id)
    }

    @Test
    fun `replaying an idempotency key does not post twice and a different body conflicts`() {
        val cash = openAccount("Idempotent", "EUR")
        val body = depositBody(cash.id, 800, null)
        val key = "same-${cash.id}"

        val created = postSettlement(key, body).andExpect(status().isCreated).andReturn()
        val replayed = postSettlement(key, body)
            .andExpect(status().isOk)
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.replayed").value(true))
            .andExpect(jsonPath("$.status").value("SETTLED"))
            .andReturn()
        val createdJson = objectMapper.readTree(created.response.contentAsString)
        val replayedJson = objectMapper.readTree(replayed.response.contentAsString)
        assertEquals(createdJson.get("id").asText(), replayedJson.get("id").asText())
        assertEquals(createdJson.get("createdAt").asText(), replayedJson.get("createdAt").asText())
        assertEquals(createdJson.get("decidedAt").asText(), replayedJson.get("decidedAt").asText())
        assertEquals(800L, balanceOf(cash.id))
        assertEquals(1, ledgerCount(cash.id))

        postSettlement(key, depositBody(cash.id, 801, null))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"))
        assertEquals(800L, balanceOf(cash.id))
    }

    @Test
    fun `insufficient funds is stored as a rejected instruction and replays the same rejection`() {
        val cash = openAccount("Thin", "EUR")
        postSettlement(key = "fund-${cash.id}", body = depositBody(cash.id, 100, null))
            .andExpect(status().isCreated)

        val key = "overdraw-${cash.id}"
        val rejected = postSettlement(key, withdrawalBody(cash.id, 101))
        rejected.andExpect(status().isConflict)
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
            .andExpect(jsonPath("$.replayed").value(false))
        val instructionId = objectMapper.readTree(rejected.andReturn().response.contentAsString)
            .get("instructionId").asText()

        mockMvc.perform(get("/settlements/$instructionId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.rejectionReason").value("INSUFFICIENT_FUNDS"))
            .andExpect(jsonPath("$.statusHistory[0].status").value("RECEIVED"))
            .andExpect(jsonPath("$.statusHistory[1].status").value("REJECTED"))

        postSettlement(key, withdrawalBody(cash.id, 101))
            .andExpect(status().isConflict)
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.replayed").value(true))
        assertEquals(100L, balanceOf(cash.id))
        assertEquals(1, ledgerCount(cash.id))
    }

    @Test
    fun `unknown account invalid amount and missing key are refused without a ledger row`() {
        val missing = UUID.randomUUID()
        val before = instructionCount()

        postSettlement(key = "missing-$missing", body = depositBody(missing, 10, null))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("UNKNOWN_ACCOUNT"))
        assertEquals(before, instructionCount())

        val cash = openAccount("Validated", "EUR")
        postSettlement(key = "zero-${cash.id}", body = depositBody(cash.id, 0, null))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_AMOUNT"))
        postSettlement(key = "neg-${cash.id}", body = depositBody(cash.id, -5, null))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_AMOUNT"))
        postSettlement(
            key = "huge-${cash.id}",
            body = depositBody(cash.id, Money.MAX_MINOR_UNITS + 1, null),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_AMOUNT"))

        mockMvc.perform(
            post("/settlements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(depositBody(cash.id, 10, null)),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("MISSING_IDEMPOTENCY_KEY"))

        postSettlement(key = "bad key", body = depositBody(cash.id, 10, null))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"))

        postSettlement(key = "self-${cash.id}", body = transferBody(cash.id, cash.id, 10))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_SETTLEMENT"))

        assertEquals(0, ledgerCount(cash.id))
        assertEquals(before, instructionCount())
    }

    @Test
    fun `frozen and closed accounts reject postings and close requires a zero balance`() {
        val cash = openAccount("Lifecycle", "EUR")
        postSettlement(key = "seed-${cash.id}", body = depositBody(cash.id, 50, null))
            .andExpect(status().isCreated)

        mockMvc.perform(post("/accounts/${cash.id}/close"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_ZERO"))

        mockMvc.perform(post("/accounts/${cash.id}/freeze"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FROZEN"))
        postSettlement(key = "frozen-${cash.id}", body = depositBody(cash.id, 10, null))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_OPEN"))
        assertEquals(50L, balanceOf(cash.id))

        mockMvc.perform(post("/accounts/${cash.id}/unfreeze"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("OPEN"))
        postSettlement(key = "drain-${cash.id}", body = withdrawalBody(cash.id, 50))
            .andExpect(status().isCreated)
        mockMvc.perform(post("/accounts/${cash.id}/close"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CLOSED"))
        postSettlement(key = "closed-${cash.id}", body = depositBody(cash.id, 5, null))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_OPEN"))
        mockMvc.perform(post("/accounts/${cash.id}/unfreeze"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"))
    }

    @Test
    fun `currency must match the account and duplicate references are refused`() {
        val eur = openAccount("Euro", "EUR")
        val usd = openAccount("Dollar", "USD")
        postSettlement(key = "fx-${eur.id}", body = transferBody(eur.id, usd.id, 10, "USD"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("CURRENCY_MISMATCH"))

        val reference = "CUST-${UUID.randomUUID()}"
        val body = """{"reference":"$reference","displayName":"One","currency":"EUR"}"""
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("DUPLICATE_ACCOUNT"))

        mockMvc.perform(
            post("/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"bad ref","displayName":"X","currency":"EUR"}"""),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT"))
        mockMvc.perform(get("/accounts/not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
        mockMvc.perform(get("/settlements/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("UNKNOWN_SETTLEMENT"))
    }

    @Test
    fun `a second credit cannot push the position past the maximum`() {
        val cash = openAccount("Capped", "EUR")
        postSettlement(key = "max-${cash.id}", body = depositBody(cash.id, Money.MAX_MINOR_UNITS, null))
            .andExpect(status().isCreated)
        postSettlement(key = "over-max-${cash.id}", body = depositBody(cash.id, 1, null))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("BALANCE_LIMIT"))
        assertEquals(Money.MAX_MINOR_UNITS, balanceOf(cash.id))
    }

    @Test
    fun `ledger rows cannot be updated or deleted and a position cannot go negative`() {
        val cash = openAccount("Append only", "EUR")
        postSettlement(key = "row-${cash.id}", body = depositBody(cash.id, 20, null))
            .andExpect(status().isCreated)
        val entryId = jdbc.queryForObject(
            "SELECT id FROM ledger_entries WHERE account_id = ?",
            String::class.java,
            cash.id.toString(),
        )
        val update = assertThrows(org.springframework.dao.DataAccessException::class.java) {
            jdbc.update("UPDATE ledger_entries SET amount_minor = 1 WHERE id = ?", entryId)
        }
        assertTrue(causeChain(update).contains("append-only"), causeChain(update))
        val delete = assertThrows(org.springframework.dao.DataAccessException::class.java) {
            jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId)
        }
        assertTrue(causeChain(delete).contains("append-only"), causeChain(delete))

        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE positions SET balance_minor = -1 WHERE account_id = ?", cash.id.toString())
        }
        assertEquals(20L, balanceOf(cash.id))
        assertEquals(1, ledgerCount(cash.id))
    }

    @Test
    fun `concurrent withdrawals cannot overdraw and a repeated key posts once`() {
        val cash = accounts.open("RACE-${UUID.randomUUID()}", "Race", CurrencyCode.parse("EUR"))
        settlements.instruct(deposit(cash.id, 1_000), "fund-${cash.id}")

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { index ->
                pool.submit<Any> {
                    start.await()
                    try {
                        settlements.instruct(withdrawal(cash.id, 1_000), "wd-${cash.id}-$index")
                    } catch (ex: SettlementRejectedException) {
                        ex
                    }
                }
            }
            start.countDown()
            val finished = results.map { it.get(15, TimeUnit.SECONDS) }
            val settled = finished.filterIsInstance<SettlementOutcome>()
            val rejected = finished.filterIsInstance<SettlementRejectedException>()
            assertEquals(1, settled.size)
            assertEquals(1, rejected.size)
            assertEquals(RejectionReason.INSUFFICIENT_FUNDS, rejected.single().instruction.rejectionReason)
            assertEquals(0L, accounts.position(cash.id).balanceMinor)
        } finally {
            pool.shutdownNow()
        }

        val other = accounts.open("RACE2-${UUID.randomUUID()}", "Race two", CurrencyCode.parse("EUR"))
        val gate = CountDownLatch(1)
        val replayPool = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map {
                replayPool.submit<SettlementOutcome> {
                    gate.await()
                    settlements.instruct(deposit(other.id, 300), "once-${other.id}")
                }
            }
            gate.countDown()
            calls.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            replayPool.shutdownNow()
        }
        assertEquals(300L, accounts.position(other.id).balanceMinor)
        assertEquals(1, ledgerCount(other.id))

        val left = accounts.open("RACE3-${UUID.randomUUID()}", "Left", CurrencyCode.parse("EUR"))
        val right = accounts.open("RACE4-${UUID.randomUUID()}", "Right", CurrencyCode.parse("EUR"))
        val conflictGate = CountDownLatch(1)
        val conflictPool = Executors.newFixedThreadPool(2)
        try {
            val calls = listOf(left.id, right.id).map { accountId ->
                conflictPool.submit<Any> {
                    conflictGate.await()
                    try {
                        settlements.instruct(deposit(accountId, 40), "shared-key-${left.id}")
                    } catch (ex: IdempotencyConflictException) {
                        ex
                    }
                }
            }
            conflictGate.countDown()
            val finished = calls.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, finished.filterIsInstance<SettlementOutcome>().size)
            assertEquals(1, finished.filterIsInstance<IdempotencyConflictException>().size)
        } finally {
            conflictPool.shutdownNow()
        }
        assertEquals(40L, accounts.position(left.id).balanceMinor + accounts.position(right.id).balanceMinor)
    }

    private fun openAccount(name: String, currency: String): OpenedAccount {
        val reference = "CUST-${UUID.randomUUID()}"
        val result = mockMvc.perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reference":"$reference","displayName":"$name","currency":"$currency"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.currency").value(currency))
            .andReturn()
        val id = UUID.fromString(objectMapper.readTree(result.response.contentAsString).get("id").asText())
        return OpenedAccount(id)
    }

    private fun postSettlement(key: String, body: String) =
        mockMvc.perform(
            post("/settlements")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(body),
        )

    private fun depositBody(accountId: UUID, amount: Long, reference: String?): String {
        val ref = if (reference == null) "" else ""","externalReference":"$reference""""
        return """{"type":"DEPOSIT","creditAccountId":"$accountId","amount":{"currency":"EUR","minorUnits":$amount}$ref}"""
    }

    private fun withdrawalBody(accountId: UUID, amount: Long) =
        """{"type":"WITHDRAWAL","debitAccountId":"$accountId","amount":{"currency":"EUR","minorUnits":$amount}}"""

    private fun transferBody(debit: UUID, credit: UUID, amount: Long, currency: String = "EUR") =
        """{"type":"TRANSFER","debitAccountId":"$debit","creditAccountId":"$credit","amount":{"currency":"$currency","minorUnits":$amount}}"""

    private fun balanceOf(accountId: UUID): Long {
        val body = mockMvc.perform(get("/accounts/$accountId/balance"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readTree(body).get("availableMinorUnits").asLong()
    }

    private fun ledgerCount(accountId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?",
            Int::class.java,
            accountId.toString(),
        )!!

    private fun instructionCount(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM settlement_instructions", Int::class.java)!!

    private fun assertReconciles(accountId: UUID) {
        val signed = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
            FROM ledger_entries WHERE account_id = ?
            """.trimIndent(),
            Long::class.java,
            accountId.toString(),
        )
        assertEquals(signed, balanceOf(accountId))
    }

    private fun deposit(accountId: UUID, amount: Long) = InstructSettlement(
        type = SettlementType.DEPOSIT,
        debitAccountId = null,
        creditAccountId = accountId,
        money = Money.of("EUR", amount),
        externalReference = null,
    )

    private fun withdrawal(accountId: UUID, amount: Long) = InstructSettlement(
        type = SettlementType.WITHDRAWAL,
        debitAccountId = accountId,
        creditAccountId = null,
        money = Money.of("EUR", amount),
        externalReference = null,
    )

    private fun causeChain(error: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = error
        while (current != null && parts.size < 8) {
            parts += current.message.orEmpty()
            current = current.cause
        }
        return parts.joinToString(" | ")
    }

    private data class OpenedAccount(val id: UUID)
}
