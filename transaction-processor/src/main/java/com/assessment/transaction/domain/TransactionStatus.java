package com.assessment.transaction.domain;

public enum TransactionStatus {
	RECEIVED,
	WAITING_FOR_SEQUENCE,
	WAITING_FOR_ORIGINAL,
	PROCESSING,
	PROCESSED,
	RETRY_PENDING,
	FAILED,
	SKIPPED
}