package com.qflow.backend.service;

import com.qflow.backend.dto.ServiceDTO;
import com.qflow.backend.model.Service;
import com.qflow.backend.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;
import java.util.Optional;

@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
@CacheConfig(cacheNames = {"services", "servicesByPlace"})
public class ServiceService {

    private final ServiceRepository serviceRepository;
    private final PlaceService placeService;
    private final AuditLogService auditLogService;

    @CacheEvict(value = {"places", "placesByAdmin", "services", "servicesByPlace", "queues", "queuesByPlace"}, allEntries = true)
    public Service createService(ServiceDTO serviceDTO) {
        // Admin flow (unchanged): creator details are recorded by the overload below when known
        return createService(serviceDTO, null, null, null);
    }

    /**
     * Creates a service under {@code serviceDTO.placeId}, recording the creator and the owning admin.
     * Clears place, service and queue caches so admin and provider lists update immediately.
     */
    @CacheEvict(value = {"places", "placesByAdmin", "services", "servicesByPlace", "queues", "queuesByPlace"}, allEntries = true)
    public Service createService(ServiceDTO serviceDTO, String creatorId, String creatorRole, String adminId) {
        log.debug("Creating new service: {} by {} ({})", serviceDTO, creatorId, creatorRole);

        Service service = new Service();
        service.setPlaceId(serviceDTO.getPlaceId());
        service.setName(serviceDTO.getName());
        service.setDescription(serviceDTO.getDescription());
        service.setAverageServiceTime(serviceDTO.getAverageServiceTime());
        service.setSupportsGroupToken(serviceDTO.getSupportsGroupToken());
        service.setEmergencySupport(serviceDTO.getEmergencySupport());
        service.setIsActive(serviceDTO.getIsActive());
        service.setCreatedBy(creatorId);
        service.setCreatedByRole(creatorRole);
        service.setAdminId(adminId);

        Service saved = serviceRepository.save(service);
        auditLogService.logEvent("SERVICE_CREATED", "Service created: " + saved.getName(),
                auditDetails(saved, creatorRole));
        log.info("Service saved with ID: {} – cache cleared for services and servicesByPlace", saved.getId());
        return saved;
    }

    public boolean isServiceOwnedByAdmin(String serviceId, String adminId) {
        Optional<Service> service = serviceRepository.findById(serviceId);
        if (service.isPresent()) {
            String placeId = service.get().getPlaceId();
            return placeService.isPlaceOwnedByAdmin(placeId, adminId);
        }
        return false;
    }

    @Cacheable(key = "#id", unless = "#result == null")
    public Service getServiceById(String id) {
        log.debug("Fetching service with ID: {} (cache miss)", id);
        return serviceRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Service not found with ID: {}", id);
                    return new RuntimeException("Service not found with id: " + id);
                });
    }

    @Cacheable(key = "#placeId", unless = "#result.isEmpty()")
    public List<Service> getServicesByPlaceId(String placeId) {
        log.debug("Fetching services by place ID: {} (cache miss)", placeId);
        List<Service> services = serviceRepository.findByPlaceId(placeId);
        log.info("Found {} services for place {}", services.size(), placeId);
        return services;
    }

    @CacheEvict(value = {"places", "placesByAdmin", "services", "servicesByPlace", "queues", "queuesByPlace"}, allEntries = true)
    public Service updateService(String id, ServiceDTO serviceDTO) {
        log.info("Updating service with ID: {} – will clear cache after update", id);
        Service service = getServiceById(id);

        if (serviceDTO.getName() != null) service.setName(serviceDTO.getName());
        if (serviceDTO.getDescription() != null) service.setDescription(serviceDTO.getDescription());
        if (serviceDTO.getAverageServiceTime() != null) service.setAverageServiceTime(serviceDTO.getAverageServiceTime());
        if (serviceDTO.getSupportsGroupToken() != null) service.setSupportsGroupToken(serviceDTO.getSupportsGroupToken());
        if (serviceDTO.getEmergencySupport() != null) service.setEmergencySupport(serviceDTO.getEmergencySupport());
        if (serviceDTO.getIsActive() != null) service.setIsActive(serviceDTO.getIsActive());

        Service updated = serviceRepository.save(service);
        log.info("Service updated successfully with ID: {} – cache cleared", id);
        auditLogService.logEvent("SERVICE_UPDATED", "Service updated: " + updated.getName(), auditDetails(updated, null));
        return updated;
    }

    @CacheEvict(value = {"places", "placesByAdmin", "services", "servicesByPlace", "queues", "queuesByPlace"}, allEntries = true)
    public void deleteService(String id) {
        log.warn("Deleting service with ID: {} – will clear cache after deletion", id);
        Service service = getServiceById(id);
        serviceRepository.delete(service);
        log.info("Service deleted successfully with ID: {} – cache cleared", id);
        auditLogService.logEvent("SERVICE_DELETED", "Service deleted: " + service.getName(), auditDetails(service, null));
    }

    /** All services under the given places (used for the admin's catalog list). */
    public List<Service> getServicesByPlaceIds(java.util.Collection<String> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return List.of();
        }
        return serviceRepository.findByPlaceIdIn(placeIds);
    }

    private static java.util.Map<String, Object> auditDetails(Service service, String role) {
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("serviceId", service.getId());
        details.put("placeId", service.getPlaceId());
        if (service.getAdminId() != null) details.put("adminId", service.getAdminId());
        if (service.getCreatedBy() != null) details.put("createdBy", service.getCreatedBy());
        if (role != null) details.put("role", role);
        return details;
    }

    public List<Service> getAllServices() {
        log.debug("Fetching all services (no caching)");
        List<Service> services = serviceRepository.findAll();
        log.info("Found {} services", services.size());
        return services;
    }
}