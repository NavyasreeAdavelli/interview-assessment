package com.assessment.transaction;

import com.assessment.transaction.application.LostProcessingClaimException;
import com.assessment.transaction.application.TransactionAttemptProcessor;
import com.assessment.transaction.application.TransactionAttemptService;
import com.assessment.transaction.application.TransactionFailureRecorder;
import com.assessment.transaction.application.TransactionProcessingService;
import com.assessment.transaction.config.TransactionProcessingProperties;
import com.assessment.transaction.domain.TransactionOperationType;
import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.entity.TransactionOperationEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.LedgerEntryRepository;
import com.assessment.transaction.persistence.repository.TransactionOperationRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "transaction-processing.worker.enabled=false")
class TransactionClaimRecoveryTests {
	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private TransactionOperationRepository operationRepository;

	@Autowired
	private TransactionAttemptService attemptService;

	@Autowired
	private TransactionAttemptProcessor attemptProcessor;

	@Autowired
	private TransactionProcessingService processingService;

	@Autowired
	private TransactionFailureRecorder failureRecorder;

	@Autowired
	private TransactionProcessingProperties properties;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void resetClaimProperties() {
		properties.setMaxAttempts(3);
		properties.setRetryBackoff(Duration.ZERO);
		properties.setProcessingClaimTimeout(Duration.ofSeconds(30));
		properties.getSimulatedTransientFailureTransactionIds().clear();
	}

