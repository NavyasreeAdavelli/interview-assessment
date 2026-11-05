package com.assessment.transaction.application;

import com.assessment.transaction.config.TransactionProcessingProperties;
import com.assessment.transaction.domain.TransactionStatus;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.LedgerEntryEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.LedgerEntryRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class TransactionAttemptProcessor {
	private static final Logger logger = LoggerFactory.getLogger(TransactionAttemptProcessor.class);

	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final TransactionProcessingProperties properties;

	public TransactionAttemptProcessor(AccountRepository accountRepository,
			TransactionRepository transactionRepository, LedgerEntryRepository ledgerEntryRepository,
			TransactionProcessingProperties properties) {
		this.accountRepository = accountRepository;
		this.transactionRepository = transactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.properties = properties;
	}

	@Transactional
	public void applyAttempt(String accountId, String transactionId, String claimId) {
		AccountEntity account = accountRepository.findByAccountIdForUpdate(accountId)
				.orElseThrow(() -> new IllegalStateException("Account disappeared during processing"));
		TransactionEntity transaction = transactionRepository.findByIdForUpdate(transactionId)
				.orElseThrow(() -> new LostProcessingClaimException("Claimed transaction no longer exists"));
		if (!transaction.getTransactionId().equals(transactionId)
				|| transaction.getStatus() != TransactionStatus.PROCESSING
				|| !claimId.equals(transaction.getProcessingClaimId())
				|| !accountId.equals(transaction.getAccount().getAccountId())
				|| transaction.getSequenceNumber() != account.getExpectedSequenceNumber()) {
			throw new LostProcessingClaimException("Transaction claim is no longer active");
		}

		if (properties.getSimulatedTransientFailureTransactionIds().contains(transactionId)
				&& transaction.getAttemptCount() == 1) {
			throw new TransientTransactionProcessingException("Simulated transient processing failure");
		}

		if (transaction.getTransactionType() == TransactionType.REVERSAL) {
			Optional<TransactionEntity> original = transactionRepository
					.findById(transaction.getReversalOfTransactionId());
			if (original.isEmpty() || original.get().getStatus() != TransactionStatus.PROCESSED) {
				transaction.markWaitingForOriginal();
				return;
			}
			if (!isValidReversal(transaction, original.get(), account)) {
				markFailed(transaction, "Reversal does not match a processed CREDIT or DEBIT on this account");
				return;
			}
			applyTransaction(account, transaction, reversalDelta(original.get()));
			return;
		}

		if (!account.getCurrency().equals(transaction.getCurrency())) {
			markFailed(transaction, "Transaction currency does not match the account currency");
			return;
		}
		if (transaction.getTransactionType() == TransactionType.DEBIT
				&& account.getBalance().compareTo(transaction.getAmount()) < 0) {
			markFailed(transaction, "Insufficient account balance");
			return;
		}

		BigDecimal amountDelta = transaction.getTransactionType() == TransactionType.CREDIT
				? transaction.getAmount()
				: transaction.getAmount().negate();
		applyTransaction(account, transaction, amountDelta);
	}

	private boolean isValidReversal(TransactionEntity reversal, TransactionEntity original, AccountEntity account) {
		return (original.getTransactionType() == TransactionType.CREDIT
				|| original.getTransactionType() == TransactionType.DEBIT)
				&& original.getAccount().getAccountId().equals(account.getAccountId())
				&& original.getCurrency().equals(reversal.getCurrency())
				&& account.getCurrency().equals(reversal.getCurrency())
				&& original.getAmount().compareTo(reversal.getAmount()) == 0;
	}

	private BigDecimal reversalDelta(TransactionEntity original) {
		return original.getTransactionType() == TransactionType.CREDIT
				? original.getAmount().negate()
				: original.getAmount();
	}

	private void applyTransaction(AccountEntity account, TransactionEntity transaction, BigDecimal amountDelta) {
		BigDecimal balanceAfter = account.getBalance().add(amountDelta);
		account.applyBalanceDelta(amountDelta);
		ledgerEntryRepository.saveAndFlush(
				new LedgerEntryEntity(transaction, account, amountDelta, balanceAfter));
		transaction.markProcessed();
		account.advanceSequence(transaction.getSequenceNumber());
		logger.info("Processed transactionId={} sequenceNumber={}",
				transaction.getTransactionId(), transaction.getSequenceNumber());
	}

	private void markFailed(TransactionEntity transaction, String reason) {
		transaction.markFailed(reason);
		logger.warn("Transaction processing failed transactionId={} reason={}",
				transaction.getTransactionId(), reason);
	}
}