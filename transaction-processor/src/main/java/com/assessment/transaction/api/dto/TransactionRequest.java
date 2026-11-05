package com.assessment.transaction.api.dto;

import com.assessment.transaction.domain.TransactionType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransactionRequest(
		@NotBlank @Size(max = 100) String transactionId,
		@NotBlank @Size(max = 100) String accountId,
		@Positive long sequenceNumber,
		@NotNull TransactionType transactionType,
		@NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
		@NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
		@Size(max = 100) String reversalOfTransactionId) {

	@AssertTrue(message = "reversalOfTransactionId is required only for REVERSAL transactions")
	public boolean isReversalReferenceValid() {
		if (transactionType == TransactionType.REVERSAL) {
			return reversalOfTransactionId != null && !reversalOfTransactionId.isBlank();
		}
		return reversalOfTransactionId == null;
	}
}