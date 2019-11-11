package org.egov.waterConnection.service;

import java.util.List;

import org.egov.common.contract.request.RequestInfo;
import org.egov.waterConnection.model.SewerageConnection;
import org.egov.waterConnection.model.SewerageConnectionRequest;
import org.egov.waterConnection.model.SearchCriteria;

public interface SewarageService {

	public List<SewerageConnection> createSewarageConnection(SewerageConnectionRequest sewarageConnectionRequest);

	public List<SewerageConnection> search(SearchCriteria criteria, RequestInfo requestInfo);

	public List<SewerageConnection> updateSewarageConnection(SewerageConnectionRequest sewarageConnectionRequest);

}