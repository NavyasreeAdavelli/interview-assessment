package com.assessment.transaction.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "accounts")
public class AccountEntity {
	@Id
	@Column(name = "account_id", nullable = false, updatable = false, length = 100)
	private String accountId;

	@Column(nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal balance = BigDecimal.ZERO;

	@Column(name = "expected_sequence_number", nullable = false)
	private long expectedSequenceNumber = 1;

	@Version
	private long version;

	protected AccountEntity() {
	}

	public AccountEntity(String accountId, String currency) {
		this.accountId = Objects.requireNonNull(accountId);
		this.currency = Objects.requireNonNull(currency);
	}

	public String getAccountId() {
		return accountId;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public long getExpectedSequenceNumber() {
		return expectedSequenceNumber;
	}

	public long getVersion() {
		return version;
	}

	public void applyBalanceDelta(BigDecimal amountDelta) {
		balance = balance.add(Objects.requireNonNull(amountDelta));
	}

	public void advanceSequence(long processedSequenceNumber) {
		if (processedSequenceNumber != expectedSequenceNumber) {
			throw new IllegalStateException("Only the expected account sequence can be advanced");
		}
		expectedSequenceNumber++;
	}
}