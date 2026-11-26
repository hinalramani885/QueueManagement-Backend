package com.qflow.backend.service;

import com.qflow.backend.dto.PlaceDTO;
import com.qflow.backend.dto.ServiceDTO;
import com.qflow.backend.enums.Role;
import com.qflow.backend.model.Place;
import com.qflow.backend.model.Service;
import com.qflow.backend.model.User;
import com.qflow.backend.repository.PlaceRepository;
import com.qflow.backend.repository.ServiceRepository;
import com.qflow.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who may create, change or delete places and services ("the catalog"), plus creator details for responses.
 *
 * <ul>
 *   <li><b>ADMIN</b> manages every place whose {@code adminId} is the admin, including places and services
 *       created by the admin's providers.</li>
 *   <li><b>PROVIDER</b> manages places they created or that are assigned to them ({@code managedPlaceIds}),
 *       and services they created or that belong to such a place. A provider's places are owned by the
 *       provider's admin, so they automatically appear in that admin's lists.</li>
 * </ul>
 */
@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class CatalogAccessService {

    private final UserRepository userRepository;
    private final PlaceRepository placeRepository;
    private final ServiceRepository serviceRepository;

    /** The caller of a catalog endpoint: their user id and role (ADMIN or PROVIDER). */
    public record Actor(String id, Role role) {
        public boolean isAdmin() {
            return role == Role.ADMIN;
        }

        public boolean isProvider() {
            return role == Role.PROVIDER;
        }
    }

    /** Builds the actor from the authenticated principal (name = user id, authority = ROLE_ADMIN / ROLE_PROVIDER). */
    public Optional<Actor> actorOf(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return Optional.empty();
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (authorities.contains("ROLE_ADMIN")) {
            return Optional.of(new Actor(authentication.getName(), Role.ADMIN));
        }
        if (authorities.contains("ROLE_PROVIDER")) {
            return Optional.of(new Actor(authentication.getName(), Role.PROVIDER));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ places

    /** Uncached lookup, so permission checks always see the current owner fields. */
    public Optional<Place> findPlace(String placeId) {
        return placeId == null ? Optional.empty() : placeRepository.findById(placeId);
    }

    /** Uncached lookup of a service, so permission checks always see the current owner fields. */
    public Optional<Service> findService(String serviceId) {
        return serviceId == null ? Optional.empty() : serviceRepository.findById(serviceId);
    }

    /**
     * The admin that owns everything a provider creates. Uses the provider's {@code adminId}; for older
     * provider accounts without one, falls back to the admin of their assigned place and stores that link.
     *
     * @return the admin id, or empty when the provider cannot be linked to any admin
     */
    public Optional<String> owningAdminIdForProvider(String providerId) {
        User provider = userRepository.findById(providerId).orElse(null);
        if (provider == null) {
            return Optional.empty();
        }
        if (provider.getAdminId() != null && !provider.getAdminId().isBlank()) {
            return Optional.of(provider.getAdminId());
        }
        List<String> candidatePlaceIds = new ArrayList<>();
        if (provider.getPlaceId() != null) {
            candidatePlaceIds.add(provider.getPlaceId());
        }
        if (provider.getManagedPlaceIds() != null) {
            candidatePlaceIds.addAll(provider.getManagedPlaceIds());
        }
        for (String placeId : candidatePlaceIds) {
            Optional<String> adminId = placeRepository.findById(placeId).map(Place::getAdminId).filter(a -> !a.isBlank());
            if (adminId.isPresent()) {
                provider.setAdminId(adminId.get());
                userRepository.save(provider);
                log.info("Linked provider {} to admin {} via assigned place", providerId, adminId.get());
                return adminId;
            }
        }
        return Optional.empty();
    }

    /**
     * Makes a place the provider just created show up in their "my places" and queue-creation lists. Providers
     * with an empty {@code managedPlaceIds} already see every place of their admin, so the list is left alone
     * for them (adding one entry would hide the admin's other places).
     */
    public void assignCreatedPlaceToProvider(String providerId, String placeId) {
        userRepository.findById(providerId).ifPresent(provider -> {
            List<String> managed = provider.getManagedPlaceIds();
            if (managed != null && !managed.isEmpty() && !managed.contains(placeId)) {
                managed.add(placeId);
                userRepository.save(provider);
            }
        });
    }

    public boolean canManagePlace(Place place, Actor actor) {
        if (place == null || actor == null) {
            return false;
        }
        if (actor.isAdmin()) {
            return Objects.equals(place.getAdminId(), actor.id());
        }
        if (actor.isProvider()) {
            return Objects.equals(place.getCreatedBy(), actor.id()) || assignedPlaceIds(actor.id()).contains(place.getId());
        }
        return false;
    }

    /** Places a provider may manage: the ones they created plus the ones assigned to them. */
    public List<Place> manageablePlacesForProvider(String providerId) {
        Map<String, Place> byId = new LinkedHashMap<>();
        placeRepository.findByCreatedBy(providerId).forEach(p -> byId.put(p.getId(), p));
        Set<String> assigned = assignedPlaceIds(providerId);
        if (!assigned.isEmpty()) {
            placeRepository.findAllById(assigned).forEach(p -> byId.putIfAbsent(p.getId(), p));
        }
        return new ArrayList<>(byId.values());
    }

    private Set<String> assignedPlaceIds(String providerId) {
        return userRepository.findById(providerId)
                .map(User::getManagedPlaceIds)
                .<Set<String>>map(HashSet::new)
                .orElseGet(HashSet::new);
    }

    // ---------------------------------------------------------------- services

    /** Whether the actor may add a service under this place (same rule as managing the place). */
    public boolean canCreateServiceUnder(Place place, Actor actor) {
        return canManagePlace(place, actor);
    }

    public boolean canManageService(Service service, Place place, Actor actor) {
        if (service == null || actor == null) {
            return false;
        }
        if (actor.isAdmin()) {
            return place != null ? Objects.equals(place.getAdminId(), actor.id()) : Objects.equals(service.getAdminId(), actor.id());
        }
        if (actor.isProvider()) {
            return Objects.equals(service.getCreatedBy(), actor.id()) || canManagePlace(place, actor);
        }
        return false;
    }

    // ------------------------------------------------------- creator details

    /** Fills in the creator's name (and keeps the server-side creator id/role) on place DTOs. */
    public List<PlaceDTO> withCreatorDetails(List<PlaceDTO> places) {
        Map<String, User> users = usersById(places.stream().map(PlaceDTO::getCreatedBy).toList());
        places.forEach(dto -> {
            User creator = users.get(dto.getCreatedBy());
            if (creator != null) {
                dto.setCreatedByName(creator.getName());
            }
        });
        return places;
    }

    /** Fills in creator name and place name on service DTOs; legacy services fall back to the place's admin. */
    public List<ServiceDTO> withCreatorDetails(List<ServiceDTO> services, Collection<Place> places) {
        Map<String, Place> placesById = places.stream().collect(Collectors.toMap(Place::getId, Function.identity(), (a, b) -> a));
        services.forEach(dto -> {
            Place place = placesById.get(dto.getPlaceId());
            if (place != null) {
                dto.setPlaceName(place.getName());
                if (dto.getAdminId() == null) {
                    dto.setAdminId(place.getAdminId());
                }
            }
            if (dto.getCreatedBy() == null) {
                dto.setCreatedBy(dto.getAdminId());
            }
        });
        Map<String, User> users = usersById(services.stream().map(ServiceDTO::getCreatedBy).toList());
        services.forEach(dto -> {
            User creator = users.get(dto.getCreatedBy());
            if (creator != null) {
                dto.setCreatedByName(creator.getName());
            }
        });
        return services;
    }

    private Map<String, User> usersById(List<String> ids) {
        Set<String> unique = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<String, User> result = new LinkedHashMap<>();
        userRepository.findAllById(unique).forEach(u -> result.put(u.getId(), u));
        return result;
    }
}
