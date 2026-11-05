package com.assessment.transaction.application;

public class LostProcessingClaimException extends RuntimeException {
	public LostProcessingClaimException(String message) {
		super(message);
	}
}