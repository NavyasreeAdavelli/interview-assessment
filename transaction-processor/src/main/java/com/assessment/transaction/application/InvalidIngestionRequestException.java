package com.assessment.transaction.application;

public class InvalidIngestionRequestException extends RuntimeException {
	public InvalidIngestionRequestException(String message) {
		super(message);
	}
}