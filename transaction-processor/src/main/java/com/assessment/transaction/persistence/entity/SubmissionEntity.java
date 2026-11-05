package com.assessment.transaction.persistence.entity;

import com.assessment.transaction.domain.SubmissionOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "transaction_submissions")
public class SubmissionEntity {
	@Id
	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 64)
	private String payloadFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 16)
	private SubmissionOutcome outcome;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transaction_id", nullable = false, updatable = false)
	private TransactionEntity transaction;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected SubmissionEntity() {
	}

	public SubmissionEntity(String idempotencyKey, String payloadFingerprint,
			SubmissionOutcome outcome, TransactionEntity transaction) {
		this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
		this.payloadFingerprint = Objects.requireNonNull(payloadFingerprint);
		this.outcome = Objects.requireNonNull(outcome);
		this.transaction = Objects.requireNonNull(transaction);
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getPayloadFingerprint() {
		return payloadFingerprint;
	}

	public SubmissionOutcome getOutcome() {
		return outcome;
	}

	public TransactionEntity getTransaction() {
		return transaction;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}