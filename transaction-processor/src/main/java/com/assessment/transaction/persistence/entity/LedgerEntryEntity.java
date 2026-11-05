package com.assessment.transaction.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transaction_id", nullable = false, unique = true, updatable = false)
	private TransactionEntity transaction;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, updatable = false)
	private AccountEntity account;

	@Column(name = "amount_delta", nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal amountDelta;

	@Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal balanceAfter;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected LedgerEntryEntity() {
	}

	public LedgerEntryEntity(TransactionEntity transaction, AccountEntity account,
			BigDecimal amountDelta, BigDecimal balanceAfter) {
		this.transaction = Objects.requireNonNull(transaction);
		this.account = Objects.requireNonNull(account);
		this.amountDelta = Objects.requireNonNull(amountDelta);
		this.balanceAfter = Objects.requireNonNull(balanceAfter);
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

	public AccountEntity getAccount() {
		return account;
	}

	public BigDecimal getAmountDelta() {
		return amountDelta;
	}

	public BigDecimal getBalanceAfter() {
		return balanceAfter;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}