package org.egov.echallan.service;

import java.util.List;
import java.util.UUID;

import org.egov.common.contract.response.ResponseInfo;
import org.egov.echallan.config.EcConstants;
import org.egov.echallan.repository.VendorRegistrationRepository;
import org.egov.echallan.service.validator.CustomBeanValidator;
import org.egov.echallan.web.models.EcSearchCriteria;
import org.egov.echallan.web.models.RequestInfoWrapper;
import org.egov.echallan.web.models.ResponseInfoWrapper;
import org.egov.echallan.web.models.VendorRegistration;
import org.egov.echallan.workflow.WorkflowIntegrator;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class VendorRegistrationService {

	private final ObjectMapper objectMapper;
	private WorkflowIntegrator wfIntegrator;
	private CustomBeanValidator validate;
	private VendorRegistrationRepository repository;
	private DeviceSourceService deviceSource;

	@Autowired
	public VendorRegistrationService(WorkflowIntegrator wfIntegrator, ObjectMapper objectMapper,CustomBeanValidator validate,
			VendorRegistrationRepository repository,DeviceSourceService deviceSource) {
		this.objectMapper = objectMapper;
		this.wfIntegrator = wfIntegrator;
		this.repository = repository;
		this.validate=validate;
		this.deviceSource=deviceSource;
	}

	/**
	*This method will fetch list of vendors
	*
	* @param RequestInfoWrapper SearchCriteria
	* @return ResponseInfoWrapper containing list of vendors
	* @throws CustomException VENDORREGISTRATION_GET_EXCEPTION
	*/
	public ResponseEntity<ResponseInfoWrapper> getVendor(RequestInfoWrapper requestInfoWrapper) {
		log.info("Vendor Service - Get Vendor");
		try {
			EcSearchCriteria searchCriteria = objectMapper.convertValue(requestInfoWrapper.getRequestBody(),
					EcSearchCriteria.class);

			List<VendorRegistration> vendor = repository.getVendor(searchCriteria);
			return new ResponseEntity<>(ResponseInfoWrapper.builder()
					.responseInfo(ResponseInfo.builder().status(EcConstants.STATUS_SUCCESS).build()).responseBody(vendor).build(),
					HttpStatus.OK);
		} catch (Exception e) {
			log.error("Vendor Service - Get Vendor Exception"+e.getMessage());
			throw new CustomException("VENDORREGISTRATION_GET_EXCEPTION", e.getMessage());
		}
	}

	/**
	* This method will add vendors into vendor master
	*
	* @param RequestInfoWrapper containing list of vendors which needs to be added
	* @param requestHeader for saving device source information
	* @return HTTP status on success
	* @throws CustomException VENDORREGISTRATION_ADD_EXCEPTION
	*/
	public ResponseEntity<ResponseInfoWrapper> createVendor(RequestInfoWrapper requestInfoWrapper, String requestHeader) {
		log.info("Vendor Service - Create Vendor");
		try {
			VendorRegistration vendorRegistration = objectMapper.convertValue(requestInfoWrapper.getRequestBody(),
					VendorRegistration.class);
			

			String sourceUuid = deviceSource.saveDeviceDetails(requestHeader, "addVendorEvent",
					vendorRegistration.getTenantId(), requestInfoWrapper.getAuditDetails());

			vendorRegistration.getVendorRegistrationList().stream().forEach((c) -> {
				c.setCreatedBy(requestInfoWrapper.getAuditDetails().getCreatedBy());
				c.setCreatedTime(requestInfoWrapper.getAuditDetails().getCreatedTime());
				c.setLastModifiedBy(requestInfoWrapper.getAuditDetails().getLastModifiedBy());
				c.setLastModifiedTime(requestInfoWrapper.getAuditDetails().getLastModifiedTime());

				c.setVendorUuid(UUID.randomUUID().toString());
				c.setSourceUuid(sourceUuid);
			});

			requestInfoWrapper.setRequestBody(vendorRegistration);
			
			validate.validateFields(vendorRegistration.getVendorRegistrationList());
			
			
			repository.saveVendor(vendorRegistration);

			return new ResponseEntity<>(
					ResponseInfoWrapper.builder().responseInfo(ResponseInfo.builder().status(EcConstants.STATUS_SUCCESS).build())
							.responseBody(vendorRegistration).build(),
					HttpStatus.OK);
		} catch (Exception e) {
			log.error("Vendor Service - Add Vendor Exception"+e.getMessage());
			throw new CustomException("VENDORREGISTRATION_ADD_EXCEPTION", e.getMessage());
		}
	}

	/**
	* This method will add vendors into vendor master
	*
	* @param RequestInfoWrapper containing list of vendors which needs to be updated
	* @return HTTP status on success
	* @throws CustomException VENDORREGISTRATION_UPDATE_EXCEPTION
	*/
	public ResponseEntity<ResponseInfoWrapper> updateVendor(RequestInfoWrapper requestInfoWrapper) {
		log.info("Vendor Service - Update Vendor");
		try {
			VendorRegistration vendorRegistration = objectMapper.convertValue(requestInfoWrapper.getRequestBody(),
					VendorRegistration.class);
			
			vendorRegistration.getVendorRegistrationList().stream().forEach((c) -> {
				c.setCreatedBy(requestInfoWrapper.getAuditDetails().getCreatedBy());
				c.setCreatedTime(requestInfoWrapper.getAuditDetails().getCreatedTime());
				c.setLastModifiedBy(requestInfoWrapper.getAuditDetails().getLastModifiedBy());
				c.setLastModifiedTime(requestInfoWrapper.getAuditDetails().getLastModifiedTime());
			});

			repository.updateVendor(vendorRegistration);

			return new ResponseEntity<>(
					ResponseInfoWrapper.builder().responseInfo(ResponseInfo.builder().status(EcConstants.STATUS_SUCCESS).build())
							.responseBody(vendorRegistration).build(),
					HttpStatus.OK);
		} catch (Exception e) {
			log.error("Vendor Service - Update Vendor Exception"+e.getMessage());
			throw new CustomException("VENDORREGISTRATION_UPDATE_EXCEPTION", e.getMessage());
		}
	}

}