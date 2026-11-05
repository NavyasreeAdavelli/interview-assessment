package com.assessment.transaction.api;

import com.assessment.transaction.api.dto.ApiErrorResponse;
import com.assessment.transaction.application.InvalidIngestionRequestException;
import com.assessment.transaction.application.TransactionConflictException;
import com.assessment.transaction.application.TransactionNotFoundException;
import com.assessment.transaction.application.TransactionOperationNotAllowedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
	private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(TransactionConflictException.class)
	public ResponseEntity<ApiErrorResponse> handleConflict(TransactionConflictException exception) {
		return error(HttpStatus.CONFLICT, "TRANSACTION_CONFLICT", exception.getMessage());
	}

	@ExceptionHandler(TransactionNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNotFound(TransactionNotFoundException exception) {
		return error(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", exception.getMessage());
	}

	@ExceptionHandler(TransactionOperationNotAllowedException.class)
	public ResponseEntity<ApiErrorResponse> handleOperationNotAllowed(
			TransactionOperationNotAllowedException exception) {
		return error(HttpStatus.CONFLICT, "OPERATION_NOT_ALLOWED", exception.getMessage());
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
		String header = exception.getHeaderName();
		return error(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Required header '" + header + "' is missing");
	}

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			ConstraintViolationException.class,
			InvalidIngestionRequestException.class,
			IllegalArgumentException.class})
	public ResponseEntity<ApiErrorResponse> handleValidation(Exception exception) {
		return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed");
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleMalformedBody(HttpMessageNotReadableException exception) {
		return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is malformed or invalid");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
		logger.error("Unexpected API error type={}", exception.getClass().getSimpleName());
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
	}

	private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String errorCode, String message) {
		return ResponseEntity.status(status)
				.body(new ApiErrorResponse(Instant.now(), status.value(), errorCode, message));
	}
}