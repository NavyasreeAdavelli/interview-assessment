package com.assessment.transaction.application;

public class TransactionConflictException extends RuntimeException {
	public TransactionConflictException(String message) {
		super(message);
	}

	public TransactionConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}