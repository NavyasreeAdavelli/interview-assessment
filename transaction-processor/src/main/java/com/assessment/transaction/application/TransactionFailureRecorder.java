package com.assessment.transaction.application;

import com.assessment.transaction.config.TransactionProcessingProperties;
import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TransactionFailureRecorder {
	private static final Logger logger = LoggerFactory.getLogger(TransactionFailureRecorder.class);
	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final TransactionProcessingProperties properties;

	public TransactionFailureRecorder(AccountRepository accountRepository,
			TransactionRepository transactionRepository, TransactionProcessingProperties properties) {
		this.accountRepository = accountRepository;
		this.transactionRepository = transactionRepository;
		this.properties = properties;
	}

	@Transactional
	public void recordTransientFailure(String transactionId, String claimId, String reason, Instant failedAt) {
		TransactionEntity transaction = lockTransaction(transactionId);
		if (!isCurrentClaim(transaction, claimId)) {
			return;
		}
		if (transaction.getAttemptCount() >= properties.getMaxAttempts()) {
			transaction.markFailed(reason);
			logger.warn("Transaction exhausted retries transactionId={} attempts={}",
					transactionId, transaction.getAttemptCount());
			return;
		}
		transaction.markRetryPending(failedAt.plus(properties.getRetryBackoff()), reason);
		logger.warn("Transaction scheduled for retry transactionId={} attempts={} nextAttemptAt={}",
				transactionId, transaction.getAttemptCount(), transaction.getNextAttemptAt());
	}

	@Transactional
	public void recordPermanentFailure(String transactionId, String claimId, String reason) {
		TransactionEntity transaction = lockTransaction(transactionId);
		if (isCurrentClaim(transaction, claimId)) {
			transaction.markFailed(reason);
			logger.error("Transaction marked failed transactionId={} attempts={}",
					transactionId, transaction.getAttemptCount());
		}
	}

	private boolean isCurrentClaim(TransactionEntity transaction, String claimId) {
		return transaction != null
				&& transaction.getStatus() == TransactionStatus.PROCESSING
				&& claimId.equals(transaction.getProcessingClaimId());
	}

	private TransactionEntity lockTransaction(String transactionId) {
		String accountId = transactionRepository.findAccountIdByTransactionId(transactionId).orElse(null);
		if (accountId == null) {
			return null;
		}
		AccountEntity account = accountRepository.findByAccountIdForUpdate(accountId)
				.orElse(null);
		if (account == null) {
			return null;
		}
		return transactionRepository.findByIdForUpdate(transactionId).orElse(null);
	}
}