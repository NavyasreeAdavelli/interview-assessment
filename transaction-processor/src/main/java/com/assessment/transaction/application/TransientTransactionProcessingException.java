package com.assessment.transaction.application;

public class TransientTransactionProcessingException extends RuntimeException {
	public TransientTransactionProcessingException(String message) {
		super(message);
	}
}