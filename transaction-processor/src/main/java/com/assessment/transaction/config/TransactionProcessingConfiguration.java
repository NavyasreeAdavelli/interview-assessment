package com.assessment.transaction.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(TransactionProcessingProperties.class)
public class TransactionProcessingConfiguration {
	@Bean
	@ConditionalOnMissingBean
	Clock transactionClock() {
		return Clock.systemUTC();
	}
}