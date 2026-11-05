package com.assessment.transaction.application;

import com.assessment.transaction.domain.TransactionOperationType;
import com.assessment.transaction.domain.TransactionStatus;
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

import java.time.Clock;

@Service
public class TransactionRecoveryService {
	private static final Logger logger = LoggerFactory.getLogger(TransactionRecoveryService.class);
	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final TransactionOperationRepository operationRepository;
	private final Clock clock;

	public TransactionRecoveryService(AccountRepository accountRepository,
			TransactionRepository transactionRepository, TransactionOperationRepository operationRepository,
			Clock clock) {
		this.accountRepository = accountRepository;
		this.transactionRepository = transactionRepository;
		this.operationRepository = operationRepository;
		this.clock = clock;
	}

	@Transactional
	public void retryFailedTransaction(String transactionId, String reason, String operatorIdentity) {
		validateOperationDetails(reason, operatorIdentity);
		LockedFailedTransaction locked = lockFailedExpectedTransaction(transactionId);
		locked.transaction().markManuallyRetried(clock.instant());
		operationRepository.saveAndFlush(new TransactionOperationEntity(
				locked.transaction(), TransactionOperationType.MANUAL_RETRY, reason, operatorIdentity));
		logger.info("Transaction manually retried transactionId={}", transactionId);
	}

	@Transactional
	public void skipFailedTransaction(String transactionId, String reason, String operatorIdentity) {
		validateOperationDetails(reason, operatorIdentity);
		LockedFailedTransaction locked = lockFailedExpectedTransaction(transactionId);
		locked.transaction().markSkipped();
		locked.account().advanceSequence(locked.transaction().getSequenceNumber());
		operationRepository.saveAndFlush(new TransactionOperationEntity(
				locked.transaction(), TransactionOperationType.SKIP, reason, operatorIdentity));
		logger.info("Transaction skipped transactionId={}", transactionId);
	}

	private LockedFailedTransaction lockFailedExpectedTransaction(String transactionId) {
		String accountId = transactionRepository.findAccountIdByTransactionId(transactionId)
				.orElseThrow(() -> new TransactionNotFoundException(transactionId));
		AccountEntity account = accountRepository.findByAccountIdForUpdate(accountId)
				.orElseThrow(() -> new TransactionNotFoundException(transactionId));
		TransactionEntity transaction = transactionRepository.findByIdForUpdate(transactionId)
				.orElseThrow(() -> new TransactionNotFoundException(transactionId));
		if (transaction.getStatus() != TransactionStatus.FAILED) {
			throw new TransactionOperationNotAllowedException("Only FAILED transactions can be retried or skipped");
		}
		if (transaction.getSequenceNumber() != account.getExpectedSequenceNumber()) {
			throw new TransactionOperationNotAllowedException("Only the blocking account sequence can be retried or skipped");
		}
		return new LockedFailedTransaction(account, transaction);
	}

	private void validateOperationDetails(String reason, String operatorIdentity) {
		if (reason == null || reason.isBlank() || reason.length() > 1000) {
			throw new IllegalArgumentException("A reason of 1 to 1000 characters is required");
		}
		if (operatorIdentity == null || operatorIdentity.isBlank() || operatorIdentity.length() > 100) {
			throw new IllegalArgumentException("An operator identity of 1 to 100 characters is required");
		}
	}

	private record LockedFailedTransaction(AccountEntity account, TransactionEntity transaction) {
	}
}