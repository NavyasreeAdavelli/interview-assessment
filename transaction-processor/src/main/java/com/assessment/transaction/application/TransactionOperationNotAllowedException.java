package com.assessment.transaction.application;

public class TransactionOperationNotAllowedException extends RuntimeException {
	public TransactionOperationNotAllowedException(String message) {
		super(message);
	}
}