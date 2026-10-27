# Ledger API

A custody ledger for client cash. It opens accounts, settles deposits, withdrawals, and transfers, and serves the available position together with the ledger entries behind it.

The service is a Spring Boot application written in Kotlin. Amounts are integer minor units. The posting path does not use floating point.

## Domain

One bounded context: client cash custody and settlement.

The code is a small Domain-Driven Design model. `Account` is the aggregate, and its position is the available balance it owns. A `SettlementInstruction` is the aggregate that accepts or rejects a movement. Settling appends ledger entries and updates the position in the same database transaction. Rejecting appends nothing to the ledger and still stores the instruction.

Status moves in one direction: received, then settled or rejected. The committed instruction row holds the terminal status. `decidedAt` is the moment that decision was committed. A status-event row records each step, in order. Ledger rows and status events are insert-only. A database trigger rejects updates and deletes of ledger rows.

A deposit or withdrawal is single-sided. The external cash counterparty sits outside this ledger, so the sum of client positions changes. A transfer is double-entry between two client accounts, and the sum of those positions does not change.

Settlement requires an `Idempotency-Key` header. The same key and the same request replay the original outcome, including a rejection. The same key with a different request is refused. A replayed rejection is not attempted again. A later attempt needs a new key.

Posting rules:

- The account must exist.
- The account must be open. Frozen and closed accounts reject postings.
- The instruction currency must match the account currency.
- The amount must be a positive number of minor units, at most `1000000000000`.
- A debit must fit in the available balance. A position cannot go negative.
- A transfer must name two different accounts.
- Closing an account requires a zero balance. Closed is terminal. Frozen can return to open, or close.

Account rows are locked in id order before the balance is checked, so two movements on the same accounts cannot both pass that check.

Settled and rejected instructions are both kept, with the status transition stored beside the ledger. That record is what these compliant financial workflows need at this boundary: a known account, a positive amount, enough balance, and a durable outcome.

## Data

Flyway owns the schema. Tables: `accounts`, `positions`, `settlement_instructions`, `settlement_status_events`, `ledger_entries`.

`./gradlew bootRun` uses an in-memory H2 database, so a restart clears state. Docker Compose runs the same schema on PostgreSQL. The append-only trigger is the only dialect-specific script: H2 calls a small Java trigger, PostgreSQL uses a PL/pgSQL trigger. Both raise `ledger_entries is append-only`.

The position row is the current balance, updated under a row lock in the posting transaction. The ledger is the history. A check constraint keeps `positions.balance_minor` at zero or above.

## Run locally

Java 21.

```bash
./gradlew bootRun
```

The process listens on port 8080. The routes are open, so keep it off a public network.

Health:

```bash
curl -s localhost:8080/actuator/health
curl -s localhost:8080/actuator/health/liveness
curl -s localhost:8080/actuator/health/readiness
```

```json
{"status":"UP","groups":["liveness","readiness"]}
```

Open an account, then deposit 15.00 EUR (1500 minor units) into it. The first settlement call is `201`. The same key and body again is `200`, with `Idempotency-Replayed: true` and `"replayed": true`. The stored instruction is unchanged.

```bash
ACCT=$(curl -s -X POST localhost:8080/accounts \
  -H 'content-type: application/json' \
  -d '{"reference":"CUST-1001","displayName":"Client cash","currency":"EUR"}')
echo "$ACCT"
CASH=$(printf '%s' "$ACCT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -s -D - -X POST localhost:8080/settlements \
  -H 'content-type: application/json' \
  -H 'Idempotency-Key: deposit-1001-1' \
  -d "{
    \"type\": \"DEPOSIT\",
    \"creditAccountId\": \"$CASH\",
    \"amount\": {\"currency\": \"EUR\", \"minorUnits\": 1500},
    \"externalReference\": \"EXT-2026-0001\"
  }"
```

One run produced this account:

```json
{
  "id": "18a63327-c4c5-4c6d-abb3-d65e4b65bb11",
  "reference": "CUST-1001",
  "displayName": "Client cash",
  "currency": "EUR",
  "status": "OPEN",
  "createdAt": "2026-10-27T14:59:21.402871Z"
}
```

and this settlement:

```json
{
  "id": "b8c75b46-7811-45ce-9a4a-a774ae73a5a6",
  "type": "DEPOSIT",
  "status": "SETTLED",
  "debitAccountId": null,
  "creditAccountId": "18a63327-c4c5-4c6d-abb3-d65e4b65bb11",
  "amount": {"currency": "EUR", "minorUnits": 1500},
  "externalReference": "EXT-2026-0001",
  "rejectionReason": null,
  "createdAt": "2026-10-27T14:59:21.498140Z",
  "decidedAt": "2026-10-27T14:59:21.498140Z",
  "replayed": false,
  "statusHistory": [
    {"sequenceNo": 1, "status": "RECEIVED", "reason": null, "occurredAt": "2026-10-27T14:59:21.498140Z"},
    {"sequenceNo": 2, "status": "SETTLED", "reason": null, "occurredAt": "2026-10-27T14:59:21.498140Z"}
  ]
}
```

Balance and entries:

```bash
curl -s "localhost:8080/accounts/$CASH/balance"
curl -s "localhost:8080/accounts/$CASH/transactions"
```

```json
{"accountId":"18a63327-c4c5-4c6d-abb3-d65e4b65bb11","currency":"EUR","availableMinorUnits":1500,"updatedAt":"2026-10-27T14:59:21.498140Z"}
```

