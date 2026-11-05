package com.assessment.transaction;

import com.assessment.transaction.api.dto.TransactionRequest;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "transaction-processing.worker.enabled=false")
class TransactionProcessorApplicationTests {
	@Autowired
	private Validator validator;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void validatesMoneyAndConditionalReversalReference() {
		assertTrue(validator.validate(new TransactionRequest(
				"credit", "account", 1, TransactionType.CREDIT,
				new BigDecimal("10.1234"), "USD", null)).isEmpty());

		assertFalse(validator.validate(new TransactionRequest(
				"too-precise", "account", 1, TransactionType.CREDIT,
				new BigDecimal("10.12345"), "USD", null)).isEmpty());
		assertFalse(validator.validate(new TransactionRequest(
				"zero", "account", 1, TransactionType.DEBIT,
				BigDecimal.ZERO, "USD", null)).isEmpty());
		assertFalse(validator.validate(new TransactionRequest(
				"missing-original", "account", 1, TransactionType.REVERSAL,
				new BigDecimal("10.00"), "USD", null)).isEmpty());
		assertFalse(validator.validate(new TransactionRequest(
				"unexpected-original", "account", 1, TransactionType.CREDIT,
				new BigDecimal("10.00"), "USD", "original")).isEmpty());
	}

	@Test
	@Transactional
	void h2AllowsNullReversalTargetsAndEnforcesLocksAndReversalUniqueness() {
		String suffix = UUID.randomUUID().toString();
		AccountEntity account = accountRepository.saveAndFlush(
				new AccountEntity("account-" + suffix, "USD"));
		TransactionEntity original = transactionRepository.saveAndFlush(new TransactionEntity(
				"original-" + suffix, account, 1, TransactionType.CREDIT,
				new BigDecimal("10.0000"), "USD", null));

		transactionRepository.saveAndFlush(new TransactionEntity(
				"second-" + suffix, account, 2, TransactionType.CREDIT,
				new BigDecimal("5.0000"), "USD", null));

		assertTrue(accountRepository.findByAccountIdForUpdate(account.getAccountId()).isPresent());
		assertTrue(transactionRepository.findByIdForUpdate(original.getTransactionId()).isPresent());

		transactionRepository.saveAndFlush(new TransactionEntity(
				"reversal-one-" + suffix, account, 3, TransactionType.REVERSAL,
				new BigDecimal("10.0000"), "USD", original.getTransactionId()));
		assertThrows(DataIntegrityViolationException.class, () -> transactionRepository.saveAndFlush(
				new TransactionEntity("reversal-two-" + suffix, account, 4, TransactionType.REVERSAL,
						new BigDecimal("10.0000"), "USD", original.getTransactionId())));
		assertEquals("USD", account.getCurrency());
	}

}
