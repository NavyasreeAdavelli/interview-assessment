package com.assessment.transaction;

import com.assessment.transaction.application.TransactionProcessingService;
import com.assessment.transaction.application.TransactionRecoveryService;
import com.assessment.transaction.config.TransactionProcessingProperties;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.TransactionOperationRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"transaction-processing.worker.enabled=false",
		"transaction-processing.retry-backoff=0ms"
})
@AutoConfigureMockMvc
class TransactionApiIntegrationTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private TransactionOperationRepository operationRepository;

	@Autowired
	private TransactionProcessingService processingService;

	@Autowired
	private TransactionRecoveryService recoveryService;

	@Autowired
	private TransactionProcessingProperties processingProperties;

	@BeforeEach
	void resetProcessingProperties() {
		processingProperties.setMaxAttempts(3);
		processingProperties.setRetryBackoff(Duration.ZERO);
		processingProperties.getSimulatedTransientFailureTransactionIds().clear();
	}

	@Test
	void ingestionAcceptsReplaysAndReportsBusinessDuplicates() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String transactionId = "txn-" + suffix;
		String accountId = "account-" + suffix;
		String body = transactionJson(transactionId, accountId, 1, "CREDIT", "50.00");

		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "key-" + suffix)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.submissionOutcome").value("ACCEPTED"))
				.andExpect(jsonPath("$.transaction.transactionId").value(transactionId))
				.andExpect(jsonPath("$.transaction.accountId").value(accountId))
				.andExpect(jsonPath("$.transaction.account").doesNotExist());

		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "key-" + suffix)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.submissionOutcome").value("ACCEPTED"));

		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "duplicate-key-" + suffix)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.submissionOutcome").value("DUPLICATE"));
	}

	@Test
	void ingestionMapsMissingKeyValidationAndConflictsToConsistentErrors() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String transactionId = "txn-" + suffix;
		String accountId = "account-" + suffix;
		String key = "key-" + suffix;
		String body = transactionJson(transactionId, accountId, 1, "CREDIT", "10.00");

		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", key)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isAccepted());

		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", key)
					.contentType(MediaType.APPLICATION_JSON)
					.content(transactionJson(transactionId, accountId, 1, "CREDIT", "11.00")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.errorCode").value("TRANSACTION_CONFLICT"))
				.andExpect(jsonPath("$.timestamp").exists());

		mockMvc.perform(post("/api/v1/transactions")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));

		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "invalid-" + suffix)
					.contentType(MediaType.APPLICATION_JSON)
					.content(transactionJson("invalid-" + suffix, "invalid-account-" + suffix,
							1, "DEBIT", "0.00")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "malformed-" + suffix)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
	}

	@Test
	void statusEndpointReturnsFlatDtoAndNotFoundError() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String transactionId = "txn-" + suffix;
		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "key-" + suffix)
					.contentType(MediaType.APPLICATION_JSON)
					.content(transactionJson(transactionId, "account-" + suffix, 1, "CREDIT", "3.25")))
				.andExpect(status().isAccepted());

		mockMvc.perform(get("/api/v1/transactions/{transactionId}", transactionId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transactionId").value(transactionId))
				.andExpect(jsonPath("$.transactionType").value("CREDIT"))
				.andExpect(jsonPath("$.amount").value(3.25))
				.andExpect(jsonPath("$.status").value("RECEIVED"))
				.andExpect(jsonPath("$.attemptCount").value(0))
				.andExpect(jsonPath("$.createdAt").exists())
				.andExpect(jsonPath("$.account").doesNotExist());

		mockMvc.perform(get("/api/v1/transactions/{transactionId}", "missing-" + suffix))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
	}

	@Test
	void retryAndSkipRoutesReturnUpdatedTransactionAndPersistAudit() throws Exception {
		TransactionEntity retryTransaction = createFailedDebit("retry-" + UUID.randomUUID());
		mockMvc.perform(get("/api/v1/transactions/{transactionId}", retryTransaction.getTransactionId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failureReason").value("Insufficient account balance"));

		mockMvc.perform(post("/api/v1/transactions/{transactionId}/retry", retryTransaction.getTransactionId())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"reason":"Funds replenished","operatorIdentity":"operator-1"}
							"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.transactionId").value(retryTransaction.getTransactionId()))
				.andExpect(jsonPath("$.status").value("RETRY_PENDING"));
		if (operationRepository.findByTransaction_TransactionIdOrderByCreatedAtAsc(
				retryTransaction.getTransactionId()).isEmpty()) {
			throw new AssertionError("Manual retry audit record was not persisted");
		}

		TransactionEntity skipTransaction = createFailedDebit("skip-" + UUID.randomUUID());
		mockMvc.perform(post("/api/v1/transactions/{transactionId}/skip", skipTransaction.getTransactionId())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"reason":"Invalid source record","operatorIdentity":"operator-2"}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transactionId").value(skipTransaction.getTransactionId()))
				.andExpect(jsonPath("$.status").value("SKIPPED"));
	}

	@Test
	void recoveryRoutesRejectInvalidStatesAndUnknownTransactions() throws Exception {
		String suffix = UUID.randomUUID().toString();
		AccountEntity account = accountRepository.saveAndFlush(new AccountEntity("account-" + suffix, "USD"));
		TransactionEntity received = transactionRepository.saveAndFlush(new TransactionEntity(
				"transaction-" + suffix, account, 1, TransactionType.CREDIT,
				new BigDecimal("1.00"), "USD", null));
		String recoveryBody = """
				{"reason":"operator review","operatorIdentity":"operator-3"}
				""";

		mockMvc.perform(post("/api/v1/transactions/{transactionId}/retry", received.getTransactionId())
					.contentType(MediaType.APPLICATION_JSON).content(recoveryBody))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("OPERATION_NOT_ALLOWED"));

		mockMvc.perform(post("/api/v1/transactions/{transactionId}/skip", received.getTransactionId())
					.contentType(MediaType.APPLICATION_JSON).content(recoveryBody))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("OPERATION_NOT_ALLOWED"));

		mockMvc.perform(post("/api/v1/transactions/{transactionId}/retry", "missing-" + suffix)
					.contentType(MediaType.APPLICATION_JSON).content(recoveryBody))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));

		mockMvc.perform(post("/api/v1/transactions/{transactionId}/skip", received.getTransactionId())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"reason":" ","operatorIdentity":"operator-3"}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
	}

	@Test
	void processingSummaryUsesDatabaseCountsAndIncludesOperationalBreakdowns() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String waitingId = "waiting-" + suffix;
		String waitingAccount = "waiting-account-" + suffix;
		String waitingBody = transactionJson(waitingId, waitingAccount, 2, "CREDIT", "4.00");
		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "waiting-key-" + suffix)
					.contentType(MediaType.APPLICATION_JSON).content(waitingBody))
				.andExpect(status().isAccepted());
		mockMvc.perform(post("/api/v1/transactions")
					.header("Idempotency-Key", "waiting-duplicate-key-" + suffix)
					.contentType(MediaType.APPLICATION_JSON).content(waitingBody))
				.andExpect(status().isOk());
		processingService.processNextForAccount(waitingAccount);

		TransactionEntity retryTransaction = createTransaction("retry-summary-" + UUID.randomUUID(),
				TransactionType.CREDIT, "5.00");
		processingProperties.getSimulatedTransientFailureTransactionIds().add(retryTransaction.getTransactionId());
		processingService.processNextForAccount(retryTransaction.getAccount().getAccountId());

		createFailedDebit("failed-summary-" + UUID.randomUUID());
		TransactionEntity skipped = createFailedDebit("skipped-summary-" + UUID.randomUUID());
		recoveryService.skipFailedTransaction(skipped.getTransactionId(), "Skip for summary test", "operator");

		mockMvc.perform(get("/api/v1/processing/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAcceptedTransactions").value(4))
				.andExpect(jsonPath("$.duplicateSubmissionCount").value(1))
				.andExpect(jsonPath("$.waitingForSequenceCount").value(1))
				.andExpect(jsonPath("$.waitingForReferenceCount").value(0))
				.andExpect(jsonPath("$.retryPendingCount").value(1))
				.andExpect(jsonPath("$.failedCount").value(1))
				.andExpect(jsonPath("$.skippedCount").value(1))
				.andExpect(jsonPath("$.transactionCountsByStatus.WAITING_FOR_SEQUENCE").value(1))
				.andExpect(jsonPath("$.transactionCountsByStatus.RETRY_PENDING").value(1))
				.andExpect(jsonPath("$.transactionCountsByStatus.FAILED").value(1))
				.andExpect(jsonPath("$.transactionCountsByStatus.SKIPPED").value(1));
	}

	private TransactionEntity createFailedDebit(String suffix) {
		return createAndProcess(suffix, TransactionType.DEBIT, "1.00");
	}

	private TransactionEntity createTransaction(String suffix, TransactionType type, String amount) {
		return createAndProcess(suffix, type, amount, false);
	}

	private TransactionEntity createAndProcess(String suffix, TransactionType type, String amount) {
		return createAndProcess(suffix, type, amount, true);
	}

	private TransactionEntity createAndProcess(String suffix, TransactionType type, String amount, boolean process) {
		AccountEntity account = accountRepository.saveAndFlush(new AccountEntity("account-" + suffix, "USD"));
		TransactionEntity transaction = transactionRepository.saveAndFlush(new TransactionEntity(
				"transaction-" + suffix, account, 1, type, new BigDecimal(amount), "USD", null));
		if (process) {
			processingService.processNextForAccount(account.getAccountId());
		}
		return transaction;
	}

	private String transactionJson(String transactionId, String accountId, long sequence,
			String type, String amount) {
		return """
				{"transactionId":"%s","accountId":"%s","sequenceNumber":%d,
				 "transactionType":"%s","amount":%s,"currency":"USD"}
				""".formatted(transactionId, accountId, sequence, type, amount);
	}
}
