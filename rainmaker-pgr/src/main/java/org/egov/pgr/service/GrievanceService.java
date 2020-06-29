package org.egov.pgr.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.common.contract.request.User;
import org.egov.mdms.model.MdmsCriteriaReq;
import org.egov.pgr.contract.Address;
import org.egov.pgr.contract.CountResponse;
import org.egov.pgr.contract.IdResponse;
import org.egov.pgr.contract.RequestInfoWrapper;
import org.egov.pgr.contract.SearcherRequest;
import org.egov.pgr.contract.ServiceReqSearchCriteria;
import org.egov.pgr.contract.ServiceRequest;
import org.egov.pgr.contract.ServiceRequestDetails;
import org.egov.pgr.contract.ServiceResponse;
import org.egov.pgr.model.ActionHistory;
import org.egov.pgr.model.ActionInfo;
import org.egov.pgr.model.AuditDetails;
import org.egov.pgr.model.Service;
import org.egov.pgr.model.Service.StatusEnum;
import org.egov.pgr.model.user.Citizen;
import org.egov.pgr.model.user.CreateUserRequest;
import org.egov.pgr.model.user.UserResponse;
import org.egov.pgr.model.user.UserSearchRequest;
import org.egov.pgr.model.user.UserType;
import org.egov.pgr.producer.PGRProducer;
import org.egov.pgr.repository.FileStoreRepo;
import org.egov.pgr.repository.IdGenRepo;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.utils.ErrorConstants;
import org.egov.pgr.utils.PGRConstants;
import org.egov.pgr.utils.PGRUtils;
import org.egov.pgr.utils.ResponseInfoFactory;
import org.egov.pgr.utils.WorkFlowConfigs;
import org.egov.pgr.validator.PGRRequestValidator;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

@org.springframework.stereotype.Service
@Slf4j
public class GrievanceService {

	@Value("${kafka.topics.save.service}")
	private String saveTopic;

	@Value("${kafka.topics.update.service}")
	private String updateTopic;
	
	@Value("${kafka.topics.save.index.service}")
	private String saveIndexTopic;

	@Value("${kafka.topics.update.index.service}")
	private String updateIndexTopic;
	
	@Value("${egov.hr.employee.v2.host}")
	private String hrEmployeeHost;

	@Value("${egov.hr.employee.v2.search.endpoint}")
	private String hrEmployeeV2SearchEndpoint;
		
	@Value("${egov.user.host}")
	private String userBasePath;
	
	@Value("${egov.user.search.endpoint}")
	private String userSearchEndPoint;
	
	@Value("${egov.user.create.endpoint}")
	private String userCreateEndPoint;
	
	@Value("${notification.fallback.locale}")
    private String fallbackLocale;

	@Autowired
	private ResponseInfoFactory factory;

	@Autowired
	private IdGenRepo idGenRepo;

	@Autowired
	private PGRUtils pGRUtils;

	@Autowired
	private PGRProducer pGRProducer;

	@Autowired
	private FileStoreRepo fileStoreRepo;

	@Autowired
	private ServiceRequestRepository serviceRequestRepository;
	
	@Autowired
	private NotificationService notificationService;
	
	@Autowired
	private PGRRequestValidator pgrRequestValidator;
	
	public static Map<String, Map<String, String>> localizedMessageMap = new HashMap<>();

	/***
	 * Asynchronous method performs business logic if any and adds the data to
	 * persister queue on create topic
	 * 
	 * @param request
	 */
	public ServiceResponse create(ServiceRequest request) {
		log.info("Service layer for createss");
		enrichserviceRequestForcreate(request);
		pGRProducer.push(saveTopic, request);
		pGRProducer.push(saveIndexTopic, dataTranformationForIndexer(request, true));
		return getServiceResponse(request);
	}

	/**
	 * Asynchronous method performs business logic if any and adds the data to
	 * persister queue on update topic
	 * 
	 * @param request
	 */
	public ServiceResponse update(ServiceRequest request) {
		enrichServiceRequestForUpdate(request);
		if (null == request.getActionInfo())
			request.setActionInfo(new ArrayList<ActionInfo>());
		pGRProducer.push(updateTopic, request);
		pGRProducer.push(updateIndexTopic, dataTranformationForIndexer(request, false));
		return getServiceResponse(request);
	}
	
	/**
	 * private method to enrich request with Ids and action infos for create
	 * 
	 * @param serviceRequest
	 */
	private void enrichserviceRequestForcreate(ServiceRequest serviceRequest) {
		log.info("enriching service request create.");
		Map<String, String> actionStatusMap = WorkFlowConfigs.getActionStatusMap();
		RequestInfo requestInfo = serviceRequest.getRequestInfo();
		List<Service> serviceReqs = serviceRequest.getServices();
		String tenantId = serviceReqs.get(0).getTenantId();
		overRideCitizenAccountId(serviceRequest);
		validateAndCreateUser(serviceRequest);
		List<String> servReqIdList = getIdList(requestInfo, tenantId, serviceReqs.size(), PGRConstants.SERV_REQ_ID_NAME,
				PGRConstants.SERV_REQ_ID_FORMAT);
		AuditDetails auditDetails = pGRUtils.getAuditDetails(String.valueOf(requestInfo.getUserInfo().getId()), true);
		String by = auditDetails.getCreatedBy() + ":" + requestInfo.getUserInfo().getRoles().get(0).getName();
		List<ActionInfo> actionInfos = new LinkedList<>();
		if(!CollectionUtils.isEmpty(serviceRequest.getActionInfo())) {
			actionInfos = serviceRequest.getActionInfo();
		}
		boolean isAutoroutingAvailable = false;
		for (int servReqCount = 0; servReqCount < serviceReqs.size(); servReqCount++) {
			Service servReq = serviceReqs.get(servReqCount);
			String currentId = servReqIdList.get(servReqCount);
			ActionInfo actionInfo = null;
			try {
				actionInfo = actionInfos.get(servReqCount);
				if(null != actionInfo) {
					actionInfo.setUuid(UUID.randomUUID().toString()); actionInfo.setBusinessKey(currentId);
					actionInfo.setAction(WorkFlowConfigs.ACTION_OPEN); actionInfo.setAssignee(null); actionInfo.setBy(by);
					actionInfo.setWhen(auditDetails.getCreatedTime()); actionInfo.setTenantId(tenantId); actionInfo.setStatus(actionStatusMap.get(WorkFlowConfigs.ACTION_OPEN));
					
				}else {
					ActionInfo newActionInfo = ActionInfo.builder().uuid(UUID.randomUUID().toString()).businessKey(currentId)
							.action(WorkFlowConfigs.ACTION_OPEN).assignee(null).by(by).when(auditDetails.getCreatedTime()).tenantId(tenantId)
							.status(actionStatusMap.get(WorkFlowConfigs.ACTION_OPEN)).build();
					actionInfos.add(newActionInfo);
				}
			}catch(Exception e) {
				ActionInfo newActionInfo = ActionInfo.builder().uuid(UUID.randomUUID().toString()).businessKey(currentId)
						.action(WorkFlowConfigs.ACTION_OPEN).assignee(null).by(by).when(auditDetails.getCreatedTime()).tenantId(tenantId)
						.status(actionStatusMap.get(WorkFlowConfigs.ACTION_OPEN)).build();
				actionInfos.add(newActionInfo);
			}
			
			String employeeCode = "";
			try {
				//Find autorouting employee for new complaint
				employeeCode = fetchAutoroutingEmployee(requestInfo, tenantId, servReq);
				
				if(!StringUtils.isEmpty(employeeCode)) {
					UserResponse userResponse = getUser(requestInfo, tenantId, employeeCode);
					
					if(!CollectionUtils.isEmpty(userResponse.getUser())) {
						ActionInfo newActionInfo = ActionInfo.builder().uuid(UUID.randomUUID().toString()).businessKey(currentId)
								.action(WorkFlowConfigs.ACTION_ASSIGN).assignee(String.valueOf(userResponse.getUser().get(0).getId()))
								.by(by).when(auditDetails.getCreatedTime()+1).tenantId(tenantId)
								.status(actionStatusMap.get(WorkFlowConfigs.ACTION_ASSIGN)).build();
						actionInfos.add(newActionInfo);
						
						isAutoroutingAvailable = true;
					}
				}else {
					log.info("Employee {} not found for auto routing.",employeeCode);
				}
			}catch(Exception e) {
				log.error("Unable to auto assign complaint to employee {}",employeeCode,e);
			}
			
			if(!isAutoroutingAvailable) {
				log.info("Auto routing employee not available for this compliant.");
			}
			
			servReq.setAuditDetails(auditDetails); servReq.setServiceRequestId(currentId);servReq.setActive(true);
			if(isAutoroutingAvailable) {
				servReq.setStatus(StatusEnum.ASSIGNED);
			}else {
				servReq.setStatus(StatusEnum.OPEN);
			}
			servReq.setFeedback(null);servReq.setRating(null); 
			servReq.getAddressDetail().setUuid(UUID.randomUUID().toString());
			servReq.getAddressDetail().setAuditDetails(auditDetails);
			servReq.getAddressDetail().setTenantId(tenantId);
			servReq.setAddressId(servReq.getAddressDetail().getUuid());
			
			List<Object> serivceDefs = getServiceType(servReq, requestInfo);
			if(!CollectionUtils.isEmpty(serivceDefs))
				servReq.setCategory(String.valueOf(serivceDefs.get(0)));
				if((Integer)serivceDefs.get(3) > 0) {
					long slaEndTime = pGRUtils.getLastDayTime(Integer.parseInt(serivceDefs.get(3).toString()));
					log.info("sla end time set to midnight:"+slaEndTime);
					//servReq.setSlaEndTime(auditDetails.getCreatedTime() + Long.parseLong(serivceDefs.get(3).toString())*24*60*60*1000);
					servReq.setSlaEndTime(slaEndTime);
				}
		}
		serviceRequest.setActionInfo(actionInfos);
	}
	
