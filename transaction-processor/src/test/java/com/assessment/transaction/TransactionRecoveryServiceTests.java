package com.assessment.transaction;

import com.assessment.transaction.application.TransactionOperationNotAllowedException;
import com.assessment.transaction.application.TransactionProcessingService;
import com.assessment.transaction.application.TransactionRecoveryService;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
		"transaction-processing.worker.enabled=false",
		"transaction-processing.retry-backoff=0ms"
})
class TransactionRecoveryServiceTests {
	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private TransactionOperationRepository operationRepository;

	@Autowired
	private TransactionProcessingService processingService;

	@Autowired
	private TransactionRecoveryService recoveryService;

	@Autowired
	private TransactionProcessingProperties processingProperties;

	@BeforeEach
	void resetRetryConfiguration() {
		processingProperties.setMaxAttempts(3);
		processingProperties.setRetryBackoff(Duration.ZERO);
		processingProperties.getSimulatedTransientFailureTransactionIds().clear();
	}

	@Test
	void transientFailureThenRetrySucceedsWithoutDuplicateLedgerEntry() {
		AccountEntity account = createAccount();
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "31.00");
		processingProperties.getSimulatedTransientFailureTransactionIds().add(credit.getTransactionId());

		processingService.processNextForAccount(account.getAccountId());
		TransactionEntity retryPending = transaction(credit);
		assertEquals(TransactionStatus.RETRY_PENDING, retryPending.getStatus());
		assertEquals(1, retryPending.getAttemptCount());
		assertEquals("Simulated transient processing failure", retryPending.getFailureReason());
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
		assertMoney("0.00", balance(account));

		processingService.processNextForAccount(account.getAccountId());

