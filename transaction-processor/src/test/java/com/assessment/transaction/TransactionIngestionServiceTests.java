package com.assessment.transaction;

import com.assessment.transaction.api.dto.TransactionIngestionResponse;
import com.assessment.transaction.api.dto.TransactionRequest;
import com.assessment.transaction.application.TransactionConflictException;
import com.assessment.transaction.application.TransactionIngestionService;
import com.assessment.transaction.domain.SubmissionOutcome;
import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.persistence.repository.SubmissionRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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

@SpringBootTest(properties = "transaction-processing.worker.enabled=false")
class TransactionIngestionServiceTests {
	@Autowired
	private TransactionIngestionService ingestionService;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private SubmissionRepository submissionRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void sameKeyAndCanonicalPayloadReturnOriginalOutcome() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String transactionId = "txn-" + suffix;
		String accountId = "account-" + suffix;
		TransactionRequest firstRequest = objectMapper.readValue("""
				{"transactionId":"%s","accountId":"%s","sequenceNumber":1,
				 "transactionType":"CREDIT","amount":125.0000,"currency":"USD"}
				""".formatted(transactionId, accountId), TransactionRequest.class);
		TransactionRequest replayRequest = objectMapper.readValue("""
				{"currency":"USD","amount":125.00,"transactionType":"CREDIT",
				 "sequenceNumber":1,"accountId":"%s","transactionId":"%s"}
				""".formatted(accountId, transactionId), TransactionRequest.class);

		TransactionIngestionResponse accepted = ingestionService.ingest(firstRequest, "key-" + suffix);
		TransactionIngestionResponse replay = ingestionService.ingest(replayRequest, "key-" + suffix);

		assertEquals(SubmissionOutcome.ACCEPTED, accepted.submissionOutcome());
		assertEquals(accepted.submissionOutcome(), replay.submissionOutcome());
		assertEquals(accepted.transaction().transactionId(), replay.transaction().transactionId());
		assertEquals(accepted.transaction().status(), replay.transaction().status());
		assertEquals(0, accepted.transaction().amount().compareTo(replay.transaction().amount()));
		assertNotNull(accepted.transaction().createdAt());
		assertNotNull(replay.transaction().createdAt());
		assertEquals(1, transactionRepository.countByAccount_AccountId(accountId));
		assertEquals(1, submissionRepository.countByTransaction_TransactionId(transactionId));
	}

	@Test
	void sameBusinessTransactionWithDifferentKeyIsRecordedAsDuplicate() {
		String suffix = UUID.randomUUID().toString();
		TransactionRequest request = request("txn-" + suffix, "account-" + suffix, 1, "10.00");

		TransactionIngestionResponse accepted = ingestionService.ingest(request, "first-key-" + suffix);
		TransactionIngestionResponse duplicate = ingestionService.ingest(request, "second-key-" + suffix);

		assertEquals(SubmissionOutcome.ACCEPTED, accepted.submissionOutcome());
		assertEquals(SubmissionOutcome.DUPLICATE, duplicate.submissionOutcome());
		assertEquals(TransactionStatus.RECEIVED, duplicate.transaction().status());
		assertEquals(accepted.transaction().transactionId(), duplicate.transaction().transactionId());
		assertEquals(1, transactionRepository.countByAccount_AccountId(request.accountId()));
		assertEquals(2, submissionRepository.countByTransaction_TransactionId(request.transactionId()));
	}

	@Test
	void reusedIdempotencyKeyWithDifferentPayloadConflicts() {
		String suffix = UUID.randomUUID().toString();
		TransactionRequest original = request("txn-" + suffix, "account-" + suffix, 1, "10.00");
		TransactionRequest changed = request(original.transactionId(), original.accountId(), 1, "11.00");
		String idempotencyKey = "key-" + suffix;

		ingestionService.ingest(original, idempotencyKey);

		assertThrows(TransactionConflictException.class, () -> ingestionService.ingest(changed, idempotencyKey));
		assertEquals(1, transactionRepository.countByAccount_AccountId(original.accountId()));
	}

	@Test
	void reusedTransactionIdWithConflictingBusinessDetailsConflicts() {
		String suffix = UUID.randomUUID().toString();
		TransactionRequest original = request("txn-" + suffix, "account-" + suffix, 1, "10.00");
		TransactionRequest changed = request(original.transactionId(), original.accountId(), 1, "11.00");

		ingestionService.ingest(original, "first-key-" + suffix);

		assertThrows(TransactionConflictException.class,
				() -> ingestionService.ingest(changed, "second-key-" + suffix));
		assertEquals(1, transactionRepository.countByAccount_AccountId(original.accountId()));
	}

	@Test
	void concurrentRequestsWithSameKeyCreateOneTransactionAndSubmission() throws Exception {
		String suffix = UUID.randomUUID().toString();
		TransactionRequest request = request("txn-" + suffix, "account-" + suffix, 1, "25.00");
		String idempotencyKey = "key-" + suffix;
		int requestCount = 8;
		ExecutorService executor = Executors.newFixedThreadPool(requestCount);
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<TransactionIngestionResponse>> results = new ArrayList<>();

		try {
			for (int index = 0; index < requestCount; index++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to start concurrent submissions");
					}
					return ingestionService.ingest(request, idempotencyKey);
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			for (Future<TransactionIngestionResponse> result : results) {
				assertEquals(SubmissionOutcome.ACCEPTED, result.get(10, TimeUnit.SECONDS).submissionOutcome());
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
		}

		assertEquals(1, transactionRepository.countByAccount_AccountId(request.accountId()));
		assertEquals(1, submissionRepository.countByTransaction_TransactionId(request.transactionId()));
	}

	private TransactionRequest request(String transactionId, String accountId, long sequence, String amount) {
		return new TransactionRequest(transactionId, accountId, sequence, TransactionType.CREDIT,
				new java.math.BigDecimal(amount), "USD", null);
	}
}