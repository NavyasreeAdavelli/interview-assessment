package com.assessment.transaction.application;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

@Service
public class TransactionProcessingService {
	private final TransactionAttemptService attemptService;
	private final TransactionAttemptProcessor attemptProcessor;
	private final TransactionFailureRecorder failureRecorder;
	private final Clock clock;

	public TransactionProcessingService(TransactionAttemptService attemptService,
			TransactionAttemptProcessor attemptProcessor, TransactionFailureRecorder failureRecorder,
			Clock clock) {
		this.attemptService = attemptService;
		this.attemptProcessor = attemptProcessor;
		this.failureRecorder = failureRecorder;
		this.clock = clock;
	}

	public void processNextForAccount(String accountId) {
		Optional<TransactionAttemptService.ProcessingClaim> claim = attemptService.beginNextAttempt(accountId, clock.instant());
		if (claim.isEmpty()) {
			return;
		}
		TransactionAttemptService.ProcessingClaim processingClaim = claim.get();

		try {
			attemptProcessor.applyAttempt(accountId, processingClaim.transactionId(), processingClaim.claimId());
		} catch (LostProcessingClaimException exception) {
			return;
		} catch (TransientTransactionProcessingException exception) {
			failureRecorder.recordTransientFailure(processingClaim.transactionId(), processingClaim.claimId(),
					exception.getMessage(), clock.instant());
		} catch (RuntimeException exception) {
			failureRecorder.recordPermanentFailure(processingClaim.transactionId(), processingClaim.claimId(),
					"Unexpected processing failure");
			throw exception;
		}
	}
}