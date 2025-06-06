package org.egov.inbox.web.controller;

import java.math.BigDecimal;
import java.util.Map;

import org.egov.inbox.repository.builder.V2.InboxQueryBuilder;
import org.egov.inbox.service.DSSInboxFilterService;
import org.egov.inbox.service.InboxService;
import org.egov.inbox.util.ResponseInfoFactory;
import org.egov.inbox.web.model.InboxRequest;
import org.egov.inbox.web.model.InboxResponse;
import org.egov.inbox.web.model.dss.InboxMetricCriteria;
import org.egov.inbox.web.model.elasticsearch.InboxElasticSearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Home redirection to swagger api documentation 
 */
@RestController
@RequestMapping("/v1")
@Slf4j
public class InboxController {
	
	@Autowired
	private InboxService inboxService;
	
	
	@Autowired
	private ResponseInfoFactory responseInfoFactory;

	@Autowired
	private DSSInboxFilterService dssInboxService;

	//@Autowired
	//private ElasticSearchService elasticSearchService;

	@Autowired
	private InboxQueryBuilder inboxQueryBuilder;
	
	
	@PostMapping(value = "/_search")
	public ResponseEntity<InboxResponse> search(@Valid @RequestBody  InboxRequest inboxRequest) {
		
		InboxResponse response = inboxService.fetchInboxData(inboxRequest.getInbox(),inboxRequest.getRequestInfo());
		
		response.setResponseInfo(
				responseInfoFactory.createResponseInfoFromRequestInfo(inboxRequest.getRequestInfo(), true));
		return new ResponseEntity<>(response, HttpStatus.OK);
	}

	@PostMapping(value = "/dss/_search")
	public ResponseEntity<Map<String, BigDecimal>> getChartV2(@Valid @RequestBody InboxMetricCriteria request) {
		Map<String, BigDecimal> response = dssInboxService.getAggregateData(request);
		return new ResponseEntity<>(response, HttpStatus.OK);
	}

	@PostMapping(value = "/elastic/_search")
	public ResponseEntity<Map<String, Object>>  elasticSearch(@Valid @RequestBody InboxElasticSearchRequest request) {
		Map<String, Object> data = null;
		return new ResponseEntity<>(data, HttpStatus.OK);
	}
	
}
