package com.assessment.transaction.persistence.repository;

import com.assessment.transaction.persistence.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {
	long countByAccount_AccountId(String accountId);

	Optional<LedgerEntryEntity> findByTransaction_TransactionId(String transactionId);
}