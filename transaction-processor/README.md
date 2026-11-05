# Transaction Processor

A Spring Boot service that accepts idempotent transaction requests, processes them in the background in per-account sequence order, and exposes transaction status and operational recovery APIs.

## Run

Requirements: Java 21, the included Maven wrapper, and Spring Boot 4.1.2.

```bash
./mvnw test
./mvnw spring-boot:run
```

The service uses an H2 in-memory database. Its data is lost when the process stops. The polling worker is enabled by default and polls every second.

Retry defaults are `transaction-processing.max-attempts: 3`, `transaction-processing.retry-backoff: 2s`, and `transaction-processing.processing-claim-timeout: 30s`. To exercise a deterministic transient failure, configure one or more transaction IDs under `transaction-processing.simulated-transient-failure-transaction-ids`; each configured transaction fails before its first balance or ledger side effect.

## Architecture

- `api`: REST controllers, request/response DTOs, and centralized exception mapping. Controllers do not return persistence entities.
- `application`: ingestion and idempotency, transaction queries, summary aggregation, recovery, attempt reservation, atomic business processing, and failure recording.
- `persistence`: JPA entities and repositories. Constraints enforce unique transaction IDs, idempotency keys, account sequences, reversal targets, and ledger entries.
- `worker`: scheduled polling that discovers candidate accounts and delegates processing.

Open Session in View is disabled. Read services map entities to response DTOs inside read-only transactions.

## API

### Ingest

`POST /api/v1/transactions` requires the `Idempotency-Key` header.

```http
POST /api/v1/transactions
Idempotency-Key: request-1001
Content-Type: application/json
```

```json
{
  "transactionId": "TXN-1001",
  "accountId": "ACC-100",
  "sequenceNumber": 1,
  "transactionType": "CREDIT",
  "amount": 125.00,
  "currency": "USD"
}
```

A new request returns `202 Accepted`:

```json
{
  "submissionOutcome": "ACCEPTED",
  "transaction": {
    "transactionId": "TXN-1001",
    "accountId": "ACC-100",
    "sequenceNumber": 1,
    "transactionType": "CREDIT",
    "amount": 125.0000,
    "currency": "USD",
    "reversalOfTransactionId": null,
    "status": "RECEIVED",
    "attemptCount": 0,
    "nextAttemptAt": null,
    "failureReason": null,
    "createdAt": "2026-11-05T12:00:00Z",
    "updatedAt": "2026-11-05T12:00:00Z"
  }
}
```

An identical idempotency-key replay returns the original accepted outcome (`202`). A matching business transaction submitted with a different key returns `200 OK` and `submissionOutcome: "DUPLICATE"`. Reusing a key or transaction ID with different business details returns `409 Conflict`.

For `REVERSAL`, include `reversalOfTransactionId`; the reversal amount and currency must match the successfully processed original.

## Processing Rules

Lifecycle states include `RECEIVED`, `WAITING_FOR_SEQUENCE`, `WAITING_FOR_ORIGINAL`, `PROCESSING`, `PROCESSED`, `RETRY_PENDING`, `FAILED`, and `SKIPPED`. The polling worker selects candidate accounts, locks each account, and processes only its expected sequence. Higher sequences remain waiting; an absent expected sequence is represented by the account's unchanged expected-sequence value and the waiting transaction status.

`CREDIT` adds the amount. `DEBIT` subtracts only when sufficient funds exist. `REVERSAL` is a full inverse of a successfully processed `CREDIT` or `DEBIT` on the same account and currency, for the same amount, and an original can be reversed only once. The reversal itself is subject to account sequencing.

Balance, ledger entry, transaction status, and expected sequence are committed atomically. A unique ledger-to-transaction constraint prevents a second ledger entry. Failed business transactions do not advance the sequence; manual skip is auditable and advances the blocking sequence without a balance or ledger effect.

## Retry and Claim Recovery

Attempt reservation commits an attempt count, `PROCESSING` state, claim UUID, and claim timestamp before the business transaction starts. The business processor reacquires the account lock and validates that claim UUID and expected sequence before any side effect. A transient failure rolls back the business transaction; a separate transaction records `RETRY_PENDING` and its due time, or `FAILED` after the attempt limit.

