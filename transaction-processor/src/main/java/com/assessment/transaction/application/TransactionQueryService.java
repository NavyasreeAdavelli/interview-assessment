package com.assessment.transaction.application;

import com.assessment.transaction.api.dto.TransactionResponse;
import com.assessment.transaction.persistence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionQueryService {
	private final TransactionRepository transactionRepository;
	private final TransactionResponseMapper responseMapper;

	public TransactionQueryService(TransactionRepository transactionRepository,
			TransactionResponseMapper responseMapper) {
		this.transactionRepository = transactionRepository;
		this.responseMapper = responseMapper;
	}

	@Transactional(readOnly = true)
	public TransactionResponse getTransaction(String transactionId) {
		return transactionRepository.findById(transactionId)
				.map(responseMapper::toResponse)
				.orElseThrow(() -> new TransactionNotFoundException(transactionId));
	}
}