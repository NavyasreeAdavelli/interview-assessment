package com.assessment.transaction;

import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.application.TransactionProcessingService;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.LedgerEntryEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.LedgerEntryRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import com.assessment.transaction.worker.TransactionPollingWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "transaction-processing.worker.enabled=false")
class TransactionProcessingServiceTests {
	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private TransactionProcessingService processingService;

	@Test
	void processesCreditAndAdvancesSequence() {
		AccountEntity account = createAccount("USD");
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "100.00", null);

		processingService.processNextForAccount(account.getAccountId());

		assertEquals(0, new BigDecimal("100.00").compareTo(
				accountRepository.findById(account.getAccountId()).orElseThrow().getBalance()));
		assertEquals(TransactionStatus.PROCESSED, statusOf(credit));
		assertEquals(2, accountRepository.findById(account.getAccountId()).orElseThrow().getExpectedSequenceNumber());
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void pollingWorkerDelegatesDiscoveredPendingAccounts() {
		TransactionRepository pollingRepository = mock(TransactionRepository.class);
		TransactionProcessingService service = mock(TransactionProcessingService.class);
		when(pollingRepository.findDistinctAccountIdsByStatusIn(argThat(
				statuses -> statuses.contains(TransactionStatus.PROCESSING))))
				.thenReturn(List.of("pending-account"));

		new TransactionPollingWorker(pollingRepository, service).poll();

		verify(service).processNextForAccount("pending-account");
	}

	@Test
	void processesDebitWhenFundsAreAvailable() {
		AccountEntity account = createAccount("USD");
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "100.00", null);
		TransactionEntity debit = createTransaction(account, 2, TransactionType.DEBIT, "35.25", null);

		processingService.processNextForAccount(account.getAccountId());
		processingService.processNextForAccount(account.getAccountId());

