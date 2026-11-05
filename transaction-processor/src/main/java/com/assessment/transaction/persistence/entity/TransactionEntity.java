package com.assessment.transaction.persistence.entity;

import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transactions",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_transaction_account_sequence", columnNames = {"account_id", "sequence_number"}),
				@UniqueConstraint(name = "uk_transaction_reversal_target", columnNames = "reversal_of_transaction_id")
		},
		indexes = @Index(name = "idx_transaction_status_retry", columnList = "status, next_attempt_at"))
public class TransactionEntity {
	@Id
	@Column(name = "transaction_id", nullable = false, updatable = false, length = 100)
	private String transactionId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, updatable = false)
	private AccountEntity account;

	@Column(name = "sequence_number", nullable = false, updatable = false)
	private long sequenceNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "transaction_type", nullable = false, updatable = false, length = 16)
	private TransactionType transactionType;

	@Column(nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal amount;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(name = "reversal_of_transaction_id", length = 100, updatable = false)
	private String reversalOfTransactionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private TransactionStatus status = TransactionStatus.RECEIVED;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "processing_claim_id", length = 36)
	private String processingClaimId;

	@Column(name = "processing_claimed_at")
	private Instant processingClaimedAt;

	@Column(name = "failure_reason", length = 1000)
	private String failureReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TransactionEntity() {
	}

	public TransactionEntity(String transactionId, AccountEntity account, long sequenceNumber,
			TransactionType transactionType, BigDecimal amount, String currency,
			String reversalOfTransactionId) {
		this.transactionId = Objects.requireNonNull(transactionId);
		this.account = Objects.requireNonNull(account);
		this.sequenceNumber = sequenceNumber;
		this.transactionType = Objects.requireNonNull(transactionType);
		this.amount = Objects.requireNonNull(amount);
		this.currency = Objects.requireNonNull(currency);
		this.reversalOfTransactionId = reversalOfTransactionId;
		this.createdAt = Instant.now();
		this.updatedAt = createdAt;
	}

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
		if (updatedAt == null) {
			updatedAt = createdAt;
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	public void markWaitingForSequence() {
		transitionFrom(TransactionStatus.RECEIVED, TransactionStatus.WAITING_FOR_SEQUENCE);
	}

	public void markWaitingForOriginal() {
		if (status != TransactionStatus.RECEIVED
				&& status != TransactionStatus.WAITING_FOR_SEQUENCE
				&& status != TransactionStatus.WAITING_FOR_ORIGINAL
				&& status != TransactionStatus.PROCESSING) {
			throw new IllegalStateException("Cannot wait for original transaction from status " + status);
		}
		status = TransactionStatus.WAITING_FOR_ORIGINAL;
		nextAttemptAt = null;
		clearProcessingClaim();
	}

	public void markProcessing() {
		markProcessing(UUID.randomUUID().toString(), Instant.now());
	}

	public void markProcessing(String claimId, Instant claimedAt) {
		if (status != TransactionStatus.RECEIVED
				&& status != TransactionStatus.WAITING_FOR_SEQUENCE
				&& status != TransactionStatus.WAITING_FOR_ORIGINAL
				&& status != TransactionStatus.RETRY_PENDING) {
			throw new IllegalStateException("Transaction cannot be processed from status " + status);
		}
		status = TransactionStatus.PROCESSING;
		attemptCount++;
		nextAttemptAt = null;
		processingClaimId = Objects.requireNonNull(claimId);
		processingClaimedAt = Objects.requireNonNull(claimedAt);
	}

	public void markProcessed() {
		transitionFrom(TransactionStatus.PROCESSING, TransactionStatus.PROCESSED);
		nextAttemptAt = null;
		failureReason = null;
		clearProcessingClaim();
	}

	public void markRetryPending(Instant retryAt, String reason) {
		transitionFrom(TransactionStatus.PROCESSING, TransactionStatus.RETRY_PENDING);
		nextAttemptAt = Objects.requireNonNull(retryAt);
		failureReason = Objects.requireNonNull(reason);
		clearProcessingClaim();
	}

	public void markManuallyRetried(Instant retryAt) {
		transitionFrom(TransactionStatus.FAILED, TransactionStatus.RETRY_PENDING);
		nextAttemptAt = Objects.requireNonNull(retryAt);
		clearProcessingClaim();
	}

	public void markFailed(String reason) {
		transitionFrom(TransactionStatus.PROCESSING, TransactionStatus.FAILED);
		nextAttemptAt = null;
		failureReason = Objects.requireNonNull(reason);
		clearProcessingClaim();
	}

	public void markSkipped() {
		transitionFrom(TransactionStatus.FAILED, TransactionStatus.SKIPPED);
		nextAttemptAt = null;
		clearProcessingClaim();
	}

	private void clearProcessingClaim() {
		processingClaimId = null;
		processingClaimedAt = null;
	}

	private void transitionFrom(TransactionStatus expected, TransactionStatus next) {
		if (status != expected) {
			throw new IllegalStateException("Cannot transition transaction from " + status + " to " + next);
		}
		status = next;
	}

	public String getTransactionId() {
		return transactionId;
	}

	public AccountEntity getAccount() {
		return account;
	}

	public long getSequenceNumber() {
		return sequenceNumber;
	}

	public TransactionType getTransactionType() {
		return transactionType;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getReversalOfTransactionId() {
		return reversalOfTransactionId;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public String getProcessingClaimId() {
		return processingClaimId;
	}

	public Instant getProcessingClaimedAt() {
		return processingClaimedAt;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}