```json
{
  "accountId": "18a63327-c4c5-4c6d-abb3-d65e4b65bb11",
  "entries": [
    {
      "id": "1c8358fb-7eea-4621-9bcc-2e897f61c496",
      "settlementInstructionId": "b8c75b46-7811-45ce-9a4a-a774ae73a5a6",
      "direction": "CREDIT",
      "amount": {"currency": "EUR", "minorUnits": 1500},
      "createdAt": "2026-10-27T14:59:21.498140Z"
    }
  ]
}
```

Transfer 400 minor units to a second account:

```bash
OTHER=$(curl -s -X POST localhost:8080/accounts \
  -H 'content-type: application/json' \
  -d '{"reference":"CUST-1002","displayName":"Treasury cash","currency":"EUR"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -s -X POST localhost:8080/settlements \
  -H 'content-type: application/json' \
  -H 'Idempotency-Key: transfer-1001-1' \
  -d "{
    \"type\": \"TRANSFER\",
    \"debitAccountId\": \"$CASH\",
    \"creditAccountId\": \"$OTHER\",
    \"amount\": {\"currency\": \"EUR\", \"minorUnits\": 400},
    \"externalReference\": \"SET-2026-0001\"
  }"
```

After that transfer the first position is `1100` and the second is `400`. Withdrawing `900` from the second account is refused. The rejection is stored and can be read back.

```json
{
  "type": "about:blank",
  "title": "Insufficient funds",
  "status": 409,
  "detail": "Available balance is below the debit amount",
  "instance": "/settlements",
  "error": "INSUFFICIENT_FUNDS",
  "instructionId": "89b1fe85-7361-4239-93c8-234810db3ce1",
  "replayed": false
}
```

Errors use `application/problem+json`.

## Run with Docker

PostgreSQL and the API:

```bash
docker compose up --build
```

The database password in the compose file is for this local stack. The API container uses the `postgres` profile and listens on port 8080.

Database only, API on the host:

```bash
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=postgres ./gradlew bootRun
```

A restart keeps PostgreSQL data in the `ledger-pg` volume. A restart of the H2 process does not.

## Tests

```bash
./gradlew test
```

Unit tests cover status transitions, money rules, and posting math. API tests cover the HTTP contract, idempotency, rejection, the non-negative balance constraint, and the append-only trigger. They use H2 and do not need PostgreSQL.

GitHub Actions is the CI/CD gate: it runs the test suite and packages the jar on every push and pull request.

## Operations

Logs are JSON lines on stdout. Liveness is `/actuator/health/liveness`. Readiness is `/actuator/health/readiness` and includes the database. The process shuts down gracefully.

That stdout stream is the observability surface for this service. The image is the unit you would run on AWS ECS, or as a Lambda container image, with infrastructure as code describing the service, the database, and the log shipping around it.

Parameterized SQL, explicit invariants, and a single bounded context keep the secure/maintainable code easy to follow.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/accounts` | Open an account. `201` and a `Location` header. |
| GET | `/accounts/{id}` | Read an account. |
| GET | `/accounts/{id}/balance` | Available position. |
| GET | `/accounts/{id}/transactions` | Ledger entries, oldest first. |
| POST | `/accounts/{id}/freeze` | `OPEN` to `FROZEN`. |
| POST | `/accounts/{id}/unfreeze` | `FROZEN` to `OPEN`. |
| POST | `/accounts/{id}/close` | Close. The position must be zero. |
| POST | `/settlements` | Deposit, withdraw, or transfer. Requires `Idempotency-Key`. |
| GET | `/settlements/{id}` | Instruction and status history. |
| GET | `/actuator/health` | Process and database health. |

`Idempotency-Key` is 1 to 200 printable ASCII characters, without spaces. `type` is `DEPOSIT`, `WITHDRAWAL`, or `TRANSFER`. A deposit sets `creditAccountId`. A withdrawal sets `debitAccountId`. A transfer sets both. `externalReference` is optional, up to 128 characters, without control characters.

A new settlement is `201`. A replay of a settled instruction is `200`. A rejection uses the status in the table below, and a replay of that rejection keeps the same status. Both responses set `Idempotency-Replayed`.

| HTTP | `error` | When |
| --- | --- | --- |
| 400 | `INVALID_AMOUNT` | Zero, negative, or above the maximum. |
| 400 | `INVALID_CURRENCY` | Currency is not three letters A–Z. |
| 400 | `INVALID_REQUEST` | Malformed JSON or another field error. |
| 400 | `INVALID_SETTLEMENT` | A missing side, or a transfer of an account to itself. |
| 400 | `INVALID_ACCOUNT` | Reference or display name is not usable. |
| 400 | `MISSING_IDEMPOTENCY_KEY` | Settlement without the header. |
| 400 | `INVALID_IDEMPOTENCY_KEY` | Header is empty, too long, or not printable. |
| 404 | `UNKNOWN_ACCOUNT` | No such account. Not stored as an instruction. |
| 404 | `UNKNOWN_SETTLEMENT` | No such instruction. |
| 409 | `DUPLICATE_ACCOUNT` | Reference already exists. |
| 409 | `INSUFFICIENT_FUNDS` | Debit is above the available position. |
| 409 | `ACCOUNT_NOT_OPEN` | Frozen or closed. |
| 409 | `ACCOUNT_NOT_ZERO` | Close while a balance remains. |
| 409 | `INVALID_STATUS_TRANSITION` | The status change is not allowed. |
| 409 | `IDEMPOTENCY_CONFLICT` | Key reused with a different body. |
| 422 | `CURRENCY_MISMATCH` | Instruction currency differs from the account. |
| 422 | `BALANCE_LIMIT` | Credit would exceed the maximum position. |

## Layout

`account`, `settlement`, and `ledger` hold the model and the application services. `persistence` is JDBC. `api` is HTTP. Migrations live in `src/main/resources/db/migration`, with the trigger under `db/vendor`.