	/**
	 * Override the accountId of every request with the user id
	 * @param serviceRequest
	 */
	private void overRideCitizenAccountId(ServiceRequest serviceRequest) {
		User user = serviceRequest.getRequestInfo().getUserInfo();
		List<String> codes = user.getRoles().stream().map(Role::getCode).collect(Collectors.toList());
		if (codes.contains(PGRConstants.ROLE_CITIZEN) || codes.contains(PGRConstants.ROLE_NAME_CITIZEN))
			serviceRequest.getServices().forEach(service -> service.setAccountId(String.valueOf(user.getId())));
	}
	
	/**
	 * Checks if the user is present for the given citizen object.
	 * 
	 * @param citizen
	 * @param requestInfo
	 * @param tenantId
	 * @return
	 */
	private String isUserPresent(Citizen citizen, RequestInfo requestInfo, String tenantId) {
		ObjectMapper mapper = pGRUtils.getObjectMapper();
		UserSearchRequest searchRequest = UserSearchRequest.builder().userName(citizen.getMobileNumber())
				.tenantId(tenantId).userType(PGRConstants.ROLE_CITIZEN).requestInfo(requestInfo).build();
		StringBuilder url = new StringBuilder(userBasePath+userSearchEndPoint); 
		UserResponse res = mapper.convertValue(serviceRequestRepository.fetchResult(url, searchRequest), UserResponse.class);
		if(CollectionUtils.isEmpty(res.getUser())) {
			return null;
		}
		return res.getUser().get(0).getId().toString();
	}
	
	/**
	 * When CSR files a complaint, this method captures the user information if the user exists otherwise creates the user.
	 * 
	 * @param serviceRequest
	 * @param errorMap
	 */
	private void validateAndCreateUser(ServiceRequest serviceRequest) {
		RequestInfo requestInfo = serviceRequest.getRequestInfo();
		List<String> roles = requestInfo.getUserInfo().getRoles().stream().map(Role::getCode)
				.collect(Collectors.toList());
		if(roles.contains(PGRConstants.ROLE_NAME_CSR) || roles.contains(PGRConstants.ROLE_CSR)) {
			serviceRequest.getServices().stream().forEach(request -> {
				String accId = null;
				if (null != request.getCitizen()) {
					accId = isUserPresent(request.getCitizen(),requestInfo,request.getTenantId());
					if (StringUtils.isEmpty(accId)) {
						accId = createUser(request.getCitizen(),requestInfo,request.getTenantId());
					}
					request.setAccountId(accId);
				}
			});	
		}
	}
	
	/**
	 * This method creates user in user svc.
	 * 
	 * @param citizen
	 * @param requestInfo
	 * @param tenantId
	 * @return
	 */
	private String createUser(Citizen citizen, RequestInfo requestInfo, String tenantId) {
		ObjectMapper mapper = pGRUtils.getObjectMapper();
		citizen.setUserName(citizen.getMobileNumber());
		citizen.setActive(true);
		citizen.setTenantId(tenantId);
		citizen.setType(UserType.CITIZEN);
		citizen.setRoles(Arrays.asList(org.egov.pgr.model.user.Role.builder().code(PGRConstants.ROLE_CITIZEN).build()));
		StringBuilder url = new StringBuilder(userBasePath+userCreateEndPoint); 
		CreateUserRequest req = CreateUserRequest.builder().citizen(citizen).requestInfo(requestInfo).build();
		UserResponse res = mapper.convertValue(serviceRequestRepository.fetchResult(url, req), UserResponse.class);
		return res.getUser().get(0).getId().toString();
	}
	/**
	 * Since the request and response formats of PGR are different, indexer operations of INDEX and LEGACYINDEX will be affected.
	 * This method ensures the following:
	 * 1. Data format being sent to indexer is of the same type for create, update as that of search as LEGACYINDEX makes use of search
	 * 2. Data during update, takes the entire actionhistory alongwith the currently performed action.
	 * 
	 * @param request
	 * @return
	 */
	private ServiceResponse dataTranformationForIndexer(ServiceRequest request, boolean isCreate) {
		/**
		 * This might seem inefficient but in our use-case, create and update happen for just one compliant 95% of the times, so loop runs just once.
		 */
		List<Service> services = new ArrayList<Service>();
		List<ActionHistory> actionHistoryList = new ArrayList<ActionHistory>();
		if(isCreate) {
			for(int i = 0; i < request.getServices().size(); i++) {
				ActionHistory actionHistory = new ActionHistory();
				List<ActionInfo> actions = new ArrayList<>();
				actions.add(request.getActionInfo().get(i));
				actionHistory.setActions(actions);
				actionHistoryList.add(actionHistory);
				services.add(request.getServices().get(i));
			}
		}else {
			for(int i = 0; i < request.getServices().size(); i++) {
				ObjectMapper mapper = pGRUtils.getObjectMapper();
				ActionHistory actionHistory = new ActionHistory();
				List<ActionInfo> actions = new ArrayList<>();
				List<String> serviceRequestIds = new ArrayList<String>();
				serviceRequestIds.add(request.getServices().get(i).getServiceRequestId());
				ServiceReqSearchCriteria serviceReqSearchCriteria = ServiceReqSearchCriteria.builder()
						.tenantId(request.getServices().get(i).getTenantId()).serviceRequestId(serviceRequestIds).build();
				ServiceResponse serviceResponse = mapper.convertValue(getServiceRequestDetails(request.getRequestInfo(), serviceReqSearchCriteria), ServiceResponse.class);
				actionHistory = serviceResponse.getActionHistory().get(0);
				actions.add(request.getActionInfo().get(i));
				actions.addAll(actionHistory.getActions());
				actionHistory.setActions(actions);
				AuditDetails auditDetails = AuditDetails.builder().createdBy(serviceResponse.getServices().get(0).getAuditDetails().getCreatedBy())
						.createdTime(serviceResponse.getServices().get(0).getAuditDetails().getCreatedTime())
						.lastModifiedBy(request.getServices().get(0).getAuditDetails().getLastModifiedBy())
						.lastModifiedTime(request.getServices().get(0).getAuditDetails().getLastModifiedTime()).build();
				request.getServices().get(0).setAuditDetails(auditDetails);
				services.add(request.getServices().get(0));
				actionHistoryList.add(actionHistory);

			}
		}
		return ServiceResponse.builder().services(services).actionHistory(actionHistoryList).build();
	}

