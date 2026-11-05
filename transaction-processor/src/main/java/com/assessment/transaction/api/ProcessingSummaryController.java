package com.assessment.transaction.api;

import com.assessment.transaction.api.dto.ProcessingSummaryResponse;
import com.assessment.transaction.application.ProcessingSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/processing")
public class ProcessingSummaryController {
	private final ProcessingSummaryService summaryService;

	public ProcessingSummaryController(ProcessingSummaryService summaryService) {
		this.summaryService = summaryService;
	}

	@GetMapping("/summary")
	public ProcessingSummaryResponse getSummary() {
		return summaryService.getSummary();
	}
}