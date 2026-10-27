package com.ledger.custody.api

import com.ledger.custody.shared.AccountNotOpenException
import com.ledger.custody.shared.AccountNotZeroException
import com.ledger.custody.shared.BalanceLimitException
import com.ledger.custody.shared.CurrencyMismatchException
import com.ledger.custody.shared.DomainException
import com.ledger.custody.shared.DuplicateAccountReferenceException
import com.ledger.custody.shared.IdempotencyConflictException
import com.ledger.custody.shared.InsufficientFundsException
import com.ledger.custody.shared.InvalidAccountNameException
import com.ledger.custody.shared.InvalidAccountReferenceException
import com.ledger.custody.shared.InvalidCurrencyException
import com.ledger.custody.shared.InvalidIdempotencyKeyException
import com.ledger.custody.shared.InvalidMoneyException
import com.ledger.custody.shared.InvalidSettlementException
import com.ledger.custody.shared.InvalidStatusTransitionException
import com.ledger.custody.shared.MissingIdempotencyKeyException
import com.ledger.custody.shared.SettlementRejectedException
import com.ledger.custody.settlement.RejectionReason
import com.ledger.custody.shared.UnknownAccountException
import com.ledger.custody.shared.UnknownSettlementException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.http.ProblemDetail

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(SettlementRejectedException::class)
    fun rejected(ex: SettlementRejectedException): ResponseEntity<ProblemDetail> {
        val reason = ex.instruction.rejectionReason
        val (status, title, code) = when (reason) {
            RejectionReason.INSUFFICIENT_FUNDS ->
                Triple(HttpStatus.CONFLICT, "Insufficient funds", "INSUFFICIENT_FUNDS")
            RejectionReason.ACCOUNT_NOT_OPEN ->
                Triple(HttpStatus.CONFLICT, "Account not open", "ACCOUNT_NOT_OPEN")
            RejectionReason.CURRENCY_MISMATCH ->
                Triple(HttpStatus.UNPROCESSABLE_ENTITY, "Currency mismatch", "CURRENCY_MISMATCH")
            RejectionReason.BALANCE_LIMIT ->
                Triple(HttpStatus.UNPROCESSABLE_ENTITY, "Balance limit", "BALANCE_LIMIT")
            null -> Triple(HttpStatus.CONFLICT, "Settlement rejected", "SETTLEMENT_REJECTED")
        }
        val problem = problem(status, title, ex.message ?: title, code)
        problem.setProperty("instructionId", ex.instruction.id.toString())
        problem.setProperty("replayed", ex.replayed)
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header("Idempotency-Replayed", ex.replayed.toString())
            .body(problem)
    }

    @ExceptionHandler(
        UnknownAccountException::class,
        UnknownSettlementException::class,
        DuplicateAccountReferenceException::class,
        InvalidMoneyException::class,
        InvalidCurrencyException::class,
        InvalidAccountReferenceException::class,
        InvalidAccountNameException::class,
        InvalidSettlementException::class,
        MissingIdempotencyKeyException::class,
        InvalidIdempotencyKeyException::class,
        IdempotencyConflictException::class,
        InvalidStatusTransitionException::class,
        AccountNotZeroException::class,
        InsufficientFundsException::class,
        AccountNotOpenException::class,
        CurrencyMismatchException::class,
        BalanceLimitException::class,
    )
    fun domain(ex: DomainException): ResponseEntity<ProblemDetail> {
        val (status, title, code) = when (ex) {
            is UnknownAccountException -> Triple(HttpStatus.NOT_FOUND, "Unknown account", "UNKNOWN_ACCOUNT")
            is UnknownSettlementException -> Triple(HttpStatus.NOT_FOUND, "Unknown settlement", "UNKNOWN_SETTLEMENT")
            is DuplicateAccountReferenceException -> Triple(HttpStatus.CONFLICT, "Duplicate account", "DUPLICATE_ACCOUNT")
            is InvalidMoneyException -> Triple(HttpStatus.BAD_REQUEST, "Invalid amount", "INVALID_AMOUNT")
            is InvalidCurrencyException -> Triple(HttpStatus.BAD_REQUEST, "Invalid currency", "INVALID_CURRENCY")
            is InvalidAccountReferenceException,
            is InvalidAccountNameException,
            -> Triple(HttpStatus.BAD_REQUEST, "Invalid account", "INVALID_ACCOUNT")
            is InvalidSettlementException -> Triple(HttpStatus.BAD_REQUEST, "Invalid settlement", "INVALID_SETTLEMENT")
            is MissingIdempotencyKeyException ->
                Triple(HttpStatus.BAD_REQUEST, "Missing idempotency key", "MISSING_IDEMPOTENCY_KEY")
            is InvalidIdempotencyKeyException ->
                Triple(HttpStatus.BAD_REQUEST, "Invalid idempotency key", "INVALID_IDEMPOTENCY_KEY")
            is IdempotencyConflictException ->
                Triple(HttpStatus.CONFLICT, "Idempotency conflict", "IDEMPOTENCY_CONFLICT")
            is InvalidStatusTransitionException ->
                Triple(HttpStatus.CONFLICT, "Invalid status transition", "INVALID_STATUS_TRANSITION")
            is AccountNotZeroException -> Triple(HttpStatus.CONFLICT, "Account not zero", "ACCOUNT_NOT_ZERO")
            is InsufficientFundsException -> Triple(HttpStatus.CONFLICT, "Insufficient funds", "INSUFFICIENT_FUNDS")
            is AccountNotOpenException -> Triple(HttpStatus.CONFLICT, "Account not open", "ACCOUNT_NOT_OPEN")
            is CurrencyMismatchException ->
                Triple(HttpStatus.UNPROCESSABLE_ENTITY, "Currency mismatch", "CURRENCY_MISMATCH")
            is BalanceLimitException -> Triple(HttpStatus.UNPROCESSABLE_ENTITY, "Balance limit", "BALANCE_LIMIT")
            is SettlementRejectedException -> error("handled separately")
        }
        return json(status, title, ex.message ?: title, code)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(ex: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val fields = ex.bindingResult.fieldErrors.map { error ->
            mapOf(
                "field" to error.field,
                "message" to (error.defaultMessage ?: "invalid"),
            )
        }
        val names = ex.bindingResult.fieldErrors.map { it.field.substringAfterLast('.') }.toSet()
        val code = when (names) {
            setOf("minorUnits") -> "INVALID_AMOUNT"
            setOf("currency") -> "INVALID_CURRENCY"
            setOf("reference"), setOf("displayName") -> "INVALID_ACCOUNT"
            else -> "INVALID_REQUEST"
        }
        val title = when (code) {
            "INVALID_AMOUNT" -> "Invalid amount"
            "INVALID_CURRENCY" -> "Invalid currency"
            "INVALID_ACCOUNT" -> "Invalid account"
            else -> "Invalid request"
        }
        val problem = problem(HttpStatus.BAD_REQUEST, title, "Request validation failed", code)
        problem.setProperty("fields", fields)
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun unreadable(): ResponseEntity<ProblemDetail> =
        json(HttpStatus.BAD_REQUEST, "Invalid request", "Request body or path parameter is not readable", "INVALID_REQUEST")

    private fun json(status: HttpStatus, title: String, detail: String, code: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem(status, title, detail, code))

    private fun problem(status: HttpStatus, title: String, detail: String, code: String): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        problem.setProperty("error", code)
        return problem
    }
}
