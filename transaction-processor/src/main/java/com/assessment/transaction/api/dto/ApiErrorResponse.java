package com.assessment.transaction.api.dto;

import java.time.Instant;

public record ApiErrorResponse(
		Instant timestamp,
		int status,
		String errorCode,
		String message) {
}