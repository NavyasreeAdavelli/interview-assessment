package com.assessment.transaction.api.dto;

import com.assessment.transaction.domain.TransactionStatus;

import java.util.Map;

public record ProcessingSummaryResponse(
		Map<TransactionStatus, Long> transactionCountsByStatus,
		long totalAcceptedTransactions,
		long duplicateSubmissionCount,
		long waitingForSequenceCount,
		long waitingForReferenceCount,
		long retryPendingCount,
		long failedCount,
		long skippedCount) {
	public ProcessingSummaryResponse {
		transactionCountsByStatus = Map.copyOf(transactionCountsByStatus);
	}
}