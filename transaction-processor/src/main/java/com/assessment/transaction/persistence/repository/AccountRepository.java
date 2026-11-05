package com.assessment.transaction.persistence.repository;

import com.assessment.transaction.persistence.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from AccountEntity account where account.accountId = :accountId")
	Optional<AccountEntity> findByAccountIdForUpdate(@Param("accountId") String accountId);
}