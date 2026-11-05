package com.assessment.transaction.application;

import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.domain.TransactionOperationType;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.config.TransactionProcessingProperties;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.entity.TransactionOperationEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.TransactionOperationRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionAttemptService {
	private static final Logger logger = LoggerFactory.getLogger(TransactionAttemptService.class);
	private static final List<TransactionStatus> POLLABLE_STATUSES = List.of(
			TransactionStatus.RECEIVED,
			TransactionStatus.WAITING_FOR_SEQUENCE,
			TransactionStatus.WAITING_FOR_ORIGINAL,
			TransactionStatus.RETRY_PENDING);

	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final TransactionOperationRepository operationRepository;
	private final TransactionProcessingProperties properties;

	public TransactionAttemptService(AccountRepository accountRepository,
			TransactionRepository transactionRepository, TransactionOperationRepository operationRepository,
			TransactionProcessingProperties properties) {
		this.accountRepository = accountRepository;
		this.transactionRepository = transactionRepository;
		this.operationRepository = operationRepository;
		this.properties = properties;
	}

	@Transactional
	public Optional<ProcessingClaim> beginNextAttempt(String accountId, Instant now) {
		Optional<AccountEntity> lockedAccount = accountRepository.findByAccountIdForUpdate(accountId);
		if (lockedAccount.isEmpty()) {
			return Optional.empty();
		}
		AccountEntity account = lockedAccount.get();
		Optional<TransactionEntity> expected = transactionRepository.findExpectedSequenceForUpdate(
				accountId, account.getExpectedSequenceNumber());
		if (expected.isEmpty()) {
			markHigherSequencesWaiting(account);
			return Optional.empty();
		}

		TransactionEntity transaction = expected.get();
		if (transaction.getStatus() == TransactionStatus.PROCESSING) {
			if (!isClaimExpired(transaction, now)) {
				return Optional.empty();
			}
			recoverExpiredClaim(transaction, now);
			return Optional.empty();
		}
		if (!POLLABLE_STATUSES.contains(transaction.getStatus())) {
			return Optional.empty();
		}
		if (transaction.getStatus() == TransactionStatus.RETRY_PENDING
				&& transaction.getNextAttemptAt() != null && transaction.getNextAttemptAt().isAfter(now)) {
			return Optional.empty();
		}

		if (transaction.getTransactionType() == TransactionType.REVERSAL) {
			Optional<TransactionEntity> original = transactionRepository
					.findById(transaction.getReversalOfTransactionId());
			if (original.isEmpty() || original.get().getStatus() != TransactionStatus.PROCESSED) {
				transaction.markWaitingForOriginal();
				return Optional.empty();
			}
		}

		String claimId = UUID.randomUUID().toString();
		transaction.markProcessing(claimId, now);
		return Optional.of(new ProcessingClaim(transaction.getTransactionId(), claimId));
	}

	private boolean isClaimExpired(TransactionEntity transaction, Instant now) {
		Instant claimedAt = transaction.getProcessingClaimedAt();
		return claimedAt == null
				|| !claimedAt.plus(properties.getProcessingClaimTimeout()).isAfter(now);
	}

	private void recoverExpiredClaim(TransactionEntity transaction, Instant now) {
		String reason = "Processing claim expired before the attempt completed";
		String previousFailure = transaction.getFailureReason();
		String auditReason = previousFailure == null || previousFailure.isBlank()
				? reason
				: reason + "; previous failure: " + previousFailure;
		if (auditReason.length() > 1000) {
			auditReason = auditReason.substring(0, 1000);
		}
		if (transaction.getAttemptCount() >= properties.getMaxAttempts()) {
			transaction.markFailed(reason);
		} else {
			transaction.markRetryPending(now.plus(properties.getRetryBackoff()), reason);
		}
		operationRepository.saveAndFlush(new TransactionOperationEntity(
				transaction, TransactionOperationType.PROCESSING_CLAIM_RECOVERY, auditReason, "system"));
		logger.warn("Recovered expired processing claim transactionId={} attempts={} status={}",
				transaction.getTransactionId(), transaction.getAttemptCount(), transaction.getStatus());
	}

	private void markHigherSequencesWaiting(AccountEntity account) {
		List<TransactionEntity> higherSequences = transactionRepository
				.findByAccount_AccountIdAndSequenceNumberGreaterThanAndStatusIn(
						account.getAccountId(), account.getExpectedSequenceNumber(), POLLABLE_STATUSES);
		for (TransactionEntity transaction : higherSequences) {
			if (transaction.getStatus() == TransactionStatus.RECEIVED) {
				transaction.markWaitingForSequence();
			}
		}
	}

	public record ProcessingClaim(String transactionId, String claimId) {
	}
}