		assertEquals(TransactionStatus.PROCESSED, transaction(credit).getStatus());
		assertEquals(2, transaction(credit).getAttemptCount());
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
		assertMoney("31.00", balance(account));
	}

	@Test
	void retryIsNotAttemptedBeforeBackoffExpires() {
		AccountEntity account = createAccount();
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "9.00");
		processingProperties.setRetryBackoff(Duration.ofMinutes(10));
		processingProperties.getSimulatedTransientFailureTransactionIds().add(credit.getTransactionId());

		processingService.processNextForAccount(account.getAccountId());
		Instant scheduledAt = transaction(credit).getNextAttemptAt();
		assertNotNull(scheduledAt);
		assertTrue(scheduledAt.isAfter(Instant.now()));

		processingService.processNextForAccount(account.getAccountId());

		TransactionEntity unchanged = transaction(credit);
		assertEquals(TransactionStatus.RETRY_PENDING, unchanged.getStatus());
		assertEquals(1, unchanged.getAttemptCount());
		assertEquals(scheduledAt, unchanged.getNextAttemptAt());
	}

	@Test
	void maximumAttemptsMarkTransactionFailedWithoutAdvancingSequence() {
		AccountEntity account = createAccount();
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "12.00");
		processingProperties.setMaxAttempts(1);
		processingProperties.getSimulatedTransientFailureTransactionIds().add(credit.getTransactionId());

		processingService.processNextForAccount(account.getAccountId());

		assertEquals(TransactionStatus.FAILED, transaction(credit).getStatus());
		assertEquals(1, transaction(credit).getAttemptCount());
		assertEquals(1, expectedSequence(account));
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void manualRetryIsAuditedAndReusesSameTransaction() {
		AccountEntity account = createAccount();
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "18.00");
		processingProperties.setMaxAttempts(1);
		processingProperties.getSimulatedTransientFailureTransactionIds().add(credit.getTransactionId());
		processingService.processNextForAccount(account.getAccountId());
		assertEquals(TransactionStatus.FAILED, transaction(credit).getStatus());

		processingProperties.setMaxAttempts(3);
		recoveryService.retryFailedTransaction(credit.getTransactionId(), "Upstream recovered", "operator-7");

		TransactionOperationEntity audit = onlyOperation(credit);
		assertEquals(TransactionOperationType.MANUAL_RETRY, audit.getOperationType());
		assertEquals("Upstream recovered", audit.getReason());
		assertEquals("operator-7", audit.getOperatorIdentity());
		assertNotNull(audit.getCreatedAt());
		assertEquals(TransactionStatus.RETRY_PENDING, transaction(credit).getStatus());
		assertEquals(1, transactionRepository.countByAccount_AccountId(account.getAccountId()));

		processingService.processNextForAccount(account.getAccountId());

		assertEquals(TransactionStatus.PROCESSED, transaction(credit).getStatus());
		assertEquals(2, transaction(credit).getAttemptCount());
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void manualSkipIsAuditedAndAdvancesSequenceWithoutBalanceOrLedgerEffects() {
		AccountEntity account = createAccount();
		TransactionEntity debit = createTransaction(account, 1, TransactionType.DEBIT, "1.00");
		processingService.processNextForAccount(account.getAccountId());
		assertEquals(TransactionStatus.FAILED, transaction(debit).getStatus());

		recoveryService.skipFailedTransaction(debit.getTransactionId(), "Invalid source record", "operator-9");

		TransactionOperationEntity audit = onlyOperation(debit);
		assertEquals(TransactionOperationType.SKIP, audit.getOperationType());
		assertEquals("Invalid source record", audit.getReason());
		assertEquals("operator-9", audit.getOperatorIdentity());
		assertNotNull(audit.getCreatedAt());
		assertEquals(TransactionStatus.SKIPPED, transaction(debit).getStatus());
		assertEquals(2, expectedSequence(account));
		assertMoney("0.00", balance(account));
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void retryAndSkipRejectNonblockingOrNonfailedTransactions() {
		AccountEntity account = createAccount();
		TransactionEntity received = createTransaction(account, 1, TransactionType.CREDIT, "1.00");
		TransactionEntity failedFuture = createTransaction(account, 2, TransactionType.CREDIT, "2.00");
		failedFuture.markProcessing();
		failedFuture.markFailed("test failure");
		transactionRepository.saveAndFlush(failedFuture);

		assertThrows(TransactionOperationNotAllowedException.class,
				() -> recoveryService.retryFailedTransaction(received.getTransactionId(), "reason", "operator"));
		assertThrows(TransactionOperationNotAllowedException.class,
				() -> recoveryService.skipFailedTransaction(received.getTransactionId(), "reason", "operator"));
		assertThrows(TransactionOperationNotAllowedException.class,
				() -> recoveryService.skipFailedTransaction(failedFuture.getTransactionId(), "reason", "operator"));
		assertThrows(IllegalArgumentException.class,
				() -> recoveryService.skipFailedTransaction(received.getTransactionId(), " ", "operator"));
	}

	@Test
	void concurrentManualRetriesAllowOnlyOneOperation() throws Exception {
		AccountEntity account = createAccount();
		TransactionEntity debit = createTransaction(account, 1, TransactionType.DEBIT, "1.00");
		processingService.processNextForAccount(account.getAccountId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();

		try {
			for (int index = 0; index < 2; index++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to start concurrent recovery");
					}
					try {
						recoveryService.retryFailedTransaction(debit.getTransactionId(), "retry", "operator");
						return true;
					} catch (TransactionOperationNotAllowedException exception) {
						return false;
					}
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			int successfulOperations = 0;
			for (Future<Boolean> result : results) {
				if (result.get(10, TimeUnit.SECONDS)) {
					successfulOperations++;
				}
			}
			assertEquals(1, successfulOperations);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}

		assertEquals(TransactionStatus.RETRY_PENDING, transaction(debit).getStatus());
		assertEquals(1, operationRepository.countByTransaction_TransactionIdAndOperationType(
				debit.getTransactionId(), TransactionOperationType.MANUAL_RETRY));
	}

	private AccountEntity createAccount() {
		return accountRepository.saveAndFlush(new AccountEntity("account-" + UUID.randomUUID(), "USD"));
	}

	private TransactionEntity createTransaction(AccountEntity account, long sequence,
			TransactionType type, String amount) {
		return transactionRepository.saveAndFlush(new TransactionEntity(
				"transaction-" + UUID.randomUUID(), account, sequence, type,
				new BigDecimal(amount), account.getCurrency(), null));
	}

	private TransactionEntity transaction(TransactionEntity transaction) {
		return transactionRepository.findById(transaction.getTransactionId()).orElseThrow();
	}

	private TransactionOperationEntity onlyOperation(TransactionEntity transaction) {
		return operationRepository.findByTransaction_TransactionIdOrderByCreatedAtAsc(
				transaction.getTransactionId()).getFirst();
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
