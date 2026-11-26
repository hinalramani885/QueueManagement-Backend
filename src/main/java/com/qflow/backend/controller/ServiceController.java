package com.qflow.backend.controller;

import com.qflow.backend.dto.ServiceDTO;
import com.qflow.backend.model.Place;
import com.qflow.backend.model.Service;
import com.qflow.backend.service.PlaceService;
import com.qflow.backend.service.ServiceService;
import com.qflow.backend.security.annotations.AdminOnly;
import com.qflow.backend.security.annotations.AdminOrProviderOnly;
import com.qflow.backend.service.CatalogAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
@Tag(name = "Services", description = "Endpoints for managing services")
public class ServiceController {

    private final ServiceService serviceService;
    private final PlaceService placeService;
    private final CatalogAccessService catalogAccessService;

    @PostMapping
    @AdminOrProviderOnly
    @Operation(summary = "Create a new service", description = "Creates a new service under a place. Admins can add services to "
            + "places they own; providers can add services only to places they created or that are assigned to them. "
            + "The creator and the place's admin are recorded, so the service appears in the admin's lists.")
    @ApiResponse(responseCode = "200", description = "Service created",
            content = @Content(schema = @Schema(implementation = ServiceDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden – caller may not manage the place")
    @ApiResponse(responseCode = "404", description = "Place not found")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ResponseEntity<ServiceDTO> createService(@Valid @RequestBody ServiceDTO serviceDTO) {
        CatalogAccessService.Actor actor = currentActor();
        if (actor == null) {
            log.error("Authenticated ADMIN or PROVIDER not found for createService");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Request to create service: {} by {} {}", serviceDTO, actor.role(), actor.id());

        String owningAdminId;
        if (actor.isAdmin()) {
            if (!placeService.isPlaceOwnedByAdmin(serviceDTO.getPlaceId(), actor.id())) {
                log.warn("Unauthorized attempt to create service for place={} by {}", serviceDTO.getPlaceId(), actor.id());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            owningAdminId = actor.id();
        } else {
            Place place = catalogAccessService.findPlace(serviceDTO.getPlaceId()).orElse(null);
            if (place == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            if (!catalogAccessService.canCreateServiceUnder(place, actor)) {
                log.warn("Provider {} may not add services to place={}", actor.id(), place.getId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            owningAdminId = place.getAdminId();
        }

        try {
            Service service = serviceService.createService(serviceDTO, actor.id(), actor.role().name(), owningAdminId);
            log.info("Service created successfully with ID: {}", service.getId());
            return ResponseEntity.ok(ServiceDTO.fromEntity(service));
        } catch (Exception e) {
            log.error("Error creating service: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get service by ID", description = "Returns a single service by its ID. Public access.")
    @ApiResponse(responseCode = "200", description = "Service found",
            content = @Content(schema = @Schema(implementation = ServiceDTO.class)))
    @ApiResponse(responseCode = "404", description = "Service not found")
    public ResponseEntity<ServiceDTO> getService(@PathVariable String id) {
        log.debug("Fetching service with ID: {}", id);
        try {
            Service service = serviceService.getServiceById(id);
            log.info("Fetched service: {}", service);
            return ResponseEntity.ok(ServiceDTO.fromEntity(service));
        } catch (Exception e) {
            log.error("Error fetching service: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/place/{placeId}")
    @Operation(summary = "Get services by place ID", description = "Returns all services for a given place. Public access.")
    @ApiResponse(responseCode = "200", description = "List of services")
    public ResponseEntity<List<ServiceDTO>> getServicesByPlace(@PathVariable String placeId) {
        log.debug("Fetching services for place ID: {}", placeId);
        List<Service> services = serviceService.getServicesByPlaceId(placeId);
        log.info("Fetched {} services for place {}", services.size(), placeId);
        return ResponseEntity.ok(services.stream().map(ServiceDTO::fromEntity).collect(Collectors.toList()));
    }

    @PutMapping("/{id}")
    @AdminOrProviderOnly
    @Operation(summary = "Update a service", description = "Updates an existing service. Admins can update services of places they own; "
            + "providers can update services they created or that belong to a place they created or are assigned to.")
    @ApiResponse(responseCode = "200", description = "Service updated",
            content = @Content(schema = @Schema(implementation = ServiceDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden – not the admin")
    @ApiResponse(responseCode = "404", description = "Service not found")
    public ResponseEntity<ServiceDTO> updateService(@PathVariable String id, @Valid @RequestBody ServiceDTO serviceDTO) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            log.error("Authentication is null for updateService");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        CatalogAccessService.Actor actor = currentActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Updating service with ID: {} | Data: {} by {} {}", id, serviceDTO, actor.role(), actor.id());

        ResponseEntity<ServiceDTO> denied = checkServiceAccess(id, actor, "update");
        if (denied != null) {
            return denied;
        }

        try {
            Service service = serviceService.updateService(id, serviceDTO);
            log.info("Service updated successfully with ID: {}", id);
            return ResponseEntity.ok(ServiceDTO.fromEntity(service));
        } catch (Exception e) {
            log.error("Error updating service: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    @AdminOrProviderOnly
    @Operation(summary = "Delete a service", description = "Deletes an existing service. Admins can delete services of places they own; "
            + "providers can delete services they created or that belong to a place they created or are assigned to.")
    @ApiResponse(responseCode = "200", description = "Service deleted")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden – not the admin")
    @ApiResponse(responseCode = "404", description = "Service not found")
    public ResponseEntity<Void> deleteService(@PathVariable String id) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            log.error("Authentication is null for deleteService");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        CatalogAccessService.Actor actor = currentActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.warn("Deleting service with ID: {} by {} {}", id, actor.role(), actor.id());

        ResponseEntity<Void> denied = checkServiceAccess(id, actor, "delete");
        if (denied != null) {
            return denied;
        }

        try {
            serviceService.deleteService(id);
            log.info("Service deleted successfully with ID: {}", id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error deleting service: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/admin/my-services")
    @AdminOnly
    @Operation(summary = "Get services of my places", description = "Returns every service under the places owned by the authenticated "
            + "admin, including services created by the admin's providers, with place name and creator details.")
    @ApiResponse(responseCode = "200", description = "List of services",
            content = @Content(schema = @Schema(implementation = ServiceDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    public ResponseEntity<List<ServiceDTO>> getAdminServices() {
        CatalogAccessService.Actor actor = currentActor();
        if (actor == null || !actor.isAdmin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Place> places = placeService.getPlacesByAdminId(actor.id());
        List<ServiceDTO> services = serviceService.getServicesByPlaceIds(places.stream().map(Place::getId).toList()).stream()
                .map(ServiceDTO::fromEntity)
                .collect(Collectors.toList());
        log.info("Returning {} services for admin {}", services.size(), actor.id());
        return ResponseEntity.ok(catalogAccessService.withCreatorDetails(services, places));
    }

    /**
     * Returns null when the actor may change the service, otherwise the 403/404 response. Admins keep the original
     * rule (they own the service's place); providers use {@link CatalogAccessService#canManageService}.
     */
    private <T> ResponseEntity<T> checkServiceAccess(String serviceId, CatalogAccessService.Actor actor, String action) {
        if (actor.isAdmin()) {
            if (!serviceService.isServiceOwnedByAdmin(serviceId, actor.id())) {
                log.warn("Unauthorized attempt to {} service={} by admin {}", action, serviceId, actor.id());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            return null;
        }
        Service service = catalogAccessService.findService(serviceId).orElse(null);
        if (service == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Place place = catalogAccessService.findPlace(service.getPlaceId()).orElse(null);
        if (!catalogAccessService.canManageService(service, place, actor)) {
            log.warn("Unauthorized attempt to {} service={} by provider {}", action, serviceId, actor.id());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
    }

    private CatalogAccessService.Actor currentActor() {
        return catalogAccessService.actorOf(SecurityContextHolder.getContext().getAuthentication()).orElse(null);
    }

    @GetMapping
    @Operation(summary = "Get all services", description = "Returns all services. Public access. Consider adding pagination if needed.")
    @ApiResponse(responseCode = "200", description = "List of all services")
    public ResponseEntity<List<ServiceDTO>> getAllServices() {
        log.debug("Fetching all services");
        List<Service> services = serviceService.getAllServices();
        log.info("Fetched {} services", services.size());
        return ResponseEntity.ok(services.stream().map(ServiceDTO::fromEntity).collect(Collectors.toList()));
    }
}