	/**
	 * Util method for the update to enrich the actions in the request 
	 * 
	 * @param request
	 */
	private void enrichServiceRequestForUpdate(ServiceRequest request) {
		Map<String, String> actionStatusMap = WorkFlowConfigs.getActionStatusMap(); 
		Map<String, List<String>> errorMap = new HashMap<>();
		RequestInfo requestInfo = request.getRequestInfo();
		List<Service> serviceReqs = request.getServices();
		List<ActionInfo> actionInfos = request.getActionInfo();
		final AuditDetails auditDetails = pGRUtils.getAuditDetails(String.valueOf(requestInfo.getUserInfo().getId()),false);
		Map<String, String> errorMap1 = new HashMap<>();
		ServiceResponse serviceResponse = pgrRequestValidator.getServiceRequests(request, errorMap1);
		List<ActionHistory> historys = serviceResponse.getActionHistory();
		Map<String, ActionHistory> historyMap = new HashMap<>();
		historys.forEach(a -> historyMap.put(a.getActions().get(0).getBusinessKey(), a));
		
		for (int index = 0; index < serviceReqs.size(); index++) {
			Service service = serviceReqs.get(index);
			ActionInfo actionInfo = actionInfos.get(index);
			actionInfo.setStatus(actionStatusMap.get(actionInfo.getAction()));	
			//actionInfo.status(actionInfo.getAction()); 
			service.setAuditDetails(auditDetails); 
			if(service.getActive() == null) service.setActive(true);
			
			//If the action is reopen, then go through the action history & find whether it is reopened 
			//1st time(last resolved by LME) or 2nd time(last resolved by escalation officer1)
			
			ActionHistory history = historyMap.get(service.getServiceRequestId());
						
			if(!StringUtils.isEmpty(actionInfo.getAction())) {
				if(pGRUtils.checkReopen2ndTime(history, actionInfo.getAction())) {
					service.setStatus(StatusEnum.fromValue(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING));
					actionInfo.setStatus(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING);	
				}else {
					service.setStatus(StatusEnum.fromValue(actionStatusMap.get(actionInfo.getAction())));
				}
			}
			String role = pGRUtils.getPrecedentRole(requestInfo.getUserInfo().getRoles().stream().map(Role::getCode)
					.collect(Collectors.toList()));
			actionInfo.setUuid(UUID.randomUUID().toString()); actionInfo.setBusinessKey(service.getServiceRequestId()); 
			actionInfo.setBy(auditDetails.getLastModifiedBy() + ":" + role); actionInfo.setWhen(auditDetails.getLastModifiedTime());
			actionInfo.setTenantId(service.getTenantId()); 
			
			//If GRO resolves/reject any complaint (LME assigned complaint/ Escalated complaint) then we set the assignee.
			
			if(PGRConstants.ROLE_GRO.equalsIgnoreCase(role) 
					&& (WorkFlowConfigs.ACTION_RESOLVE.equalsIgnoreCase(actionInfo.getAction())
							|| WorkFlowConfigs.ACTION_REJECT.equalsIgnoreCase(actionInfo.getAction()))) {
				actionInfo.setAssignee(auditDetails.getLastModifiedBy());
			}
			//If escalated complaint is resolved or reject by escalation officer then set the assignee value of escalation officer
			else if(pGRUtils.checkComplaintAlreadyEscalated(history, actionInfo.getAction())) {
				actionInfo.setAssignee(auditDetails.getLastModifiedBy());
			}
		}
		if (!errorMap.isEmpty()) {
			Map<String, String> newMap = new HashMap<>();
			errorMap.keySet().forEach(key -> newMap.put(key, errorMap.get(key).toString()));
			throw new CustomException(newMap);
		}
	}

	/**
	 * method to parse the IdGenResponse from IdgenRepo to List of String ids
	 * required by the respective methods
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param length
	 * @param idKey
	 * @param idformat
	 * 
	 */
	private List<String> getIdList(RequestInfo requestInfo, String tenantId, Integer length, String idKey,
			String idformat) {
		return idGenRepo.getId(requestInfo, tenantId, length, idKey, idformat).getIdResponses().stream()
				.map(IdResponse::getId).collect(Collectors.toList());
	}

	/**
	 * returns ServiceResponse fetched from database/built based on the given
	 * ServiceRequest
	 * 
	 * @param serviceReqRequest
	 * @return serviceReqResponse
	 */
	public ServiceResponse getServiceResponse(ServiceRequest serviceReqRequest) {
			return ServiceResponse.builder()
					.responseInfo(factory.createResponseInfoFromRequestInfo(serviceReqRequest.getRequestInfo(), true))
					.services(serviceReqRequest.getServices())
					.actionHistory(convertActionInfosToHistorys(serviceReqRequest.getActionInfo())).build();
	}

	/**
	 * helper method to convert list of actioninfos to list of actionHistorys
	 * 
	 * @param actionInfos
	 * @return
	 */
	private List<ActionHistory> convertActionInfosToHistorys(List<ActionInfo> actionInfos) {
		List<ActionHistory> historys = new ArrayList<>();
		if (!CollectionUtils.isEmpty(actionInfos))
			actionInfos.forEach(a -> {
				List<ActionInfo> infos = new ArrayList<>();
				infos.add(a);
				historys.add(new ActionHistory(infos));
			});
		return historys;
	}

	/**
	 * Method to return service requests along with details acc to V5 design
	 * received from the repo to the controller in the reqd format
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 * @return ServiceReqResponse
	 * @author vishal
	 */
	public Object getServiceRequestDetails(RequestInfo requestInfo, ServiceReqSearchCriteria serviceReqSearchCriteria) {
		StringBuilder uri = new StringBuilder();
		SearcherRequest searcherRequest = null;
		try {
			enrichRequest(requestInfo, serviceReqSearchCriteria);
		} catch (CustomException e) {
			if (e.getMessage().equals(ErrorConstants.NO_DATA_MSG))
				return pGRUtils.getDefaultServiceResponse(requestInfo);
			else
				throw e;
		}
		
		Object response = null;
		
		List<String> codes = requestInfo.getUserInfo().getRoles().stream().map(Role::getCode).collect(Collectors.toList());
		
		if ((codes.contains(PGRConstants.ROLE_ESCALATION_OFFICER1) || codes.contains(PGRConstants.ROLE_ESCALATION_OFFICER2))
				&& CollectionUtils.isEmpty(serviceReqSearchCriteria.getServiceRequestId())) {
		/*if(!CollectionUtils.isEmpty(serviceReqSearchCriteria.getStatus()) 
				&& (serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL1_PENDING)
					|| serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING))){*/
			response = getComplaintListForEscalationOfficer(requestInfo, serviceReqSearchCriteria, uri, response);
			
			//if any complaint is assigned to an escalated officer via autorouting then fetch that complaints also.
			try {
				List<String> status = new ArrayList<String>();
				status.add(WorkFlowConfigs.STATUS_ASSIGNED);
				status.add(WorkFlowConfigs.STATUS_REASSIGN_REQUESTED);
				serviceReqSearchCriteria.setStatus(status);
				serviceReqSearchCriteria.setCategory(null);
				uri = new StringBuilder();
				enrichRequest(requestInfo, serviceReqSearchCriteria);
				searcherRequest = pGRUtils.prepareSearchRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
				Object assignedResponse = serviceRequestRepository.fetchResult(uri, searcherRequest);
				
				if(null != assignedResponse) {
					List assignedServiceList = JsonPath.read(assignedResponse, PGRConstants.COMPLAINT_JSONPATH);
					if(!CollectionUtils.isEmpty(assignedServiceList)) {
						LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>)assignedResponse;
						List obj = (List)map.get("services");
						LinkedHashMap<String, Object> map1 = (LinkedHashMap<String, Object>)response;
						List obj1 = (List)map1.get("services");
						
						List<Object> finalObj = new ArrayList<Object>();
						finalObj.addAll(obj);
						finalObj.addAll(obj1);
						
						map1.put("services", finalObj);
						
					}
				}
			} catch (CustomException e) {
				if (e.getMessage().equals(ErrorConstants.NO_DATA_MSG))
					log.debug("No complaint is assigned to this escalated officer {}",requestInfo.getUserInfo().getUserName());
			}
		}else {
			searcherRequest = pGRUtils.prepareSearchRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
			response = serviceRequestRepository.fetchResult(uri, searcherRequest);
			log.debug(PGRConstants.SEARCHER_RESPONSE_TEXT + response);
		}
		
