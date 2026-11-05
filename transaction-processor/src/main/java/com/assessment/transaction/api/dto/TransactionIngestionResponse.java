package com.assessment.transaction.api.dto;

import com.assessment.transaction.domain.SubmissionOutcome;

public record TransactionIngestionResponse(
		SubmissionOutcome submissionOutcome,
		TransactionResponse transaction) {
}