If a `PROCESSING` claim exceeds the configured timeout, the worker reclaims it while holding the same account-level pessimistic lock. It records a `PROCESSING_CLAIM_RECOVERY` audit operation, then schedules a retry or marks the transaction `FAILED` if attempts are exhausted. The claim UUID fences off a delayed worker from applying effects after reclamation. An active business processor holds the account lock until commit or rollback, so the reaper waits and then observes the committed state before deciding whether recovery is needed.

### Status

`GET /api/v1/transactions/{transactionId}` returns the flat transaction response shown above, or `404 Not Found` when the ID is unknown. JPA entities are not returned from API endpoints.

### Processing summary

`GET /api/v1/processing/summary` returns database-aggregated counts:

```json
{
  "transactionCountsByStatus": {
    "RECEIVED": 1,
    "WAITING_FOR_SEQUENCE": 0,
    "WAITING_FOR_ORIGINAL": 0,
    "PROCESSING": 0,
    "PROCESSED": 4,
    "RETRY_PENDING": 1,
    "FAILED": 0,
    "SKIPPED": 0
  },
  "totalAcceptedTransactions": 6,
  "duplicateSubmissionCount": 1,
  "waitingForSequenceCount": 0,
  "waitingForReferenceCount": 0,
  "retryPendingCount": 1,
  "failedCount": 0,
  "skippedCount": 0
}
```

### Manual recovery

Both recovery routes require a non-empty reason and operator identity:

```http
POST /api/v1/transactions/TXN-1001/retry
Content-Type: application/json
```

```json
{
  "reason": "Upstream issue resolved",
  "operatorIdentity": "operator-7"
}
```

`POST /api/v1/transactions/{transactionId}/retry` returns `202 Accepted` with the updated transaction. `POST /api/v1/transactions/{transactionId}/skip` returns `200 OK` with the updated transaction. Both operations are limited to a blocking `FAILED` transaction and persist an audit record. Skip advances the sequence without creating a ledger entry or changing the balance.

### Errors

Errors use one response shape and never include exception stack traces:

```json
{
  "timestamp": "2026-11-05T12:00:00Z",
  "status": 409,
  "errorCode": "TRANSACTION_CONFLICT",
  "message": "Transaction ID was already used with different business details"
}
```

Validation and malformed requests return `400`; conflicts and disallowed recovery operations return `409`; unknown transactions return `404`; unexpected errors return a sanitized `500` response.

## Design Notes

- A unique idempotency key and unique transaction ID constraints protect concurrent submissions; canonical payload fingerprints ignore JSON property order and decimal scale.
- Processing locks the account, claims only its expected sequence, and commits balance, ledger entry, transaction status, and sequence advancement atomically.
- Transient failure metadata is committed separately from the rolled-back business attempt. Retries run only when due and while their sequence is expected.
- Database constraints protect transaction IDs, idempotency keys, account sequences, reversal targets, and one-ledger-entry-per-transaction.
- H2 is suitable for this assessment but is not durable or shared between service instances. Production deployment should use a durable database and verify pessimistic locking, time-source consistency, and lease behavior against its target database. Authentication is not implemented; operator identity is supplied by the request. External side effects are not covered by the local database transaction; use downstream idempotency or an outbox if those are added.

## Assessment Scenarios

Submit sequence `2` before sequence `1` for the same account; sequence `2` remains waiting until sequence `1` is processed:

```json
{
  "transactionId": "TXN-2002",
  "accountId": "ACC-201",
  "sequenceNumber": 2,
  "transactionType": "DEBIT",
  "amount": 40.00,
  "currency": "USD"
}
```

A negative amount is rejected with `400`. A valid debit with insufficient funds becomes `FAILED` without changing the balance or sequence. Configure a credit transaction ID in the simulated failure list to observe `RETRY_PENDING` followed by a later successful attempt. A reversal references a processed original and uses the same account, currency, and amount:

```json
{
  "transactionId": "TXN-2003-REV",
  "accountId": "ACC-201",
  "sequenceNumber": 3,
  "transactionType": "REVERSAL",
  "amount": 100.00,
  "currency": "USD",
  "reversalOfTransactionId": "TXN-2003"
}
```

Inspect `GET /api/v1/processing/summary` for lifecycle and retry counts. Retry or skip a blocking `FAILED` transaction using the recovery routes above; retry preserves the same transaction ID and sequence, while skip requires an auditable reason and operator identity.