	@Test
	void freshClaimIsNotReclaimedAndCanFinishNormally() {
		properties.setProcessingClaimTimeout(Duration.ofMinutes(5));
		AccountEntity account = createAccount();
		TransactionEntity transaction = createCredit(account, "25.00");
		TransactionAttemptService.ProcessingClaim claim = attemptService
				.beginNextAttempt(account.getAccountId(), Instant.now()).orElseThrow();

		processingService.processNextForAccount(account.getAccountId());

		TransactionEntity stillClaimed = findTransaction(transaction);
		assertEquals(TransactionStatus.PROCESSING, stillClaimed.getStatus());
		assertEquals(claim.claimId(), stillClaimed.getProcessingClaimId());
		assertEquals(1, stillClaimed.getAttemptCount());
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));

		attemptProcessor.applyAttempt(account.getAccountId(), transaction.getTransactionId(), claim.claimId());

		assertEquals(TransactionStatus.PROCESSED, findTransaction(transaction).getStatus());
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void expiredClaimIsAuditedRetriedAndOldClaimCannotApplyAgain() {
		properties.setProcessingClaimTimeout(Duration.ofSeconds(1));
		AccountEntity account = createAccount();
		TransactionEntity transaction = createCredit(account, "42.00");
		Instant past = Instant.now().minus(Duration.ofMinutes(2));
		transaction.markProcessing("prior-claim", past);
		transaction.markRetryPending(past.plusSeconds(1), "Previous transient failure");
		transactionRepository.saveAndFlush(transaction);

		TransactionAttemptService.ProcessingClaim expiredClaim = attemptService
				.beginNextAttempt(account.getAccountId(), past.plusSeconds(2)).orElseThrow();
		assertEquals(2, findTransaction(transaction).getAttemptCount());

		processingService.processNextForAccount(account.getAccountId());

		TransactionEntity recovered = findTransaction(transaction);
		assertEquals(TransactionStatus.RETRY_PENDING, recovered.getStatus());
		assertEquals(2, recovered.getAttemptCount());
		assertEquals("Processing claim expired before the attempt completed", recovered.getFailureReason());
		assertEquals(1, expectedSequence(account));
		assertMoney("0.00", balance(account));
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));

		TransactionOperationEntity audit = operationRepository
				.findByTransaction_TransactionIdOrderByCreatedAtAsc(transaction.getTransactionId()).getFirst();
		assertEquals(TransactionOperationType.PROCESSING_CLAIM_RECOVERY, audit.getOperationType());
		assertTrue(audit.getReason().contains("Previous transient failure"));
		assertEquals("system", audit.getOperatorIdentity());
		assertNotNull(audit.getCreatedAt());

		processingService.processNextForAccount(account.getAccountId());
		assertEquals(TransactionStatus.PROCESSED, findTransaction(transaction).getStatus());
		assertEquals(3, findTransaction(transaction).getAttemptCount());
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
		assertMoney("42.00", balance(account));

		assertThrows(LostProcessingClaimException.class, () -> attemptProcessor.applyAttempt(
				account.getAccountId(), transaction.getTransactionId(), expiredClaim.claimId()));
		failureRecorder.recordTransientFailure(transaction.getTransactionId(), expiredClaim.claimId(),
				"Late failure from an expired claim", Instant.now());
		assertEquals(TransactionStatus.PROCESSED, findTransaction(transaction).getStatus());
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
		assertMoney("42.00", balance(account));
	}

	@Test
	void expiredClaimAtMaximumAttemptsBecomesFailedWithoutSequenceAdvance() {
		properties.setProcessingClaimTimeout(Duration.ZERO);
		properties.setMaxAttempts(1);
		AccountEntity account = createAccount();
		TransactionEntity transaction = createCredit(account, "7.00");
		attemptService.beginNextAttempt(account.getAccountId(), Instant.now().minusSeconds(5)).orElseThrow();

		processingService.processNextForAccount(account.getAccountId());

		TransactionEntity failed = findTransaction(transaction);
		assertEquals(TransactionStatus.FAILED, failed.getStatus());
		assertEquals(1, failed.getAttemptCount());
		assertEquals(1, expectedSequence(account));
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void expiredReaperWaitsForAccountLockHeldByActiveWorker() throws Exception {
		properties.setProcessingClaimTimeout(Duration.ZERO);
		AccountEntity account = createAccount();
		TransactionEntity transaction = createCredit(account, "11.00");
		attemptService.beginNextAttempt(account.getAccountId(), Instant.now().minusSeconds(5)).orElseThrow();

		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		CountDownLatch recoveryStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		Future<?> activeWorker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			accountRepository.findByAccountIdForUpdate(account.getAccountId()).orElseThrow();
			lockAcquired.countDown();
			try {
				if (!releaseLock.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting to release account lock");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Account lock holder interrupted", exception);
			}
		}));
		Future<?> expiredRecovery = null;

		try {
			assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));
			expiredRecovery = executor.submit(() -> {
				recoveryStarted.countDown();
				processingService.processNextForAccount(account.getAccountId());
			});
			assertTrue(recoveryStarted.await(5, TimeUnit.SECONDS));
			Future<?> pendingRecovery = expiredRecovery;
			assertThrows(TimeoutException.class,
					() -> pendingRecovery.get(150, TimeUnit.MILLISECONDS));
			assertEquals(TransactionStatus.PROCESSING, findTransaction(transaction).getStatus());
		} finally {
			releaseLock.countDown();
			executor.shutdown();
		}

		activeWorker.get(5, TimeUnit.SECONDS);
		if (expiredRecovery != null) {
			expiredRecovery.get(5, TimeUnit.SECONDS);
		}
		assertEquals(TransactionStatus.RETRY_PENDING, findTransaction(transaction).getStatus());
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	private AccountEntity createAccount() {
		return accountRepository.saveAndFlush(new AccountEntity("account-" + UUID.randomUUID(), "USD"));
	}

	private TransactionEntity createCredit(AccountEntity account, String amount) {
		return transactionRepository.saveAndFlush(new TransactionEntity(
				"transaction-" + UUID.randomUUID(), account, 1, TransactionType.CREDIT,
				new BigDecimal(amount), account.getCurrency(), null));
	}

	private TransactionEntity findTransaction(TransactionEntity transaction) {
		return transactionRepository.findById(transaction.getTransactionId()).orElseThrow();
	}

	private long expectedSequence(AccountEntity account) {
		return accountRepository.findById(account.getAccountId()).orElseThrow().getExpectedSequenceNumber();
	}

	private BigDecimal balance(AccountEntity account) {
		return accountRepository.findById(account.getAccountId()).orElseThrow().getBalance();
	}

	private void assertMoney(String expected, BigDecimal actual) {
		assertEquals(0, new BigDecimal(expected).compareTo(actual));
	}
}
