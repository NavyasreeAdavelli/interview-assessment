package com.assessment.transaction.application;

import com.assessment.transaction.api.dto.TransactionRequest;
import com.assessment.transaction.persistence.entity.TransactionEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class TransactionPayloadFingerprint {
	private TransactionPayloadFingerprint() {
	}

	static String of(TransactionRequest request) {
		return hash(canonicalPayload(
				request.transactionId(),
				request.accountId(),
				request.sequenceNumber(),
				request.transactionType().name(),
				request.amount().stripTrailingZeros().toPlainString(),
				request.currency(),
				request.reversalOfTransactionId()));
	}

	static String of(TransactionEntity transaction) {
		return hash(canonicalPayload(
				transaction.getTransactionId(),
				transaction.getAccount().getAccountId(),
				transaction.getSequenceNumber(),
				transaction.getTransactionType().name(),
				transaction.getAmount().stripTrailingZeros().toPlainString(),
				transaction.getCurrency(),
				transaction.getReversalOfTransactionId()));
	}

	private static String canonicalPayload(String transactionId, String accountId, long sequenceNumber,
			String transactionType, String amount, String currency, String reversalOfTransactionId) {
		StringBuilder canonical = new StringBuilder();
		append(canonical, transactionId);
		append(canonical, accountId);
		append(canonical, Long.toString(sequenceNumber));
		append(canonical, transactionType);
		append(canonical, amount);
		append(canonical, currency);
		append(canonical, reversalOfTransactionId);
		return canonical.toString();
	}

	private static void append(StringBuilder target, String value) {
		if (value == null) {
			target.append("-1:");
			return;
		}
		target.append(value.length()).append(':').append(value);
	}

	private static String hash(String canonicalPayload) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}