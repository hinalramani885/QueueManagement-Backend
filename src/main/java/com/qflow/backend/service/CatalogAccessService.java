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
 *   <li><b>PROVIDER</b> signs up without a place and then creates their own. They manage places they created
 *       ({@code createdBy}, linked to their account via {@code ownedPlaceIds}/{@code placeId}) or that an admin
 *       assigned to them ({@code managedPlaceIds}), and services they created or that belong to such a place.
 *       When the provider works for an admin, their places are also owned by that admin, so they appear in the
 *       admin's lists; independent providers (no admin) own their places alone.</li>
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
     * @return the admin id, or empty for independent providers (self-signed-up, no admin) – their places
     *         then have no owning admin and belong to the provider alone
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
     * Links a place the provider just created to the provider's account: it is added to {@code ownedPlaceIds},
     * and becomes their main {@code placeId} when they have none yet (providers sign up without a place).
     * {@code managedPlaceIds} (places assigned by an admin) is left alone: an empty list means "every place of
     * my admin", and adding one entry would hide the admin's other places.
     *
     * @return true when the user document was changed
     */
    public boolean linkCreatedPlaceToProvider(String providerId, String placeId) {
        User provider = userRepository.findById(providerId).orElse(null);
        if (provider == null || placeId == null) {
            return false;
        }
        boolean changed = false;
        if (provider.getOwnedPlaceIds() == null) {
            provider.setOwnedPlaceIds(new ArrayList<>());
        }
        if (!provider.getOwnedPlaceIds().contains(placeId)) {
            provider.getOwnedPlaceIds().add(placeId);
            changed = true;
        }
        if (provider.getPlaceId() == null || provider.getPlaceId().isBlank()) {
            provider.setPlaceId(placeId);
            changed = true;
        }
        if (changed) {
            userRepository.save(provider);
            log.info("Linked place {} to provider {}", placeId, providerId);
        }
        return changed;
    }

    public boolean canManagePlace(Place place, Actor actor) {
        if (place == null || actor == null) {
            return false;
        }
        if (actor.isAdmin()) {
            return place.getAdminId() != null && place.getAdminId().equals(actor.id());
        }
        if (actor.isProvider()) {
            return Objects.equals(place.getCreatedBy(), actor.id()) || linkedPlaceIds(actor.id()).contains(place.getId());
        }
        return false;
    }

    /** Places a provider may manage: the ones they created/own plus the ones assigned to them. */
    public List<Place> manageablePlacesForProvider(String providerId) {
        Map<String, Place> byId = new LinkedHashMap<>();
        placeRepository.findByCreatedBy(providerId).forEach(p -> byId.put(p.getId(), p));
        Set<String> linked = linkedPlaceIds(providerId);
        linked.removeAll(byId.keySet());
        if (!linked.isEmpty()) {
            placeRepository.findAllById(linked).forEach(p -> byId.putIfAbsent(p.getId(), p));
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Places a provider can run queues at: everything they manage, plus – for providers of an admin who have
     * no specific assignment – every place of that admin (the original behaviour).
     */
    public List<Place> queuePlacesForProvider(String providerId) {
        User provider = userRepository.findById(providerId).orElse(null);
        if (provider == null) {
            return List.of();
        }
        Map<String, Place> byId = new LinkedHashMap<>();
        manageablePlacesForProvider(providerId).forEach(p -> byId.put(p.getId(), p));
        boolean noAssignment = provider.getManagedPlaceIds() == null || provider.getManagedPlaceIds().isEmpty();
        if (noAssignment && provider.getAdminId() != null && !provider.getAdminId().isBlank()) {
            placeRepository.findByAdminId(provider.getAdminId()).forEach(p -> byId.putIfAbsent(p.getId(), p));
        }
        return new ArrayList<>(byId.values());
    }

    /** Whether a provider may run a queue at this place (same places as {@link #queuePlacesForProvider}). */
    public boolean canRunQueueAt(User provider, Place place) {
        if (provider == null || place == null) {
            return false;
        }
        if (Objects.equals(place.getCreatedBy(), provider.getId())) {
            return true;
        }
        if (provider.getOwnedPlaceIds() != null && provider.getOwnedPlaceIds().contains(place.getId())) {
            return true;
        }
        if (provider.getManagedPlaceIds() != null && provider.getManagedPlaceIds().contains(place.getId())) {
            return true;
        }
        boolean noAssignment = provider.getManagedPlaceIds() == null || provider.getManagedPlaceIds().isEmpty();
        return noAssignment && place.getAdminId() != null && place.getAdminId().equals(provider.getAdminId());
    }

    /** Ids of places linked to the provider: owned (created by them) and assigned by an admin. */
    private Set<String> linkedPlaceIds(String providerId) {
        Set<String> ids = new HashSet<>();
        userRepository.findById(providerId).ifPresent(u -> {
            if (u.getOwnedPlaceIds() != null) ids.addAll(u.getOwnedPlaceIds());
            if (u.getManagedPlaceIds() != null) ids.addAll(u.getManagedPlaceIds());
        });
        return ids;
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
