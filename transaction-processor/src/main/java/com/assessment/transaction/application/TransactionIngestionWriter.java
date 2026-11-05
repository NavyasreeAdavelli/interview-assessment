package com.assessment.transaction.application;

import com.assessment.transaction.api.dto.TransactionIngestionResponse;
import com.assessment.transaction.api.dto.TransactionRequest;
import com.assessment.transaction.domain.SubmissionOutcome;
import com.assessment.transaction.domain.TransactionType;
import com.assessment.transaction.persistence.entity.AccountEntity;
import com.assessment.transaction.persistence.entity.SubmissionEntity;
import com.assessment.transaction.persistence.entity.TransactionEntity;
import com.assessment.transaction.persistence.repository.AccountRepository;
import com.assessment.transaction.persistence.repository.SubmissionRepository;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TransactionIngestionWriter {
	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final SubmissionRepository submissionRepository;
	private final TransactionResponseMapper responseMapper;

	public TransactionIngestionWriter(AccountRepository accountRepository,
			TransactionRepository transactionRepository, SubmissionRepository submissionRepository,
			TransactionResponseMapper responseMapper) {
		this.accountRepository = accountRepository;
		this.transactionRepository = transactionRepository;
		this.submissionRepository = submissionRepository;
		this.responseMapper = responseMapper;
	}

	@Transactional
	public TransactionIngestionResponse write(TransactionRequest request, String idempotencyKey,
			String payloadFingerprint) {
		Optional<SubmissionEntity> existingSubmission = submissionRepository.findById(idempotencyKey);
		if (existingSubmission.isPresent()) {
			return replay(existingSubmission.get(), payloadFingerprint);
		}

		Optional<TransactionEntity> existingTransaction = transactionRepository.findById(request.transactionId());
		if (existingTransaction.isPresent()) {
			return recordDuplicate(existingTransaction.get(), idempotencyKey, payloadFingerprint);
		}

		if (request.transactionType() == TransactionType.REVERSAL) {
			transactionRepository.findByReversalOfTransactionId(request.reversalOfTransactionId())
					.ifPresent(reversal -> {
						throw new TransactionConflictException("The original transaction already has a reversal");
					});
		}

		AccountEntity account = findOrCreateAccount(request);
		Optional<TransactionEntity> sequenceOwner = transactionRepository
				.findByAccount_AccountIdAndSequenceNumber(request.accountId(), request.sequenceNumber());
		if (sequenceOwner.isPresent()) {
			throw new TransactionConflictException("The account sequence is already assigned to another transaction");
		}

		TransactionEntity transaction = new TransactionEntity(
				request.transactionId(), account, request.sequenceNumber(), request.transactionType(),
				request.amount(), request.currency(), request.reversalOfTransactionId());
		transactionRepository.saveAndFlush(transaction);

		SubmissionEntity submission = new SubmissionEntity(
				idempotencyKey, payloadFingerprint, SubmissionOutcome.ACCEPTED, transaction);
		submissionRepository.saveAndFlush(submission);
		return response(SubmissionOutcome.ACCEPTED, transaction);
	}

	private AccountEntity findOrCreateAccount(TransactionRequest request) {
		Optional<AccountEntity> existingAccount = accountRepository.findById(request.accountId());
		if (existingAccount.isPresent()) {
			AccountEntity account = existingAccount.get();
			if (!account.getCurrency().equals(request.currency())) {
				throw new TransactionConflictException("Transaction currency does not match the account currency");
			}
			return account;
		}
		return accountRepository.saveAndFlush(new AccountEntity(request.accountId(), request.currency()));
	}

	private TransactionIngestionResponse replay(SubmissionEntity submission, String payloadFingerprint) {
		if (!submission.getPayloadFingerprint().equals(payloadFingerprint)) {
			throw new TransactionConflictException("Idempotency-Key was already used with a different payload");
		}
		return response(submission.getOutcome(), submission.getTransaction());
	}

	private TransactionIngestionResponse recordDuplicate(TransactionEntity existingTransaction,
			String idempotencyKey, String payloadFingerprint) {
		if (!TransactionPayloadFingerprint.of(existingTransaction).equals(payloadFingerprint)) {
			throw new TransactionConflictException("Transaction ID was already used with different business details");
		}
		SubmissionEntity duplicateSubmission = new SubmissionEntity(
				idempotencyKey, payloadFingerprint, SubmissionOutcome.DUPLICATE, existingTransaction);
		submissionRepository.saveAndFlush(duplicateSubmission);
		return response(SubmissionOutcome.DUPLICATE, existingTransaction);
	}

	private TransactionIngestionResponse response(SubmissionOutcome outcome, TransactionEntity transaction) {
		return new TransactionIngestionResponse(outcome, responseMapper.toResponse(transaction));
	}
}