package com.assessment.transaction.persistence.repository;

import com.assessment.transaction.domain.SubmissionOutcome;
import com.assessment.transaction.persistence.entity.SubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<SubmissionEntity, String> {
	long countByTransaction_TransactionId(String transactionId);

	long countByOutcome(SubmissionOutcome outcome);
}