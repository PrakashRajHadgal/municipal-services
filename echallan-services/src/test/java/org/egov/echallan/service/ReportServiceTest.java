package org.egov.echallan.service;

import java.util.ArrayList;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.echallan.producer.Producer;
import org.egov.echallan.repository.ReportRepository;
import org.egov.echallan.web.models.DashboardDetails;
import org.egov.echallan.web.models.Report;
import org.egov.echallan.web.models.RequestInfoWrapper;
import org.egov.echallan.workflow.WorkflowIntegrator;
import org.egov.tracer.model.CustomException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

@RunWith(MockitoJUnitRunner.class)
public class ReportServiceTest {

	@Mock
	private ReportRepository repository;

	@InjectMocks
	private ReportService service;

	/*@Mock
	private ResponseInfoFactory responseInfoFactory;*/

	@InjectMocks
	private Report ReportMaster;

	@Mock
	private Producer producer;

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	WorkflowIntegrator wfIntegrator;

	@Test
	public void testGetReport() throws Exception {
		Report report = Report.builder().build();
		RequestInfoWrapper infoWrapper = RequestInfoWrapper.builder().requestBody(report)
				.requestInfo(RequestInfo.builder().userInfo(User.builder().tenantId("ch").build()).build()).build();
		
		Mockito.when(objectMapper.convertValue(infoWrapper.getRequestBody(), Report.class)).thenReturn(report);
		Mockito.when(repository.getReport(report)).thenReturn(new ArrayList<Report>());
		Assert.assertEquals(HttpStatus.OK, service.getReport(infoWrapper).getStatusCode());
	}

	@Test(expected = CustomException.class)
	public void testGetReportException() throws CustomException {
		service.getReport(null);
	}
	
	@Test
	public void testGetDashboard() throws Exception {
		DashboardDetails report = DashboardDetails.builder().build();
		RequestInfoWrapper infoWrapper = RequestInfoWrapper.builder().requestBody(report)
				.requestInfo(RequestInfo.builder().userInfo(User.builder().tenantId("ch").build()).build()).build();
		
		Mockito.when(objectMapper.convertValue(infoWrapper.getRequestBody(), DashboardDetails.class)).thenReturn(report);
		Mockito.when(repository.getDashboard(infoWrapper)).thenReturn(new ArrayList<DashboardDetails>());
		Assert.assertEquals(HttpStatus.OK, service.getDashboard(infoWrapper).getStatusCode());
	}

}