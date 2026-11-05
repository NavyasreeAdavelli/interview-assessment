package com.assessment.transaction.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransactionRecoveryRequest(
		@NotBlank @Size(max = 1000) String reason,
		@NotBlank @Size(max = 100) String operatorIdentity) {
}