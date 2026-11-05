package com.assessment.transaction.api.dto;

import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
		String transactionId,
		String accountId,
		long sequenceNumber,
		TransactionType transactionType,
		BigDecimal amount,
		String currency,
		String reversalOfTransactionId,
		TransactionStatus status,
		int attemptCount,
		Instant nextAttemptAt,
		String failureReason,
		Instant createdAt,
		Instant updatedAt) {
}