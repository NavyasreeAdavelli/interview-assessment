package com.assessment.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "transaction-processing")
public class TransactionProcessingProperties {
	private int maxAttempts = 3;
	private Duration retryBackoff = Duration.ofSeconds(2);
	private Duration processingClaimTimeout = Duration.ofSeconds(30);
	private Set<String> simulatedTransientFailureTransactionIds = new LinkedHashSet<>();

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least one");
		}
		this.maxAttempts = maxAttempts;
	}

	public Duration getRetryBackoff() {
		return retryBackoff;
	}

	public void setRetryBackoff(Duration retryBackoff) {
		if (retryBackoff == null || retryBackoff.isNegative()) {
			throw new IllegalArgumentException("retryBackoff must not be negative");
		}
		this.retryBackoff = retryBackoff;
	}

	public Duration getProcessingClaimTimeout() {
		return processingClaimTimeout;
	}

	public void setProcessingClaimTimeout(Duration processingClaimTimeout) {
		if (processingClaimTimeout == null || processingClaimTimeout.isNegative()) {
			throw new IllegalArgumentException("processingClaimTimeout must not be negative");
		}
		this.processingClaimTimeout = processingClaimTimeout;
	}

	public Set<String> getSimulatedTransientFailureTransactionIds() {
		return simulatedTransientFailureTransactionIds;
	}

	public void setSimulatedTransientFailureTransactionIds(Set<String> transactionIds) {
		this.simulatedTransientFailureTransactionIds = new LinkedHashSet<>(transactionIds);
	}
}