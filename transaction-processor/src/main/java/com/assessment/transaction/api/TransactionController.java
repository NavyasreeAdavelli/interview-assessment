package com.assessment.transaction.api;

import com.assessment.transaction.api.dto.TransactionIngestionResponse;
import com.assessment.transaction.api.dto.TransactionRecoveryRequest;
import com.assessment.transaction.api.dto.TransactionRequest;
import com.assessment.transaction.api.dto.TransactionResponse;
import com.assessment.transaction.application.TransactionIngestionService;
import com.assessment.transaction.application.TransactionQueryService;
import com.assessment.transaction.application.TransactionRecoveryService;
import com.assessment.transaction.domain.SubmissionOutcome;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
	private final TransactionIngestionService ingestionService;
	private final TransactionQueryService queryService;
	private final TransactionRecoveryService recoveryService;

	public TransactionController(TransactionIngestionService ingestionService,
			TransactionQueryService queryService, TransactionRecoveryService recoveryService) {
		this.ingestionService = ingestionService;
		this.queryService = queryService;
		this.recoveryService = recoveryService;
	}

	@PostMapping
	public ResponseEntity<TransactionIngestionResponse> ingest(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody TransactionRequest request) {
		TransactionIngestionResponse response = ingestionService.ingest(request, idempotencyKey);
		if (response.submissionOutcome() == SubmissionOutcome.DUPLICATE) {
			return ResponseEntity.ok(response);
		}
		return ResponseEntity.accepted().body(response);
	}

	@GetMapping("/{transactionId}")
	public TransactionResponse getTransaction(@PathVariable String transactionId) {
		return queryService.getTransaction(transactionId);
	}

	@PostMapping("/{transactionId}/retry")
	public ResponseEntity<TransactionResponse> retry(
			@PathVariable String transactionId,
			@Valid @RequestBody TransactionRecoveryRequest request) {
		recoveryService.retryFailedTransaction(transactionId, request.reason(), request.operatorIdentity());
		return ResponseEntity.accepted().body(queryService.getTransaction(transactionId));
	}

	@PostMapping("/{transactionId}/skip")
	public TransactionResponse skip(
			@PathVariable String transactionId,
			@Valid @RequestBody TransactionRecoveryRequest request) {
		recoveryService.skipFailedTransaction(transactionId, request.reason(), request.operatorIdentity());
		return queryService.getTransaction(transactionId);
	}
}