package com.assessment.transaction.application;

import com.assessment.transaction.api.dto.ProcessingSummaryResponse;
import com.assessment.transaction.domain.SubmissionOutcome;
import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.persistence.repository.SubmissionRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

@Service
public class ProcessingSummaryService {
	private final TransactionRepository transactionRepository;
	private final SubmissionRepository submissionRepository;

	public ProcessingSummaryService(TransactionRepository transactionRepository,
			SubmissionRepository submissionRepository) {
		this.transactionRepository = transactionRepository;
		this.submissionRepository = submissionRepository;
	}

	@Transactional(readOnly = true)
	public ProcessingSummaryResponse getSummary() {
		Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
		for (TransactionStatus status : TransactionStatus.values()) {
			counts.put(status, 0L);
		}
		for (TransactionRepository.StatusCount statusCount : transactionRepository.countGroupedByStatus()) {
			counts.put(statusCount.getStatus(), statusCount.getCount());
		}

		return new ProcessingSummaryResponse(
				counts,
				transactionRepository.count(),
				submissionRepository.countByOutcome(SubmissionOutcome.DUPLICATE),
				counts.get(TransactionStatus.WAITING_FOR_SEQUENCE),
				counts.get(TransactionStatus.WAITING_FOR_ORIGINAL),
				counts.get(TransactionStatus.RETRY_PENDING),
				counts.get(TransactionStatus.FAILED),
				counts.get(TransactionStatus.SKIPPED));
	}
}