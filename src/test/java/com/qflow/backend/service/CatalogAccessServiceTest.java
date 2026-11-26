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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @InjectMocks
    private CatalogAccessService access;

    private static final CatalogAccessService.Actor ADMIN = new CatalogAccessService.Actor("admin1", Role.ADMIN);
    private static final CatalogAccessService.Actor PROVIDER = new CatalogAccessService.Actor("prov1", Role.PROVIDER);

    private Place place(String id, String adminId, String createdBy) {
        Place p = new Place();
        p.setId(id);
        p.setName("Place " + id);
        p.setAdminId(adminId);
        p.setCreatedBy(createdBy);
        return p;
    }

    private User provider(String adminId, List<String> managed) {
        User u = new User();
        u.setId("prov1");
        u.setName("Priya");
        u.setRole(Role.PROVIDER);
        u.setAdminId(adminId);
        u.setManagedPlaceIds(new ArrayList<>(managed));
        return u;
    }

    @Test
    void actorOfReadsRoleFromAuthorities() {
        var adminAuth = new UsernamePasswordAuthenticationToken("admin1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var userAuth = new UsernamePasswordAuthenticationToken("u1", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertEquals(ADMIN, access.actorOf(adminAuth).orElseThrow());
        assertTrue(access.actorOf(userAuth).isEmpty());
        assertTrue(access.actorOf(null).isEmpty());
    }

    @Test
    void adminManagesEveryPlaceTheyOwnIncludingProviderCreated() {
        assertTrue(access.canManagePlace(place("p1", "admin1", "prov9"), ADMIN));
        assertFalse(access.canManagePlace(place("p2", "admin2", "admin2"), ADMIN));
        assertFalse(access.canManagePlace(place("p3", null, null), ADMIN));
    }

    @Test
    void providerManagesCreatedOrAssignedPlacesOnly() {
        when(userRepository.findById("prov1")).thenReturn(Optional.of(provider("admin1", List.of("assigned"))));
        assertTrue(access.canManagePlace(place("own", "admin1", "prov1"), PROVIDER));
        assertTrue(access.canManagePlace(place("assigned", "admin1", "admin1"), PROVIDER));
        assertFalse(access.canManagePlace(place("other", "admin1", "prov2"), PROVIDER));
    }

    @Test
    void providerManagesOwnServiceEvenUnderAnotherPlace() {
        Service own = Service.builder().id("s1").placeId("other").createdBy("prov1").build();
        assertTrue(access.canManageService(own, place("other", "admin1", "prov2"), PROVIDER));
    }

    @Test
    void providerCannotManageOthersServiceUnderOthersPlace() {
        when(userRepository.findById("prov1")).thenReturn(Optional.of(provider("admin1", List.of())));
        Service other = Service.builder().id("s2").placeId("other").createdBy("prov2").build();
        assertFalse(access.canManageService(other, place("other", "admin1", "prov2"), PROVIDER));
    }

    @Test
    void owningAdminUsesProviderAdminId() {
        when(userRepository.findById("prov1")).thenReturn(Optional.of(provider("admin1", List.of())));
        assertEquals("admin1", access.owningAdminIdForProvider("prov1").orElseThrow());
        verify(userRepository, never()).save(any());
    }

    @Test
    void owningAdminFallsBackToAssignedPlaceAndStoresTheLink() {
        User legacy = provider(null, List.of("assigned"));
        when(userRepository.findById("prov1")).thenReturn(Optional.of(legacy));
        when(placeRepository.findById("assigned")).thenReturn(Optional.of(place("assigned", "admin7", "admin7")));

        assertEquals("admin7", access.owningAdminIdForProvider("prov1").orElseThrow());
        assertEquals("admin7", legacy.getAdminId());
        verify(userRepository).save(legacy);
    }

    @Test
    void owningAdminEmptyWhenProviderCannotBeLinked() {
        when(userRepository.findById("prov1")).thenReturn(Optional.of(provider(null, List.of())));
        assertTrue(access.owningAdminIdForProvider("prov1").isEmpty());
    }

    @Test
    void createdPlaceIsLinkedToProviderWithoutAPlace() {
        User newProvider = provider(null, List.of()); // signed up without a place
        when(userRepository.findById("prov1")).thenReturn(Optional.of(newProvider));

        assertTrue(access.linkCreatedPlaceToProvider("prov1", "firmPlace"));

        assertEquals("firmPlace", newProvider.getPlaceId());
        assertEquals(List.of("firmPlace"), newProvider.getOwnedPlaceIds());
        assertTrue(newProvider.getManagedPlaceIds().isEmpty(), "assigned places stay untouched");
        verify(userRepository).save(newProvider);
    }

    @Test
    void secondPlaceKeepsMainPlaceAndLinkIsIdempotent() {
        User provider = provider("admin1", List.of());
        provider.setPlaceId("first");
        provider.setOwnedPlaceIds(new ArrayList<>(List.of("first")));
        when(userRepository.findById("prov1")).thenReturn(Optional.of(provider));

        assertTrue(access.linkCreatedPlaceToProvider("prov1", "second"));
        assertEquals("first", provider.getPlaceId());
        assertEquals(List.of("first", "second"), provider.getOwnedPlaceIds());

        assertFalse(access.linkCreatedPlaceToProvider("prov1", "second"), "running again changes nothing");
        verify(userRepository, times(1)).save(provider);
    }

    @Test
    void providerManagesOwnedPlace() {
        User provider = provider(null, List.of());
        provider.setOwnedPlaceIds(new ArrayList<>(List.of("owned")));
        when(userRepository.findById("prov1")).thenReturn(Optional.of(provider));
        assertTrue(access.canManagePlace(place("owned", null, null), PROVIDER));
    }

    @Test
    void independentProviderRunsQueuesOnlyAtOwnPlaces() {
        User independent = provider(null, List.of());
        independent.setOwnedPlaceIds(new ArrayList<>(List.of("own")));
        assertTrue(access.canRunQueueAt(independent, place("own", null, "prov1")));
        assertFalse(access.canRunQueueAt(independent, place("someoneElses", null, "prov2")),
                "a place without an admin must not match a provider without an admin");
    }

    @Test
    void adminProviderWithoutAssignmentStillRunsQueuesAtAdminPlaces() {
        User adminProvider = provider("admin1", List.of());
        assertTrue(access.canRunQueueAt(adminProvider, place("adminPlace", "admin1", "admin1")));
        assertFalse(access.canRunQueueAt(adminProvider, place("otherAdmin", "admin2", "admin2")));
    }

    @Test
    void queuePlacesCombineOwnAssignedAndAdminPlaces() {
        User adminProvider = provider("admin1", List.of());
        when(userRepository.findById("prov1")).thenReturn(Optional.of(adminProvider));
        when(placeRepository.findByCreatedBy("prov1")).thenReturn(List.of(place("own", "admin1", "prov1")));
        when(placeRepository.findByAdminId("admin1")).thenReturn(List.of(place("own", "admin1", "prov1"), place("hq", "admin1", "admin1")));

        List<String> ids = access.queuePlacesForProvider("prov1").stream().map(Place::getId).toList();
        assertEquals(List.of("own", "hq"), ids);
    }

    @Test
    void independentProviderQueuePlacesAreOnlyTheirOwn() {
        User independent = provider(null, List.of());
        when(userRepository.findById("prov1")).thenReturn(Optional.of(independent));
        when(placeRepository.findByCreatedBy("prov1")).thenReturn(List.of(place("own", null, "prov1")));

        assertEquals(List.of("own"), access.queuePlacesForProvider("prov1").stream().map(Place::getId).toList());
        verify(placeRepository, never()).findByAdminId(any());
    }

    @Test
    void creatorDetailsFillNamesAndLegacyDefaults() {
        User admin = new User();
        admin.setId("admin1");
        admin.setName("Alex");
        when(userRepository.findAllById(any())).thenReturn(List.of(admin));

        Place legacy = place("p1", "admin1", null); // created before ownership tracking
        List<PlaceDTO> dtos = access.withCreatorDetails(new ArrayList<>(List.of(PlaceDTO.fromEntity(legacy))));
        assertEquals("admin1", dtos.get(0).getCreatedBy());
        assertEquals("ADMIN", dtos.get(0).getCreatedByRole());
        assertEquals("Alex", dtos.get(0).getCreatedByName());

        Service legacyService = Service.builder().id("s1").placeId("p1").build();
        List<ServiceDTO> services = access.withCreatorDetails(new ArrayList<>(List.of(ServiceDTO.fromEntity(legacyService))), List.of(legacy));
        assertEquals("Place p1", services.get(0).getPlaceName());
        assertEquals("admin1", services.get(0).getCreatedBy());
        assertEquals("ADMIN", services.get(0).getCreatedByRole());
        assertEquals("Alex", services.get(0).getCreatedByName());
    }
}
