package com.assessment.transaction.application;

import com.assessment.transaction.api.dto.TransactionResponse;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import org.springframework.stereotype.Component;

@Component
public class TransactionResponseMapper {
	public TransactionResponse toResponse(TransactionEntity transaction) {
		return new TransactionResponse(
				transaction.getTransactionId(),
				transaction.getAccount().getAccountId(),
				transaction.getSequenceNumber(),
				transaction.getTransactionType(),
				transaction.getAmount(),
				transaction.getCurrency(),
				transaction.getReversalOfTransactionId(),
				transaction.getStatus(),
				transaction.getAttemptCount(),
				transaction.getNextAttemptAt(),
				transaction.getFailureReason(),
				transaction.getCreatedAt(),
				transaction.getUpdatedAt());
	}
}