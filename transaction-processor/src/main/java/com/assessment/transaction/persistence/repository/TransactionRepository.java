package com.assessment.transaction.persistence.repository;

import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {
	interface StatusCount {
		TransactionStatus getStatus();

		long getCount();
	}

	@Query("select transaction.status as status, count(transaction) as count "
			+ "from TransactionEntity transaction group by transaction.status")
	List<StatusCount> countGroupedByStatus();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select transaction from TransactionEntity transaction join fetch transaction.account "
			+ "where transaction.transactionId = :transactionId")
	Optional<TransactionEntity> findByIdForUpdate(@Param("transactionId") String transactionId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select transaction from TransactionEntity transaction join fetch transaction.account "
			+ "where transaction.account.accountId = :accountId and transaction.sequenceNumber = :sequenceNumber")
	Optional<TransactionEntity> findExpectedSequenceForUpdate(
			@Param("accountId") String accountId, @Param("sequenceNumber") long sequenceNumber);

	Optional<TransactionEntity> findByAccount_AccountIdAndSequenceNumber(String accountId, long sequenceNumber);

	Optional<TransactionEntity> findByReversalOfTransactionId(String reversalOfTransactionId);

	@Query("select transaction.account.accountId from TransactionEntity transaction "
			+ "where transaction.transactionId = :transactionId")
	Optional<String> findAccountIdByTransactionId(@Param("transactionId") String transactionId);

	long countByAccount_AccountId(String accountId);

	List<TransactionEntity> findByAccount_AccountIdAndSequenceNumberGreaterThanAndStatusIn(
			String accountId, long sequenceNumber, Collection<TransactionStatus> statuses);

	@Query("select distinct transaction.account.accountId from TransactionEntity transaction "
			+ "where transaction.status in :statuses")
	List<String> findDistinctAccountIdsByStatusIn(@Param("statuses") Collection<TransactionStatus> statuses);
}