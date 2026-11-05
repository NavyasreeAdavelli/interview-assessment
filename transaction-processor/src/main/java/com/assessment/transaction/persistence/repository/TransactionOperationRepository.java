package com.assessment.transaction.persistence.repository;

import com.assessment.transaction.persistence.entity.TransactionOperationEntity;
import com.assessment.transaction.domain.TransactionOperationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionOperationRepository extends JpaRepository<TransactionOperationEntity, Long> {
	List<TransactionOperationEntity> findByTransaction_TransactionIdOrderByCreatedAtAsc(String transactionId);

	long countByTransaction_TransactionIdAndOperationType(String transactionId,
			TransactionOperationType operationType);
}