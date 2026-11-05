package com.assessment.transaction.worker;

import com.assessment.transaction.application.TransactionProcessingService;
import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "transaction-processing.worker", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class TransactionPollingWorker {
	private static final Logger logger = LoggerFactory.getLogger(TransactionPollingWorker.class);
	private static final List<TransactionStatus> POLLABLE_STATUSES = List.of(
			TransactionStatus.RECEIVED,
			TransactionStatus.WAITING_FOR_SEQUENCE,
			TransactionStatus.WAITING_FOR_ORIGINAL,
			TransactionStatus.RETRY_PENDING,
			TransactionStatus.PROCESSING);

	private final TransactionRepository transactionRepository;
	private final TransactionProcessingService processingService;

	public TransactionPollingWorker(TransactionRepository transactionRepository,
			TransactionProcessingService processingService) {
		this.transactionRepository = transactionRepository;
		this.processingService = processingService;
	}

	@Scheduled(fixedDelayString = "${transaction-processing.worker.poll-interval-ms:1000}")
	public void poll() {
		for (String accountId : transactionRepository.findDistinctAccountIdsByStatusIn(POLLABLE_STATUSES)) {
			try {
				processingService.processNextForAccount(accountId);
			} catch (RuntimeException exception) {
				logger.error("Unable to process pending transaction accountId={} errorType={}",
						accountId, exception.getClass().getSimpleName());
			}
		}
	}
}