		assertEquals(TransactionStatus.PROCESSED, statusOf(credit));
		assertEquals(TransactionStatus.PROCESSED, statusOf(debit));
		assertEquals(0, new BigDecimal("64.75").compareTo(
				accountRepository.findById(account.getAccountId()).orElseThrow().getBalance()));
		assertEquals(2, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void insufficientDebitFailsWithoutChangingBalanceOrSequence() {
		AccountEntity account = createAccount("USD");
		TransactionEntity debit = createTransaction(account, 1, TransactionType.DEBIT, "1.00", null);

		processingService.processNextForAccount(account.getAccountId());

		AccountEntity unchangedAccount = accountRepository.findById(account.getAccountId()).orElseThrow();
		assertEquals(TransactionStatus.FAILED, statusOf(debit));
		assertEquals(0, BigDecimal.ZERO.compareTo(unchangedAccount.getBalance()));
		assertEquals(1, unchangedAccount.getExpectedSequenceNumber());
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void reversalAppliesExactInverseOfProcessedCredit() {
		AccountEntity account = createAccount("USD");
		TransactionEntity original = createTransaction(account, 1, TransactionType.CREDIT, "82.10", null);
		TransactionEntity reversal = createTransaction(account, 2, TransactionType.REVERSAL, "82.10",
				original.getTransactionId());

		processingService.processNextForAccount(account.getAccountId());
		processingService.processNextForAccount(account.getAccountId());

		assertEquals(TransactionStatus.PROCESSED, statusOf(original));
		assertEquals(TransactionStatus.PROCESSED, statusOf(reversal));
		assertEquals(0, BigDecimal.ZERO.compareTo(
				accountRepository.findById(account.getAccountId()).orElseThrow().getBalance()));
		assertEquals(2, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
		assertEquals(0, new BigDecimal("-82.10").compareTo(
				ledgerEntryRepository.findByTransaction_TransactionId(reversal.getTransactionId())
						.orElseThrow().getAmountDelta()));
	}

	@Test
	void reversalArrivingBeforeOriginalWaitsForSequenceThenProcesses() {
		AccountEntity account = createAccount("USD");
		String originalId = "original-" + UUID.randomUUID();
		TransactionEntity reversal = createTransaction(account, 2, TransactionType.REVERSAL, "50.00", originalId);

		processingService.processNextForAccount(account.getAccountId());
		assertEquals(TransactionStatus.WAITING_FOR_SEQUENCE, statusOf(reversal));
		assertEquals(1, accountRepository.findById(account.getAccountId()).orElseThrow()
				.getExpectedSequenceNumber());

		TransactionEntity original = saveTransaction(new TransactionEntity(originalId, account, 1,
				TransactionType.CREDIT, new BigDecimal("50.00"), "USD", null));
		processingService.processNextForAccount(account.getAccountId());
		assertEquals(TransactionStatus.PROCESSED, statusOf(original));
		assertEquals(TransactionStatus.WAITING_FOR_SEQUENCE, statusOf(reversal));
		processingService.processNextForAccount(account.getAccountId());
		assertEquals(TransactionStatus.PROCESSED, statusOf(reversal));
		assertEquals(0, BigDecimal.ZERO.compareTo(
				accountRepository.findById(account.getAccountId()).orElseThrow().getBalance()));
		assertEquals(3, accountRepository.findById(account.getAccountId()).orElseThrow().getExpectedSequenceNumber());
	}

	@Test
	void higherSequenceAndMissingSequenceRemainVisibleWithoutProcessing() {
		AccountEntity account = createAccount("USD");
		TransactionEntity future = createTransaction(account, 3, TransactionType.CREDIT, "5.00", null);

		processingService.processNextForAccount(account.getAccountId());

		assertEquals(TransactionStatus.WAITING_FOR_SEQUENCE, statusOf(future));
		assertEquals(1, accountRepository.findById(account.getAccountId()).orElseThrow()
				.getExpectedSequenceNumber());
		assertEquals(0, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	@Test
	void reversalOfProcessedDebitRestoresTheDebitedAmount() {
		AccountEntity account = createAccount("USD");
		createTransaction(account, 1, TransactionType.CREDIT, "100.00", null);
		TransactionEntity originalDebit = createTransaction(account, 2, TransactionType.DEBIT, "40.00", null);
		TransactionEntity reversal = createTransaction(account, 3, TransactionType.REVERSAL, "40.00",
				originalDebit.getTransactionId());

		processingService.processNextForAccount(account.getAccountId());
		processingService.processNextForAccount(account.getAccountId());
		processingService.processNextForAccount(account.getAccountId());

		assertEquals(TransactionStatus.PROCESSED, statusOf(reversal));
		assertEquals(0, new BigDecimal("100.00").compareTo(
				accountRepository.findById(account.getAccountId()).orElseThrow().getBalance()));
		assertEquals(0, new BigDecimal("40.00").compareTo(
				ledgerEntryRepository.findByTransaction_TransactionId(reversal.getTransactionId())
						.orElseThrow().getAmountDelta()));
	}

	@Test
	void concurrentProcessingAttemptsApplyTransactionOnlyOnce() throws Exception {
		AccountEntity account = createAccount("USD");
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "17.00", null);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> results = new ArrayList<>();

		try {
			for (int index = 0; index < 2; index++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to start concurrent processing");
					}
					processingService.processNextForAccount(account.getAccountId());
					return null;
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			for (Future<?> result : results) {
				result.get(10, TimeUnit.SECONDS);
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
		}

		assertEquals(TransactionStatus.PROCESSED, statusOf(credit));
		assertEquals(0, new BigDecimal("17.00").compareTo(
				accountRepository.findById(account.getAccountId()).orElseThrow().getBalance()));
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
		assertEquals(2, accountRepository.findById(account.getAccountId()).orElseThrow()
				.getExpectedSequenceNumber());
	}

	@Test
	void ledgerConstraintFailureRollsBackBalanceStatusAndSequence() {
		AccountEntity account = createAccount("USD");
		TransactionEntity credit = createTransaction(account, 1, TransactionType.CREDIT, "22.00", null);
		ledgerEntryRepository.saveAndFlush(new LedgerEntryEntity(
				credit, account, new BigDecimal("22.00"), new BigDecimal("22.00")));

		assertThrows(DataIntegrityViolationException.class,
				() -> processingService.processNextForAccount(account.getAccountId()));

		AccountEntity unchangedAccount = accountRepository.findById(account.getAccountId()).orElseThrow();
		TransactionEntity failed = transactionRepository.findById(credit.getTransactionId()).orElseThrow();
		assertEquals(TransactionStatus.FAILED, failed.getStatus());
		assertEquals(1, failed.getAttemptCount());
		assertNotNull(failed.getFailureReason());
		assertEquals(0, BigDecimal.ZERO.compareTo(unchangedAccount.getBalance()));
		assertEquals(1, unchangedAccount.getExpectedSequenceNumber());
		assertEquals(1, ledgerEntryRepository.countByAccount_AccountId(account.getAccountId()));
	}

	private AccountEntity createAccount(String currency) {
		return accountRepository.saveAndFlush(new AccountEntity("account-" + UUID.randomUUID(), currency));
	}

	private TransactionEntity createTransaction(AccountEntity account, long sequence,
			TransactionType type, String amount, String reversalOf) {
		return saveTransaction(new TransactionEntity("transaction-" + UUID.randomUUID(), account,
				sequence, type, new BigDecimal(amount), account.getCurrency(), reversalOf));
	}

	private TransactionEntity saveTransaction(TransactionEntity transaction) {
		return transactionRepository.saveAndFlush(transaction);
	}

	private TransactionStatus statusOf(TransactionEntity transaction) {
		return transactionRepository.findById(transaction.getTransactionId()).orElseThrow().getStatus();
	}
}
