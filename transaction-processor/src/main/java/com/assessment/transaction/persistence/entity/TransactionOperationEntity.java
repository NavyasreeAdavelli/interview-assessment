package com.assessment.transaction.persistence.entity;

import com.assessment.transaction.domain.TransactionOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "transaction_operations")
public class TransactionOperationEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transaction_id", nullable = false, updatable = false)
	private TransactionEntity transaction;

	@Enumerated(EnumType.STRING)
	@Column(name = "operation_type", nullable = false, updatable = false, length = 16)
	private TransactionOperationType operationType;

	@Column(nullable = false, updatable = false, length = 1000)
	private String reason;

	@Column(name = "operator_identity", nullable = false, updatable = false, length = 100)
	private String operatorIdentity;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TransactionOperationEntity() {
	}

	public TransactionOperationEntity(TransactionEntity transaction,
			TransactionOperationType operationType, String reason, String operatorIdentity) {
		this.transaction = Objects.requireNonNull(transaction);
		this.operationType = Objects.requireNonNull(operationType);
		this.reason = Objects.requireNonNull(reason);
		this.operatorIdentity = Objects.requireNonNull(operatorIdentity);
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public TransactionEntity getTransaction() {
		return transaction;
	}

	public TransactionOperationType getOperationType() {
		return operationType;
	}

	public String getReason() {
		return reason;
	}

	public String getOperatorIdentity() {
		return operatorIdentity;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}