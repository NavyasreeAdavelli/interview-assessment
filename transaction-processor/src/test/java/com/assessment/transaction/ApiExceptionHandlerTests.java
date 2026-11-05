package com.assessment.transaction;

import com.assessment.transaction.api.ApiExceptionHandler;
import com.assessment.transaction.api.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiExceptionHandlerTests {
	@Test
	void unexpectedErrorResponseDoesNotExposeInternalExceptionDetails() {
		ApiErrorResponse response = new ApiExceptionHandler()
				.handleUnexpected(new IllegalStateException("sensitive internal failure"))
				.getBody();

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.status());
		assertEquals("INTERNAL_ERROR", response.errorCode());
		assertEquals("An unexpected error occurred", response.message());
		assertFalse(response.message().contains("sensitive internal failure"));
	}
}