		if (null == response)
			return pGRUtils.getDefaultServiceResponse(requestInfo);
		ServiceResponse serviceResponse = prepareResult(response, requestInfo);
		if(CollectionUtils.isEmpty(serviceResponse.getServices()))
			return serviceResponse;
		else
			return enrichResult(requestInfo, serviceResponse);
	}

	
	/**
	 * Method to return service requests along with details to plain search
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 * @return ServiceReqResponse
	 * @author vishal
	 */
	public Object getServiceRequestDetailsForPlainSearch(RequestInfo requestInfo, ServiceReqSearchCriteria serviceReqSearchCriteria) {
		StringBuilder uri = new StringBuilder();
		SearcherRequest searcherRequest = null;
		searcherRequest = pGRUtils.preparePlainSearchReq(uri, serviceReqSearchCriteria, requestInfo);
		Object response = serviceRequestRepository.fetchResult(uri, searcherRequest);
		log.debug(PGRConstants.SEARCHER_RESPONSE_TEXT + response);
		if (null == response)
			return pGRUtils.getDefaultServiceResponse(requestInfo);
		ServiceResponse serviceResponse = prepareResult(response, requestInfo);
		if(CollectionUtils.isEmpty(serviceResponse.getServices()))
			return serviceResponse;
		else
			return enrichResult(requestInfo, serviceResponse);
	}
	

	/**
	 * Method to enrich the request for search based on roles.
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 */
	public void enrichRequest(RequestInfo requestInfo, ServiceReqSearchCriteria serviceReqSearchCriteria) {
		log.info("Enriching request for search");
		String precedentRole = pGRUtils.getPrecedentRole(requestInfo.getUserInfo().getRoles().stream().map(Role::getCode)
				.collect(Collectors.toList()));
		if (requestInfo.getUserInfo().getType().equalsIgnoreCase(PGRConstants.ROLE_CITIZEN)) {
			serviceReqSearchCriteria.setAccountId(requestInfo.getUserInfo().getId().toString());
		    serviceReqSearchCriteria.setTenantId(serviceReqSearchCriteria.getTenantId().split("[.]")[0]); //citizen can search his complaints across state.
		} else if (requestInfo.getUserInfo().getType().equalsIgnoreCase(PGRConstants.ROLE_EMPLOYEE)) {
			/**
			 * GRO can search complaints belonging to only his tenant.
			 */
			if(precedentRole.equalsIgnoreCase(PGRConstants.ROLE_GRO)) {
				serviceReqSearchCriteria.setTenantId(requestInfo.getUserInfo().getTenantId());
			}
			/**
			 * DGRO belongs to a department and that department takes care of certain complaint types.
			 * A DGRO can address/see only the complaints belonging to those complaint types and to only his tenant.
			 */
			else if (precedentRole.equalsIgnoreCase(PGRConstants.ROLE_DGRO)) { 
				Object response = fetchServiceDefs(requestInfo, serviceReqSearchCriteria.getTenantId(), 
						getDepartmentCode(serviceReqSearchCriteria, requestInfo));
				if (null == response) {
					throw new CustomException(ErrorConstants.NO_DATA_KEY, ErrorConstants.NO_DATA_MSG);
				}
				try {
					List<String> serviceCodes = JsonPath.read(response, PGRConstants.JSONPATH_SERVICE_CODES);
					if(serviceCodes.isEmpty())
						throw new CustomException(ErrorConstants.NO_DATA_KEY, ErrorConstants.NO_DATA_MSG);
					log.info("serviceCodes: "+serviceCodes);
					serviceReqSearchCriteria.setServiceCodes(serviceCodes);
				} catch (Exception e) {
					throw new CustomException(ErrorConstants.NO_DATA_KEY, ErrorConstants.NO_DATA_MSG);
				}
				serviceReqSearchCriteria.setTenantId(requestInfo.getUserInfo().getTenantId());
			}
			/**
			 * An Employee can by default search only the complaints assigned to him.
			 */
			else if (precedentRole.equalsIgnoreCase(PGRConstants.ROLE_EMPLOYEE)) {
				if (StringUtils.isEmpty(serviceReqSearchCriteria.getAssignedTo()) && CollectionUtils.isEmpty(serviceReqSearchCriteria.getServiceRequestId())) {
					
					List<String> codes = requestInfo.getUserInfo().getRoles().stream().map(Role::getCode).collect(Collectors.toList());
					
					if ((codes.contains(PGRConstants.ROLE_ESCALATION_OFFICER1) || codes.contains(PGRConstants.ROLE_ESCALATION_OFFICER2))
							&& (!CollectionUtils.isEmpty(serviceReqSearchCriteria.getStatus()) 
								&& (serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL1_PENDING)
									|| serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING)))) {
						//Do not need to set assign anyone for escalation flow if the status is pending
					}
					/**if(!CollectionUtils.isEmpty(serviceReqSearchCriteria.getStatus()) 
							&& (serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL1_PENDING)
								|| serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING))){
						//Do not need to set assign anyone for escalation flow if the status is pending
					}**/
					else {
						serviceReqSearchCriteria.setAssignedTo(requestInfo.getUserInfo().getId().toString());
					}
				}
			} 
			/**
			 * CSR can search complaints across the state.
			 */
			else if (precedentRole.equalsIgnoreCase(PGRConstants.ROLE_CSR)) {
				serviceReqSearchCriteria.setTenantId(serviceReqSearchCriteria.getTenantId().split("[.]")[0]); //csr can search his complaints across state.
			}
		}
		if (!StringUtils.isEmpty(serviceReqSearchCriteria.getAssignedTo())) {
			List<String> serviceRequestIds = getServiceRequestIdsOnAssignedTo(requestInfo, serviceReqSearchCriteria);
			if (serviceRequestIds.isEmpty())
				throw new CustomException(ErrorConstants.NO_DATA_KEY, ErrorConstants.NO_DATA_MSG);
			serviceReqSearchCriteria.setServiceRequestId(serviceRequestIds);
		}
		if(!StringUtils.isEmpty(serviceReqSearchCriteria.getGroup()) && CollectionUtils.isEmpty(serviceReqSearchCriteria.getServiceCodes())) {
			List<String> departmentCodes = new ArrayList<>(); departmentCodes.add(serviceReqSearchCriteria.getGroup());
			Object response = fetchServiceDefs(requestInfo, serviceReqSearchCriteria.getTenantId(), departmentCodes);
			if (null == response) {
				throw new CustomException(ErrorConstants.NO_DATA_KEY, ErrorConstants.NO_DATA_MSG);
			}
			try {
				List<String> serviceCodes = JsonPath.read(response, PGRConstants.JSONPATH_SERVICE_CODES);
				if(serviceCodes.isEmpty())
					throw new CustomException(ErrorConstants.NO_DATA_KEY, ErrorConstants.NO_DATA_MSG);
				serviceReqSearchCriteria.setServiceCodes(serviceCodes);
			} catch (Exception e) {
				throw new CustomException(ErrorConstants.NO_DATA_KEY, ErrorConstants.NO_DATA_MSG);
			}
		}
		serviceReqSearchCriteria.setActive(true);
	}

	/**
	 * This method fetches all the codes of departments that the employee belongs to.
	 * 
	 * @param serviceReqSearchCriteria
	 * @param requestInfo
	 * @return
	 */
	public List<String> getDepartmentCode(ServiceReqSearchCriteria serviceReqSearchCriteria, RequestInfo requestInfo) {
		StringBuilder uri = new StringBuilder();
		RequestInfoWrapper requestInfoWrapper = pGRUtils.prepareRequestForEmployeeSearch(uri, requestInfo,
				serviceReqSearchCriteria);
		Object response = null;
		log.debug("Employee: " + response);
		List<String> departmenCodes = null;
		try {
			response = serviceRequestRepository.fetchResult(uri, requestInfoWrapper);
			if (null == response) {
				throw new CustomException(ErrorConstants.UNAUTHORIZED_EMPLOYEE_TENANT_KEY,
						ErrorConstants.UNAUTHORIZED_EMPLOYEE_TENANT_MSG);
			}
			log.debug("Employee: " + response);
			departmenCodes = JsonPath.read(response, PGRConstants.EMPLOYEE_DEPTCODES_JSONPATH);
		} catch (Exception e) {
			log.error("Exception: " + e);
			throw new CustomException(ErrorConstants.UNAUTHORIZED_EMPLOYEE_TENANT_KEY,
					ErrorConstants.UNAUTHORIZED_EMPLOYEE_TENANT_MSG);
		}
		return departmenCodes;
	}	

	/**
	 * method to fetch service defs from mdms based on dept
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param department
	 * @return Object
	 * @author vishal
	 */
	public Object fetchServiceDefs(RequestInfo requestInfo, String tenantId, List<String> departments) {
		StringBuilder uri = new StringBuilder();
		MdmsCriteriaReq mdmsCriteriaReq = pGRUtils.prepareSearchRequestForServiceCodes(uri, tenantId, departments,
				requestInfo);
		Object response = null;
		try {
			response = serviceRequestRepository.fetchResult(uri, mdmsCriteriaReq);
		} catch (Exception e) {
			log.error("Exception while fetching serviceCodes: " + e);
		}
		return response;

	}

	/**
	 * Method to return service requests ids based on the assignedTo
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 * @return List<String>
	 * @author vishal
	 */
	public List<String> getServiceRequestIdsOnAssignedTo(RequestInfo requestInfo,
			ServiceReqSearchCriteria serviceReqSearchCriteria) {
		StringBuilder uri = new StringBuilder();
		List<String> serviceRequestIds = new ArrayList<>();
		SearcherRequest searcherRequest = pGRUtils.prepareSearchRequestForAssignedTo(uri, serviceReqSearchCriteria,
				requestInfo);
		try {
			Object response = serviceRequestRepository.fetchResult(uri, searcherRequest);
			log.debug("Searcher response: " + response);
			if (null == response)
				return serviceRequestIds;
			serviceRequestIds = JsonPath.read(response, PGRConstants.SRID_ASSIGNEDTO_JSONPATH);
		} catch (Exception e) {
			log.error("Exception while parsing SRid search on AssignedTo result: " + e);
			return serviceRequestIds;
		}
		log.debug("serviceRequestIds: " + serviceRequestIds);

		return serviceRequestIds;

	}

	/**
	 * This method formats the search result according to the contract.
	 * 
	 * @param response
	 * @param requestInfo
	 * @return
	 */
	public ServiceResponse prepareResult(Object response, RequestInfo requestInfo) {
		ObjectMapper mapper = pGRUtils.getObjectMapper();
		List<Service> services = new ArrayList<>();
		List<ActionHistory> actionHistory = new ArrayList<>();
		List<ServiceRequestDetails> result = new ArrayList<>();
		List<Object> list = JsonPath.read(response, "$.services");
		list.stream().forEach(entry -> result.add(mapper.convertValue(entry, ServiceRequestDetails.class)));
		result.stream().forEach(obj -> {
			if(null != obj) {
				ActionHistory actionHis = new ActionHistory();
				actionHis.setActions(obj.getActionhistory());
				actionHistory.add(actionHis);
				obj.setActionhistory(null);
				services.add(obj.getServices());
			}
		});
		replaceIdsWithUrls(actionHistory);

		return ServiceResponse.builder().responseInfo(factory.createResponseInfoFromRequestInfo(requestInfo, true))
				.services(services).actionHistory(actionHistory).build();
	}

	/**
	 * Fetches count of service requests and returns in the reqd format.
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 * @return Object
	 * @author vishal
	 */
	public Object getCount(RequestInfo requestInfo, ServiceReqSearchCriteria serviceReqSearchCriteria) {
		StringBuilder uri = new StringBuilder();
		SearcherRequest searcherRequest = null;
		try {
			enrichRequest(requestInfo, serviceReqSearchCriteria);
		} catch (CustomException e) {
			if (e.getMessage().equals(ErrorConstants.NO_DATA_MSG))
				return pGRUtils.getDefaultCountResponse(requestInfo);
			else
				throw e;
		}
		
		Object response = null;
		Double count = 0.0;
		
		List<String> codes = requestInfo.getUserInfo().getRoles().stream().map(Role::getCode).collect(Collectors.toList());
		
		if ((codes.contains(PGRConstants.ROLE_ESCALATION_OFFICER1) || codes.contains(PGRConstants.ROLE_ESCALATION_OFFICER2))
				&& CollectionUtils.isEmpty(serviceReqSearchCriteria.getServiceRequestId())) {
		/*if(!CollectionUtils.isEmpty(serviceReqSearchCriteria.getStatus()) 
				&& (serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL1_PENDING)
					|| serviceReqSearchCriteria.getStatus().contains(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING))){*/
			count = getComplaintCountForEscalationOfficer(requestInfo, serviceReqSearchCriteria, uri);
			
			//if any complaint is assigned to an escalated officer via autorouting then fetch that complaints also.
			try {
				List<String> status = new ArrayList<String>();
				status.add(WorkFlowConfigs.STATUS_ASSIGNED);
				status.add(WorkFlowConfigs.STATUS_REASSIGN_REQUESTED);
				serviceReqSearchCriteria.setStatus(status);
				serviceReqSearchCriteria.setCategory(null);
				uri = new StringBuilder();
				enrichRequest(requestInfo, serviceReqSearchCriteria);
				searcherRequest = pGRUtils.prepareCountRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
				Object assignedResponse = serviceRequestRepository.fetchResult(uri, searcherRequest);
				
				if(null != assignedResponse) {
					count = count+ (Double)JsonPath.read(assignedResponse, PGRConstants.PG_JSONPATH_COUNT);	
				}
			} catch (CustomException e) {
				if (e.getMessage().equals(ErrorConstants.NO_DATA_MSG))
					log.debug("No complaint is assigned to this escalated officer {}",requestInfo.getUserInfo().getUserName());
			}
		}else {
			searcherRequest = pGRUtils.prepareCountRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
			response = serviceRequestRepository.fetchResult(uri, searcherRequest);
			count = JsonPath.read(response, PGRConstants.PG_JSONPATH_COUNT);
		}
		
		/*if (null == response)
			return pGRUtils.getDefaultCountResponse(requestInfo);*/
		
		return new CountResponse(factory.createResponseInfoFromRequestInfo(requestInfo, true), count);
	}

	/**
	 * method to replace the fileStoreIds with the respective urls acquired from
	 * filestore service
	 * 
	 * @param historyList
	 */
	private void replaceIdsWithUrls(List<ActionHistory> historyList) {
		if (CollectionUtils.isEmpty(historyList))
			return;
		try {
			String tenantId = historyList.get(0).getActions().get(0).getTenantId();
			List<String> fileStoreIds = new ArrayList<>();
			historyList.stream().forEach(history -> {
			if(null != history) {
				List<ActionInfo> actions = history.getActions();
				if(!CollectionUtils.isEmpty(actions)) {
					actions.stream().forEach(action -> {
						if(null != action) {
							List<String> media = action.getMedia();
							if (!CollectionUtils.isEmpty(media))
								fileStoreIds.addAll(media);
						}
					});
				}
			}});
			Map<String, String> computeUriIdMap = new HashMap<>();
			try {
				computeUriIdMap = fileStoreRepo.getUrlMaps(tenantId.split("\\.")[0], fileStoreIds);
			} catch (Exception e) {
				log.error(" exception while connecting to filestore : " + e);
			}
			final Map<String, String> urlIdMap = computeUriIdMap;
			if(!CollectionUtils.isEmpty(urlIdMap.keySet())) {
				historyList.stream().forEach(history -> {
					if(null != history) {
						List<ActionInfo> actions = history.getActions();
						if(!CollectionUtils.isEmpty(actions)) {
							actions.stream().forEach(action -> {
								if(null != action) {
									List<String> media = action.getMedia();
									if(!CollectionUtils.isEmpty(media)) {
										List<String> mediaList = new ArrayList<>();
										media.forEach(obj -> {
											obj = StringUtils.isEmpty(urlIdMap.get(obj)) ? obj : urlIdMap.get(obj);
											mediaList.add(obj);
										});	
										action.setMedia(mediaList);
									}
								}
							});
							
						}
					}
				});
			}
		} catch (Exception e) {
			log.error("Exception while replacing s3 links: ", e);
		}
	}
	
	/**
	 * This method populates timeline information of the service request with user details of the actors on the complaint
	 * This method populates the locality field of the addressDetail object with name (english) of the mohalla.
	 * 
	 * @param requestInfo
	 * @param response
	 * @return
	 */
	public ServiceResponse enrichResult(RequestInfo requestInfo, ServiceResponse response) {
		List<Long> userIds = response.getServices().stream().map(a -> {
					try {return Long.parseLong(a.getAccountId());}catch(Exception e) {return null;} }).collect(Collectors.toList());
		List<Address> addresses = new ArrayList<>();
		response.getServices().forEach(service -> {
			if(null != service) {
				if(null != service.getAddressDetail()) {
					addresses.add(service.getAddressDetail());
				}
			}
		});
		Map<String, String> mapOfMohallaCodesAndNames = new HashMap<>();
		/**
		 * Populating locality field.
		 */
		if(!CollectionUtils.isEmpty(addresses)) {
			Map<String, List<String>> mapOfTenantIdAndMohallaCodes = new HashMap<>();
			/**
			 * When CSR searches, complaints in the result belong to multiple tenants. Inorder to populate mohalla value, we need tenant of every complaint.
			 */
			for(Address address: addresses) {
				if(null != address) {
					if(CollectionUtils.isEmpty(mapOfTenantIdAndMohallaCodes.get(address.getTenantId()))){
						List<String> mohCodes = new ArrayList();
						mohCodes.add(address.getMohalla());
						mapOfTenantIdAndMohallaCodes.put(address.getTenantId(), mohCodes);
					}else {
						List<String> codes = mapOfTenantIdAndMohallaCodes.get(address.getTenantId());
						codes.add(address.getMohalla());
						mapOfTenantIdAndMohallaCodes.put(address.getTenantId(), codes);
					}
				}
			}
			Set<String> tenantIds = addresses.stream().map(obj -> {
				if(null != obj)  return obj.getTenantId(); else return null;
			}).collect(Collectors.toSet());
			for(String tenantId: tenantIds) {
				if(!StringUtils.isEmpty(tenantId)) {
					Map<String, String> tenantWiseMap = new HashMap<>();
					if(!CollectionUtils.isEmpty(mapOfTenantIdAndMohallaCodes.get(tenantId))) {
						tenantWiseMap = getMohallNames(requestInfo, tenantId, mapOfTenantIdAndMohallaCodes.get(tenantId), 
								PGRConstants.LOCATION__BOUNDARY_HIERARCHYTYPE_ADMIN, PGRConstants.LOCATION__BOUNDARY_BOUNDARYTYPE_LOCALITY);
					}
					mapOfMohallaCodesAndNames.putAll(tenantWiseMap);
				}
			}
		}
		if(!CollectionUtils.isEmpty(mapOfMohallaCodesAndNames.keySet())) {
			for(Service service: response.getServices()) {
				if(null != service) {
					if(null != service.getAddressDetail()) {
						if(!StringUtils.isEmpty(mapOfMohallaCodesAndNames.get(service.getAddressDetail().getMohalla()))) {
							service.getAddressDetail().setLocality(mapOfMohallaCodesAndNames.get(service.getAddressDetail().getMohalla()));
						}
					}
				}
			}
			
		}
		/**
		 * User details enrichment
		 */
		String tenantId = response.getServices().get(0).getTenantId().split("[.]")[0]; //citizen is state-level no point in sending ulb level tenant.
		UserResponse userResponse = getUsers(requestInfo, tenantId, userIds);
		if(null != userResponse) {
			Map<Long, Citizen> userResponseMap = userResponse.getUser().stream()
					.collect(Collectors.toMap(Citizen :: getId, Function.identity()));
			for(Service service: response.getServices()) {
				if(null != service) {
					Long id = null;
					try {
						id = Long.parseLong(service.getAccountId());
					}catch(Exception e) {}
					service.setCitizen(userResponseMap.get(id));
				}
			}
		}
		return response;
	}
	
	/**
	 * Fetches Users to be populated in the response
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param userIds
	 * @return
	 */
	public UserResponse getUsers(RequestInfo requestInfo, String tenantId, List<Long> userIds) {
		ObjectMapper mapper = pGRUtils.getObjectMapper();
		UserSearchRequest searchRequest = UserSearchRequest.builder().id(userIds).tenantId(tenantId)
				.userType(PGRConstants.ROLE_CITIZEN).requestInfo(requestInfo).build();
		StringBuilder url = new StringBuilder();
		url.append(userBasePath).append(userSearchEndPoint);
		try {
			UserResponse res = mapper.convertValue(serviceRequestRepository.fetchResult(url, searchRequest), UserResponse.class);
			if(CollectionUtils.isEmpty(res.getUser())) {
				return null;
			}else {
				return res;
			}
		}catch(Exception e) {
			return null;
		}
		
	}
	
	/**
	 * Method fetches a map of code vs name of the boundary types as per the request.
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param mohallaCodes
	 * @param hierarchyType
	 * @param boundaryType
	 * @return
	 */
	public Map<String, String> getMohallNames(RequestInfo requestInfo, String tenantId, List<String> mohallaCodes, String hierarchyType, String boundaryType){
		StringBuilder uri = new StringBuilder();
		RequestInfoWrapper request = pGRUtils.prepareRequestForLocation(uri, requestInfo, boundaryType, tenantId, hierarchyType, mohallaCodes);
		Map<String, String> map = new HashMap<>();
		try {
			Object response = serviceRequestRepository.fetchResult(uri, request);
			if(null != response) {
				List<String> names = JsonPath.read(response, PGRConstants.LOCATION__BOUNDARY_NAMES_JSONPATH);
				List<String> codes = JsonPath.read(response, PGRConstants.LOCATION__BOUNDARY_CODES_JSONPATH);
				for(int i = 0; i < names.size(); i++) {
					map.put(codes.get(i), names.get(i));
				}
			}
		}catch(Exception e) {
			log.error("Couldn't fetch mohalla names: "+e);
		}
		log.info("map: "+map);
		return map;
	}
	
	/**
	 * method to fetch AutoroutingEscalationMap from mdms based on category,sector
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param category
	 * @param sector
	 * @return Object
	 * @author Tonmoy
	 */
	public Object fetchAutoroutingEscalationMap(RequestInfo requestInfo, String tenantId, String category, String sector) {
		StringBuilder uri = new StringBuilder();
		MdmsCriteriaReq mdmsCriteriaReq = pGRUtils.prepareAutoroutingEscalationMapSearchMdmsRequestByCategoryAndSector(uri, tenantId, category, sector, requestInfo);
		Object response = null;
		try {
			response = serviceRequestRepository.fetchResult(uri, mdmsCriteriaReq);
		} catch (Exception e) {
			log.error("Exception while fetching AutoroutingEscalationMap: " + e);
		}
		return response;

	}
	
	/**
	 * method to fetch AutoRoutingEmployee from mdms based on category,sector
	 * 
	 * @param requestInfo
	 * @param tenantId
	  * @param category
	 * @param sector
	 * @return String
	 * @author Tonmoy
	 */
	public String fetchAutoroutingEmployee(RequestInfo requestInfo, String tenantId, Service servReq) {
		String employeeCode = null;
		try {
			String category = null;
			String sector = null;
			List<Object> serivceDefs = getServiceType(servReq, requestInfo);
			if(!CollectionUtils.isEmpty(serivceDefs))
				category = String.valueOf(serivceDefs.get(0));
			
			Address address = servReq.getAddressDetail();
			if(null != address){
				sector = address.getMohalla();
			}
			
			Object result = fetchAutoroutingEscalationMap(requestInfo, tenantId, category, sector);
			if(null != result) {
				List objList = JsonPath.read(result, PGRConstants.JSONPATH_AUTOROUTING_CODES);
				if(CollectionUtils.isEmpty(objList)) {
					return null;
				}
				
				List sectorArr = (List)objList.get(0);
				for (int i = 0; i < sectorArr.size(); i++) {
					List<String> sectors = JsonPath.read(sectorArr.get(i), PGRConstants.AUTOROUTING_SECTOR_JSONPATH);
					if(!CollectionUtils.isEmpty(sectors)) {
						if(sectors.contains(sector)) {
							employeeCode = JsonPath.read(sectorArr.get(i), PGRConstants.AUTOROUTING_EMPLOYEE_JSONPATH);
							break;
						}
					}
				}
			}
		} catch (Exception e) {
			log.error("Exception while fetching fetchAutoroutingEmployee: " + e);
		}
		
		return employeeCode;

	}
	
	/**
	 * method to fetch Escalating officers(1st level & 2nd level) from mdms based on category,sector
	 * 
	 * @param requestInfo
	 * @param tenantId
	  * @param category
	 * @param sector
	 * @return Map<String,List<String>>
	 * @author Tonmoy
	 */
	public Map<String,List<String>> fetchEscalatingOfficers(RequestInfo requestInfo, String tenantId, Service servReq) {
		Map<String,List<String>> escalatingOfficerMap = new HashMap<String, List<String>>();
		List<String> escalationOfficer1 = null;
		List<String> escalationOfficer2 = null;
		try {
			String category = null;
			List<Object> serivceDefs = getServiceType(servReq, requestInfo);
			if(!CollectionUtils.isEmpty(serivceDefs))
				category = String.valueOf(serivceDefs.get(0));
			
			Object result = fetchAutoroutingEscalationMap(requestInfo, tenantId, category, null);
			if(null != result) {
				escalationOfficer1 = JsonPath.read(result, PGRConstants.JSONPATH_ESCALATING_OFFICER1_CODES);
				escalationOfficer2 = JsonPath.read(result, PGRConstants.JSONPATH_ESCALATING_OFFICER2_CODES);
			}
		} catch (Exception e) {
			log.error("Exception while fetching fetchEscalatingOfficers: " + e);
		}
		escalatingOfficerMap.put("escalationOfficer1", escalationOfficer1);
		escalatingOfficerMap.put("escalationOfficer2", escalationOfficer2);
		
		return escalatingOfficerMap;

	}
	
	/**
	 * Fetches the category, department, Service type, sla hours for the respective service type
	 * 
	 * @param serviceReq
	 * @param requestInfo
	 * @param locale
	 * @return
	 */
	public List<Object> getServiceType(Service serviceReq, RequestInfo requestInfo) {
		StringBuilder uri = new StringBuilder();
		List<Object> listOfValues = new ArrayList<>();
		String serviceType = null;
		String categoryCode = null;
		String departmentCode = null;
		Integer sla = 0;
		List<String> categories = null;
		List<String> departments = null;
		List<String> serviceTypes = null;
		List<Integer> slaHours = null;
		String tenantId = serviceReq.getTenantId().split("[.]")[0]; // localization values are for now state-level.
		try {
			MdmsCriteriaReq mdmsCriteriaReq = pGRUtils.prepareSearchRequestForServiceType(uri, serviceReq.getTenantId(),
					serviceReq.getServiceCode(), requestInfo);
			log.info("Criteria to fetch servicedefs from MDMS:"+mdmsCriteriaReq.toString());
			
			Object result = serviceRequestRepository.fetchResult(uri, mdmsCriteriaReq);
			categories = JsonPath.read(result, PGRConstants.JSONPATH_CATEGORY_CODES);
			departments = JsonPath.read(result, PGRConstants.JSONPATH_DEPARTMENT_CODES);
			serviceTypes = JsonPath.read(result, PGRConstants.JSONPATH_SERVICE_CODES);
			slaHours = JsonPath.read(result, PGRConstants.JSONPATH_SLA);
			
			String locale = null;
	        try {
	            locale = requestInfo.getMsgId().split("[|]")[1]; // Conventionally locale is sent in the first index of msgid split by |
	            if (StringUtils.isEmpty(locale))
	                locale = fallbackLocale;
	        } catch (Exception e) {
	            locale = fallbackLocale;
	        }
	        
	        log.info("Get data from servicedefs- categories:"+categories+" ,departments: "+departments+" ,serviceTypes:"+serviceTypes+" ,slaHours:"+slaHours);
			
			if (null == localizedMessageMap.get(locale + "|" + tenantId)) // static map that saves code-message pair against locale | tenantId.
				getLocalisedMessages(requestInfo, tenantId, locale, PGRConstants.LOCALIZATION_MODULE_NAME);
			
			if(!CollectionUtils.isEmpty(serviceTypes))
				serviceType = localizedMessageMap.get(locale + "|" + tenantId).get(PGRConstants.LOCALIZATION_COMP_CATEGORY_PREFIX + serviceTypes.get(0).toUpperCase()); //result set is always of size one.
			
			log.info("Sub Category:"+serviceType);
			if(StringUtils.isEmpty(serviceType))
				serviceType = PGRUtils.splitCamelCase(serviceTypes.get(0));
			if(!CollectionUtils.isEmpty(categories))
				categoryCode = categories.get(0);
			if(!CollectionUtils.isEmpty(departments))
				departmentCode = departments.get(0);
			if(!CollectionUtils.isEmpty(slaHours))
				sla = Integer.valueOf(slaHours.get(0)) / 24; //converting hours to days.
		} catch (Exception e) {
			log.error("Error in fetching servicedefs"+e);
		}
		
		listOfValues.add(categoryCode); 
		listOfValues.add(departmentCode); 
		listOfValues.add(serviceType); 
		listOfValues.add(sla);
		return listOfValues;
	}

	/**
	 * Populates the localized msg cache
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param locale
	 * @param module
	 */
	public void getLocalisedMessages(RequestInfo requestInfo, String tenantId, String locale, String module) {
		Map<String, String> mapOfCodesAndMessages = new HashMap<>();
		StringBuilder uri = new StringBuilder();
		RequestInfoWrapper requestInfoWrapper = pGRUtils.prepareRequestForLocalization(uri, requestInfo, locale,
				tenantId, module);
		List<String> codes = null;
		List<String> messages = null;
		Object result = null;
		try {
			result = serviceRequestRepository.fetchResult(uri, requestInfoWrapper);
			codes = JsonPath.read(result, PGRConstants.LOCALIZATION_CODES_JSONPATH);
			messages = JsonPath.read(result, PGRConstants.LOCALIZATION_MSGS_JSONPATH);
		} catch (Exception e) {
			log.error("Exception while fetching from localization: " + e);
		}
		if (null != result) {
			for (int i = 0; i < codes.size(); i++) {
				mapOfCodesAndMessages.put(codes.get(i), messages.get(i));
			}
			localizedMessageMap.put(locale + "|" + tenantId, mapOfCodesAndMessages);
		}
	}
	
	/**
	 * Fetches Users to be populated in the response
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param userIds
	 * @return
	 */
	public UserResponse getUser(RequestInfo requestInfo, String tenantId, String employeeCode) {
		ObjectMapper mapper = pGRUtils.getObjectMapper();
		UserSearchRequest searchRequest = UserSearchRequest.builder().tenantId(tenantId).userName(employeeCode)
				.userType(PGRConstants.ROLE_EMPLOYEE).requestInfo(requestInfo).build();
		StringBuilder url = new StringBuilder();
		url.append(userBasePath).append(userSearchEndPoint);
		try {
			UserResponse res = mapper.convertValue(serviceRequestRepository.fetchResult(url, searchRequest), UserResponse.class);
			if(CollectionUtils.isEmpty(res.getUser())) {
				return null;
			}else {
				return res;
			}
		}catch(Exception e) {
			return null;
		}
		
	}
	
	/**
	 * method to fetch AutoRoutingEmployee from mdms based on category,sector
	 * 
	 * @param requestInfo
	 * @param tenantId
	  * @param category
	 * @param sector
	 * @return String
	 * @author Tonmoy
	 */
	public Map<String,List<String>> fetchCategoriesForEscalationOfficer(RequestInfo requestInfo, String tenantId) {
		Map<String,List<String>> categoryMap = new HashMap<String, List<String>>();
		List<String> categoryList1=null;
		List<String> categoryList2=null;
		try {
			//Get category list for escalationOfficer1
			Object result = fetchCategoriesFromAutoroutingEscalationMap(requestInfo, tenantId);
			
			if(null != result) {
				List objList = JsonPath.read(result, PGRConstants.JSONPATH_AUTOROUTING_MAP_CODES);
				if(CollectionUtils.isEmpty(objList)) {
					return null;
				}
				
				//Category list of escalation officer1
				for (int i = 0; i < objList.size(); i++) {
					List<String> escalationOfficer1List = JsonPath.read(objList.get(i), PGRConstants.AUTOROUTING_ESCALATING_OFFICER1_JSONPATH);
					if(!CollectionUtils.isEmpty(escalationOfficer1List)) {
						if(escalationOfficer1List.contains(requestInfo.getUserInfo().getUserName())) {
							if(CollectionUtils.isEmpty(categoryList1))
								categoryList1=new ArrayList<String>();
							categoryList1.add(JsonPath.read(objList.get(i), PGRConstants.AUTOROUTING_CATEGORY_JSONPATH));
						}
					}
				}
				
				//Category list of escalation officer2
				for (int i = 0; i < objList.size(); i++) {
					List<String> escalationOfficer2List = JsonPath.read(objList.get(i), PGRConstants.AUTOROUTING_ESCALATING_OFFICER2_JSONPATH);
					if(!CollectionUtils.isEmpty(escalationOfficer2List)) {
						if(escalationOfficer2List.contains(requestInfo.getUserInfo().getUserName())) {
							if(CollectionUtils.isEmpty(categoryList2))
								categoryList2=new ArrayList<String>();
							categoryList2.add(JsonPath.read(objList.get(i), PGRConstants.AUTOROUTING_CATEGORY_JSONPATH));
						}
					}
				}
			}
			
		} catch (Exception e) {
			log.error("Exception while fetching fetchCategoriesForEscalationOfficer: " + e);
		}
		
		categoryMap.put(PGRConstants.MDMS_AUTOROUTING_ESCALATION_OFFICER1_NAME, categoryList1);
		categoryMap.put(PGRConstants.MDMS_AUTOROUTING_ESCALATION_OFFICER2_NAME, categoryList2);
		
		return categoryMap;

	}
	
	/**
	 * method to fetch catrgory list from mdms based on employeeode,escalation officer
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param employeCode
	 * @param escalationOfficer
	 * @return Object
	 * @author Tonmoy
	 */
	public Object fetchCategoriesFromAutoroutingEscalationMap(RequestInfo requestInfo, String tenantId) {
		StringBuilder uri = new StringBuilder();
		MdmsCriteriaReq mdmsCriteriaReq = pGRUtils.prepareCategoryMdmsRequestByEscalationOfficer(uri, tenantId, requestInfo);
		Object response = null;
		try {
			response = serviceRequestRepository.fetchResult(uri, mdmsCriteriaReq);
		} catch (Exception e) {
			log.error("Exception while fetching fetchCategoriesFromAutoroutingEscalationMap: " + e);
		}
		return response;

	}
	
	/**
	 * method to search complaint list for escalation officer
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 * @param uri
	 * @param response
	 * @return Object
	 * @author Tonmoy
	 */
	private Object getComplaintListForEscalationOfficer(RequestInfo requestInfo,
			ServiceReqSearchCriteria serviceReqSearchCriteria, StringBuilder uri, Object response) {
		
		//If the user is escalation officer then 1st get the pending complaint of 1st level & get the pending complaint of 2nd level 
		// & merge these two results in the response. Because same user can be the escalation officer at 1st 
		//level in one department & 2nd level officer in other department  
		try {
			SearcherRequest searcherRequest;
			Object firstLevelResponse = null;
			Object secondLevelResponse = null;
			List firstLevelServiceList = null;
			List firstLevelActionHistoryList = null;
			List secondLevelServiceList = null;
			List secondLevelActionHistoryList = null;
			
			Map<String,List<String>> categoryList = fetchCategoriesForEscalationOfficer(requestInfo, serviceReqSearchCriteria.getTenantId());		
			List<String> categoryListForEscalatingOfficer1 = categoryList.get(PGRConstants.MDMS_AUTOROUTING_ESCALATION_OFFICER1_NAME);
			List<String> categoryListForEscalatingOfficer2 = categoryList.get(PGRConstants.MDMS_AUTOROUTING_ESCALATION_OFFICER2_NAME);
			
			
			if(!CollectionUtils.isEmpty(categoryListForEscalatingOfficer1)) {
				serviceReqSearchCriteria.setCategory(categoryListForEscalatingOfficer1);
				List<String> status = new ArrayList<String>();
				status.add(WorkFlowConfigs.STATUS_ESCALATED_LEVEL1_PENDING);
				serviceReqSearchCriteria.setStatus(status);
				searcherRequest = pGRUtils.prepareSearchRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
				firstLevelResponse = serviceRequestRepository.fetchResult(uri, searcherRequest);
				log.debug(PGRConstants.SEARCHER_RESPONSE_TEXT + firstLevelResponse);
				if(null != firstLevelResponse) {
					firstLevelServiceList = JsonPath.read(firstLevelResponse, PGRConstants.COMPLAINT_JSONPATH);
					firstLevelActionHistoryList = JsonPath.read(firstLevelResponse, PGRConstants.COMPLAINT_ACTION_HISTORY_JSONPATH);
				}
			}
			if(!CollectionUtils.isEmpty(categoryListForEscalatingOfficer2)) {
				serviceReqSearchCriteria.setCategory(categoryListForEscalatingOfficer2);
				List<String> status = new ArrayList<String>();
				status.add(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING);
				serviceReqSearchCriteria.setStatus(status);
				uri = new StringBuilder();
				searcherRequest = pGRUtils.prepareSearchRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
				secondLevelResponse = serviceRequestRepository.fetchResult(uri, searcherRequest);
				log.debug(PGRConstants.SEARCHER_RESPONSE_TEXT + secondLevelResponse);
				if(null != secondLevelResponse) {
					secondLevelServiceList = JsonPath.read(secondLevelResponse, PGRConstants.COMPLAINT_JSONPATH);
					secondLevelActionHistoryList = JsonPath.read(secondLevelResponse, PGRConstants.COMPLAINT_ACTION_HISTORY_JSONPATH);
				}
			}
			if(CollectionUtils.isEmpty(firstLevelServiceList)) {
				response = secondLevelResponse;
			}else if(CollectionUtils.isEmpty(secondLevelServiceList)) {
				response = firstLevelResponse;
			}else {
					
				LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>)firstLevelResponse;
				List obj = (List)map.get("services");
				LinkedHashMap<String, Object> map1 = (LinkedHashMap<String, Object>)secondLevelResponse;
				List obj1 = (List)map1.get("services");
				
				List<Object> finalObj = new ArrayList<Object>();
				finalObj.addAll(obj);
				finalObj.addAll(obj1);
				
				map1.put("services", finalObj);
				
				response = secondLevelResponse;
				
			}
		}catch(Exception e) {
			log.error("Error in generating final response for escalation officer "+e);
		}
		return response;
	}
	
	/**
	 * method to fetch PGR Department from mdms based on department code
	 * 
	 * @param requestInfo
	 * @param tenantId
	 * @param departmentCode
	 * @return Object
	 * @author Tonmoy
	 */
	public Object fetchPgrDepartment(RequestInfo requestInfo, String tenantId, String departmentCode) {
		StringBuilder uri = new StringBuilder();
		MdmsCriteriaReq mdmsCriteriaReq = pGRUtils.prepareSearchRequestForPgrDepartment(uri, tenantId, departmentCode, requestInfo);
		Object response = null;
		try {
			response = serviceRequestRepository.fetchResult(uri, mdmsCriteriaReq);
		} catch (Exception e) {
			log.error("Exception while fetching pgrdepartment: " + e);
		}
		return response;

	}
	
	/**
	 * Method to return service requests along with details acc to V5 design
	 * received from the repo to the controller in the reqd format
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 * @return ServiceReqResponse
	 * @author Tonmoy
	 */
	public Object getEscalationServiceRequestDetails(RequestInfo requestInfo, ServiceReqSearchCriteria serviceReqSearchCriteria) {
		StringBuilder uri = new StringBuilder();
		SearcherRequest searcherRequest = null;
		try {
			enrichEscalationSearchRequest(requestInfo, serviceReqSearchCriteria);
		} catch (CustomException e) {
			if (e.getMessage().equals(ErrorConstants.NO_DATA_MSG))
				return pGRUtils.getDefaultServiceResponse(requestInfo);
			else
				throw e;
		}
		
		searcherRequest = pGRUtils.prepareSearchRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
		Object response = serviceRequestRepository.fetchResult(uri, searcherRequest);
		log.debug(PGRConstants.SEARCHER_RESPONSE_TEXT + response);
		
		if (null == response)
			return pGRUtils.getDefaultServiceResponse(requestInfo);
		ServiceResponse serviceResponse = prepareResult(response, requestInfo);
		
		
		if(CollectionUtils.isEmpty(serviceResponse.getServices())) {
			log.info("No record found for auto escalation.");
			return serviceResponse;
		}else {
			serviceResponse = enrichResult(requestInfo, serviceResponse);
			if(!CollectionUtils.isEmpty(serviceResponse.getServices())) {
				int totalRecord = serviceResponse.getServices().size();
				log.info("Total compliants record found for auto escalation is "+totalRecord);
				int success =0;
				for(int i=0; i<serviceResponse.getServices().size(); i++) {
					Service service = serviceResponse.getServices().get(i);
					ActionHistory actionHistory = serviceResponse.getActionHistory().get(i);
					
					if(pGRUtils.checkAutoEscalatedWithoutResolved(actionHistory)) {
						log.info("complaint {} is already auto escalated.",service.getServiceRequestId());
						continue;
					}
					
					log.info("Escalation started for complaint "+service.getServiceRequestId());
					
					List<ActionInfo> actionInfo = new ArrayList<ActionInfo>();
					actionInfo.add(ActionInfo.builder().action(WorkFlowConfigs.ACTION_REOPEN).build());
					List<Service> services = new ArrayList<Service>();
					services.add(service);
					ServiceRequest request = ServiceRequest.builder().requestInfo(requestInfo).actionInfo(actionInfo).services(services).build();
					
					try {
						update(request);
						success++;
						log.info("Escalation success for complaint "+service.getServiceRequestId());
					}catch(Exception e) {
						log.error("Error in auto escalation for compliant {}",service.getServiceRequestId(),e);
					}
				}
				log.info("Total escalation success is {} out of {}",success,totalRecord);
			}
		}
		
		return serviceResponse;
	}
	
	/**
	 * Method to enrich the request for search based on roles.
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 */
	public void enrichEscalationSearchRequest(RequestInfo requestInfo, ServiceReqSearchCriteria serviceReqSearchCriteria) {
		log.info("Enriching request for escalation search");
		
		serviceReqSearchCriteria.setTenantId(PGRConstants.TENANT_ID.split("[.]")[0]);
		serviceReqSearchCriteria.setActive(true);
		serviceReqSearchCriteria.setSlaEndTime(new Date().getTime());
		
		List<String> status = new ArrayList<String>();
		status.add(WorkFlowConfigs.STATUS_OPENED);
		status.add(WorkFlowConfigs.STATUS_ASSIGNED);
		status.add(WorkFlowConfigs.STATUS_REASSIGN_REQUESTED);
		status.add(WorkFlowConfigs.STATUS_ESCALATED_LEVEL1_PENDING);
		serviceReqSearchCriteria.setStatus(status);
	}
	
	/**
	 * method to get count of complaint list for escalation officer
	 * 
	 * @param requestInfo
	 * @param serviceReqSearchCriteria
	 * @param uri
	 * @param response
	 * @return Double
	 * @author Tonmoy
	 */
	private Double getComplaintCountForEscalationOfficer(RequestInfo requestInfo,
			ServiceReqSearchCriteria serviceReqSearchCriteria, StringBuilder uri) {
		
		//If the user is escalation officer then 1st get the pending complaint count of 1st level & get the pending complaint count of 2nd level 
		// & merge these two results in the response. Because same user can be the escalation officer at 1st 
		//level in one department & 2nd level officer in other department  
		
		Double count =0.0;
		try {
			SearcherRequest searcherRequest;
			Object response = null;
			
			Map<String,List<String>> categoryList = fetchCategoriesForEscalationOfficer(requestInfo, serviceReqSearchCriteria.getTenantId());		
			List<String> categoryListForEscalatingOfficer1 = categoryList.get(PGRConstants.MDMS_AUTOROUTING_ESCALATION_OFFICER1_NAME);
			List<String> categoryListForEscalatingOfficer2 = categoryList.get(PGRConstants.MDMS_AUTOROUTING_ESCALATION_OFFICER2_NAME);
			
			searcherRequest = pGRUtils.prepareCountRequestWithDetails(uri, serviceReqSearchCriteria, requestInfo);
			
			if(!CollectionUtils.isEmpty(categoryListForEscalatingOfficer1)) {
				serviceReqSearchCriteria.setCategory(categoryListForEscalatingOfficer1);
				List<String> status = new ArrayList<String>();
				status.add(WorkFlowConfigs.STATUS_ESCALATED_LEVEL1_PENDING);
				serviceReqSearchCriteria.setStatus(status);
				
				response = serviceRequestRepository.fetchResult(uri, searcherRequest);
				count = JsonPath.read(response, PGRConstants.PG_JSONPATH_COUNT);
			}
			if(!CollectionUtils.isEmpty(categoryListForEscalatingOfficer2)) {
				serviceReqSearchCriteria.setCategory(categoryListForEscalatingOfficer2);
				List<String> status = new ArrayList<String>();
				status.add(WorkFlowConfigs.STATUS_ESCALATED_LEVEL2_PENDING);
				serviceReqSearchCriteria.setStatus(status);
				
				response = serviceRequestRepository.fetchResult(uri, searcherRequest);
				count = count + (Double)JsonPath.read(response, PGRConstants.PG_JSONPATH_COUNT);
			}
		}catch(Exception e) {
			log.error("Error in generating final response for escalation officer "+e);
		}
		return count;
	}
}
