package com.assessment.transaction.application;

import com.assessment.transaction.api.dto.TransactionIngestionResponse;
import com.assessment.transaction.api.dto.TransactionRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class TransactionIngestionService {
	private static final int MAX_CONCURRENT_WRITE_ATTEMPTS = 3;
	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

	private final Validator validator;
	private final TransactionIngestionWriter writer;

	public TransactionIngestionService(Validator validator, TransactionIngestionWriter writer) {
		this.validator = validator;
		this.writer = writer;
	}

	public TransactionIngestionResponse ingest(TransactionRequest request, String idempotencyKey) {
		validateRequest(request, idempotencyKey);
		String payloadFingerprint = TransactionPayloadFingerprint.of(request);
		DataIntegrityViolationException lastConflict = null;

		for (int attempt = 0; attempt < MAX_CONCURRENT_WRITE_ATTEMPTS; attempt++) {
			try {
				return writer.write(request, idempotencyKey, payloadFingerprint);
			} catch (DataIntegrityViolationException conflict) {
				lastConflict = conflict;
			}
		}

		throw new TransactionConflictException(
				"Submission conflicted with concurrent data; retry with the same idempotency key", lastConflict);
	}

	private void validateRequest(TransactionRequest request, String idempotencyKey) {
		if (request == null) {
			throw new InvalidIngestionRequestException("Transaction request is required");
		}
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new InvalidIngestionRequestException("Idempotency-Key is required");
		}
		if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new InvalidIngestionRequestException("Idempotency-Key must not exceed 128 characters");
		}

		Set<ConstraintViolation<TransactionRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
	}
}