package com.qflow.backend.controller;

import com.qflow.backend.dto.PlaceDTO;
import com.qflow.backend.model.Place;
import com.qflow.backend.service.PlaceService;
import com.qflow.backend.security.annotations.AdminOnly;
import com.qflow.backend.security.annotations.AdminOrProviderOnly;
import com.qflow.backend.security.annotations.ProviderOnly;
import com.qflow.backend.service.CatalogAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
@Tag(name = "Places", description = "Endpoints for managing places")
@Validated
public class PlaceController {

    private final PlaceService placeService;
    private final CatalogAccessService catalogAccessService;

    @PostMapping
    @AdminOrProviderOnly
    @Operation(summary = "Create a new place",
            description = "Creates a new place. Admins create places they own (adminId must be their own id or empty). "
                    + "Providers create places owned by their admin, so the place also appears in that admin's lists; "
                    + "the provider is recorded as the creator.")
    @ApiResponse(responseCode = "200", description = "Place created successfully",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error, or the provider account is not linked to an admin")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden – admin ID mismatch")
    @ApiResponse(responseCode = "500", description = "Internal server error")
    public ResponseEntity<PlaceDTO> createPlace(@Valid @RequestBody PlaceDTO placeDTO) {
        CatalogAccessService.Actor actor = currentActor();
        if (actor == null) {
            log.error("Authenticated ADMIN or PROVIDER not found for createPlace");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Request received to create place: {} by {} {}", placeDTO, actor.role(), actor.id());

        if (actor.isAdmin()) {
            if (placeDTO.getAdminId() != null && !placeDTO.getAdminId().isBlank() && !placeDTO.getAdminId().equals(actor.id())) {
                log.warn("Unauthorized attempt to create place for adminId={} by {}", placeDTO.getAdminId(), actor.id());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            placeDTO.setAdminId(actor.id());
        } else {
            // A provider's place belongs to the provider. If the provider works for an admin, that admin also
            // owns it (so it shows up in the admin's dashboard); independent providers have no admin.
            placeDTO.setAdminId(catalogAccessService.owningAdminIdForProvider(actor.id()).orElse(null));
        }

        try {
            Place place = placeService.createPlace(placeDTO, actor.id(), actor.role().name());
            if (actor.isProvider()) {
                // Providers sign up without a place: link the place they create to their account
                catalogAccessService.linkCreatedPlaceToProvider(actor.id(), place.getId());
            }
            log.info("Place created successfully with ID: {}", place.getId());
            return ResponseEntity.ok(PlaceDTO.fromEntity(place));
        } catch (Exception e) {
            log.error("Error creating place: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/provider/my-places")
    @ProviderOnly
    @Operation(summary = "Get places the provider can manage",
            description = "Returns the places the authenticated provider created plus the places assigned to them, with creator details.")
    @ApiResponse(responseCode = "200", description = "List of places",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    public ResponseEntity<List<PlaceDTO>> getProviderManageablePlaces() {
        CatalogAccessService.Actor actor = currentActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<PlaceDTO> places = catalogAccessService.manageablePlacesForProvider(actor.id()).stream()
                .map(PlaceDTO::fromEntity)
                .collect(Collectors.toList());
        log.info("Returning {} manageable places for provider {}", places.size(), actor.id());
        return ResponseEntity.ok(catalogAccessService.withCreatorDetails(places));
    }

    /**
     * Returns null when the actor may change the place, otherwise the 403/404 response. Admins keep the original
     * rule (they own the place); providers may change places they created or that are assigned to them.
     */
    private <T> ResponseEntity<T> checkPlaceAccess(String placeId, CatalogAccessService.Actor actor, String action) {
        if (actor.isAdmin()) {
            if (!placeService.isPlaceOwnedByAdmin(placeId, actor.id())) {
                log.warn("Unauthorized attempt to {} place={} by admin {}", action, placeId, actor.id());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            return null;
        }
        Place place = catalogAccessService.findPlace(placeId).orElse(null);
        if (place == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!catalogAccessService.canManagePlace(place, actor)) {
            log.warn("Unauthorized attempt to {} place={} by provider {}", action, placeId, actor.id());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
    }

    private CatalogAccessService.Actor currentActor() {
        return catalogAccessService.actorOf(SecurityContextHolder.getContext().getAuthentication()).orElse(null);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get place by ID", description = "Returns a single place by its ID. Public access.")
    @ApiResponse(responseCode = "200", description = "Place found",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    @ApiResponse(responseCode = "404", description = "Place not found")
    public ResponseEntity<PlaceDTO> getPlace(@PathVariable String id) {
        log.debug("Fetching place with ID: {}", id);
        try {
            Place place = placeService.getPlaceById(id);
            log.info("Fetched place: {}", place);
            return ResponseEntity.ok(PlaceDTO.fromEntity(place));
        } catch (Exception e) {
            log.error("Error fetching place: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/admin/my-places")
    @AdminOnly
    @Operation(summary = "Get my places", description = "Returns all places owned by the authenticated admin, including places created by the admin's providers, with creator details (createdBy, createdByRole, createdByName).")
    @ApiResponse(responseCode = "200", description = "List of places",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    public ResponseEntity<List<PlaceDTO>> getMyPlaces() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String adminId = authentication.getName();

        if (adminId == null) {
            log.error("Admin ID not found in authentication");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            List<Place> places = placeService.getPlacesByAdminId(adminId);
            return ResponseEntity.ok(catalogAccessService.withCreatorDetails(
                    places.stream().map(PlaceDTO::fromEntity).collect(Collectors.toList())));
        } catch (Exception e) {
            log.error("Error fetching places for admin: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/admin/{adminId}")
    @AdminOnly
    @Operation(summary = "Get places by admin ID", description = "Returns all places for a specific admin. The authenticated admin must match the requested adminId.")
    @ApiResponse(responseCode = "200", description = "List of places",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden – admin ID mismatch")
    public ResponseEntity<List<PlaceDTO>> getPlacesByAdmin(@PathVariable String adminId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentAdminId = authentication.getName();

        if (currentAdminId == null) {
            log.error("Admin ID not found in authentication");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!adminId.equals(currentAdminId)) {
            log.warn("Unauthorized attempt to access places of adminId={} by {}", adminId, currentAdminId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.debug("Fetching places for admin ID: {}", adminId);
        try {
            List<Place> places = placeService.getPlacesByAdminId(adminId);
            log.info("Fetched {} places for admin {}", places.size(), adminId);
            return ResponseEntity.ok(places.stream().map(PlaceDTO::fromEntity).collect(Collectors.toList()));
        } catch (Exception e) {
            log.error("Error fetching places by admin: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/type/{type}")
    @Operation(summary = "Get places by type", description = "Returns all places of a given type. Public access.")
    @ApiResponse(responseCode = "200", description = "List of places",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    public ResponseEntity<List<PlaceDTO>> getPlacesByType(@PathVariable String type) {
        log.debug("Fetching places of type: {}", type);
        List<Place> places = placeService.getPlacesByType(type);
        log.info("Fetched {} places of type {}", places.size(), type);
        return ResponseEntity.ok(places.stream().map(PlaceDTO::fromEntity).collect(Collectors.toList()));
    }

    @GetMapping("/nearby")
    @Operation(summary = "Get nearby places", description = "Returns places within a given radius (km) from the specified coordinates. Public access.")
    @ApiResponse(responseCode = "200", description = "List of nearby places",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid radius (must be positive and ≤50)")
    public ResponseEntity<List<PlaceDTO>> getNearbyPlaces(
            @Parameter(description = "Longitude") @RequestParam double longitude,
            @Parameter(description = "Latitude") @RequestParam double latitude,
            @Parameter(description = "Radius in kilometers") @RequestParam(defaultValue = "5") @Positive @Max(50) double radius) {
        log.debug("Searching nearby places [lon={}, lat={}, radius={}km]", longitude, latitude, radius);
        List<Place> places = placeService.getNearbyPlaces(longitude, latitude, radius);
        log.info("Found {} nearby places", places.size());
        return ResponseEntity.ok(places.stream().map(PlaceDTO::fromEntity).collect(Collectors.toList()));
    }

    @PutMapping("/{id}")
    @AdminOrProviderOnly
    @Operation(summary = "Update a place", description = "Updates an existing place. Admins can update every place they own "
            + "(including places created by their providers); providers can update places they created or that are assigned to them.")
    @ApiResponse(responseCode = "200", description = "Place updated",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden – not the owner")
    @ApiResponse(responseCode = "404", description = "Place not found")
    public ResponseEntity<PlaceDTO> updatePlace(@PathVariable String id, @Valid @RequestBody PlaceDTO placeDTO) {
        CatalogAccessService.Actor actor = currentActor();
        if (actor == null) {
            log.error("Authenticated ADMIN or PROVIDER not found for updatePlace");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Updating place with ID: {} | Data: {} by {} {}", id, placeDTO, actor.role(), actor.id());

        try {
            ResponseEntity<PlaceDTO> denied = checkPlaceAccess(id, actor, "update");
            if (denied != null) {
                return denied;
            }

            Place place = placeService.updatePlace(id, placeDTO);
            log.info("Place updated successfully with ID: {}", id);
            return ResponseEntity.ok(PlaceDTO.fromEntity(place));
        } catch (Exception e) {
            log.error("Error updating place: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    @AdminOrProviderOnly
    @Operation(summary = "Delete a place", description = "Deletes an existing place. Admins can delete every place they own "
            + "(including places created by their providers); providers can delete places they created or that are assigned to them.")
    @ApiResponse(responseCode = "200", description = "Place deleted")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden – not the owner")
    @ApiResponse(responseCode = "404", description = "Place not found")
    public ResponseEntity<Void> deletePlace(@PathVariable String id) {
        CatalogAccessService.Actor actor = currentActor();
        if (actor == null) {
            log.error("Authenticated ADMIN or PROVIDER not found for deletePlace");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.warn("Request received to delete place with ID: {} by {} {}", id, actor.role(), actor.id());

        try {
            ResponseEntity<Void> denied = checkPlaceAccess(id, actor, "delete");
            if (denied != null) {
                return denied;
            }

            placeService.deletePlace(id);
            log.info("Place deleted successfully with ID: {}", id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error deleting place: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping
    @Operation(summary = "Get all places (non-paginated)", description = "Returns all places. Public access. Consider using /paginated for large datasets.")
    @ApiResponse(responseCode = "200", description = "List of all places",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    public ResponseEntity<List<PlaceDTO>> getAllPlaces() {
        log.debug("Fetching all places");
        List<Place> places = placeService.getAllPlaces();
        log.info("Fetched {} places", places.size());
        return ResponseEntity.ok(places.stream().map(PlaceDTO::fromEntity).collect(Collectors.toList()));
    }

    @GetMapping("/paginated")
    @Operation(summary = "Get paginated places", description = "Returns a paginated list of places. Public access.")
    @ApiResponse(responseCode = "200", description = "Paginated list of places",
            content = @Content(schema = @Schema(implementation = Page.class)))
    public ResponseEntity<Page<PlaceDTO>> getPlacesPaginated(
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        log.debug("Fetching paginated places: page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        Page<Place> placePage = placeService.getAllPlacesPaginated(pageable);
        Page<PlaceDTO> dtoPage = placePage.map(PlaceDTO::fromEntity);
        return ResponseEntity.ok(dtoPage);
    }

    @GetMapping("/top-rated")
    @Operation(summary = "Get top-rated places", description = "Returns a limited number of places with the highest average rating.")
    @ApiResponse(responseCode = "200", description = "List of top-rated places",
            content = @Content(schema = @Schema(implementation = PlaceDTO.class)))
    public ResponseEntity<List<PlaceDTO>> getTopRatedPlaces(@RequestParam(defaultValue = "3") int limit) {
        log.info("Fetching top {} rated places", limit);
        List<Place> places = placeService.getTopRatedPlaces(limit);
        List<PlaceDTO> dtos = places.stream().map(PlaceDTO::fromEntity).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }
}