package com.qflow.backend.controller;

import com.qflow.backend.config.RateLimitConfig;
import com.qflow.backend.config.TestSecurityConfig;
import com.qflow.backend.model.Place;
import com.qflow.backend.model.Service;
import com.qflow.backend.model.User;
import com.qflow.backend.repository.UserRepository;
import com.qflow.backend.repository.ServiceRepository;
import com.qflow.backend.repository.PlaceRepository;
import com.qflow.backend.service.CatalogAccessService;
import com.qflow.backend.service.PlaceService;
import com.qflow.backend.service.ProviderAnalyticsService;
import com.qflow.backend.service.ServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProviderController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@Import({RateLimitConfig.class, TestSecurityConfig.class, CatalogAccessService.class})
@AutoConfigureMockMvc
class ProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PlaceService placeService;

    @MockitoBean
    private ServiceService serviceService;

    @MockitoBean
    private ProviderAnalyticsService providerAnalyticsService;

    @MockitoBean
    private PlaceRepository placeRepository;

    @MockitoBean
    private ServiceRepository serviceRepository;

    private final String providerId = "provider123";
    private final String adminId = "admin123";
    private final String placeId1 = "place1";
    private final String placeId2 = "place2";
    private final String serviceId1 = "service1";
    private final String serviceId2 = "service2";

    private User createProvider(List<String> managedPlaceIds) {
        return User.builder()
                .id(providerId)
                .name("Test Provider")
                .email("provider@test.com")
                .adminId(adminId)
                .managedPlaceIds(managedPlaceIds)
                .build();
    }

    private Place createPlace(String id, String name) {
        Place place = new Place();
        place.setId(id);
        place.setName(name);
        place.setType("SHOP");
        place.setAddress("Address");
        place.setAdminId(adminId);
        place.setIsActive(true);
        return place;
    }

    private Service createService(String id, String placeId, String name) {
        return Service.builder()
                .id(id)
                .placeId(placeId)
                .name(name)
                .averageServiceTime(10)
                .isActive(true)
                .build();
    }

    // ==================== GET MY PLACES ====================

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getMyManagedPlaces_WithManagedPlaceIds_Success() throws Exception {
        User provider = createProvider(List.of(placeId1, placeId2));
        when(userRepository.findById(providerId)).thenReturn(Optional.of(provider));

        Place place1 = createPlace(placeId1, "Place 1");
        Place place2 = createPlace(placeId2, "Place 2");
        when(placeRepository.findAllById(any())).thenReturn(List.of(place1, place2));

        mockMvc.perform(get("/api/providers/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(placeId1))
                .andExpect(jsonPath("$[1].id").value(placeId2))
                .andExpect(jsonPath("$[0].name").value("Place 1"))
                .andExpect(jsonPath("$[1].name").value("Place 2"));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getMyManagedPlaces_WithoutManagedPlaceIds_Success() throws Exception {
        User provider = createProvider(null); // no managedPlaceIds
        when(userRepository.findById(providerId)).thenReturn(Optional.of(provider));

        Place place1 = createPlace(placeId1, "Place 1");
        when(placeRepository.findByAdminId(adminId)).thenReturn(List.of(place1));

        mockMvc.perform(get("/api/providers/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(placeId1))
                .andExpect(jsonPath("$[0].name").value("Place 1"));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getMyManagedPlaces_ProviderNotFound() throws Exception {
        when(userRepository.findById(providerId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/providers/my-places"))
                .andExpect(status().isBadRequest()); // was is5xxServerError
    }

    // ==================== GET MY SERVICES ====================

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getMyManagedServices_WithManagedPlaceIds_Success() throws Exception {
        User provider = createProvider(List.of(placeId1, placeId2));
        when(userRepository.findById(providerId)).thenReturn(Optional.of(provider));

        when(placeRepository.findAllById(any())).thenReturn(List.of(createPlace(placeId1, "Place 1"), createPlace(placeId2, "Place 2")));
        Service service1 = createService(serviceId1, placeId1, "Service 1");
        Service service2 = createService(serviceId2, placeId2, "Service 2");
        when(serviceService.getServicesByPlaceId(placeId1)).thenReturn(List.of(service1));
        when(serviceService.getServicesByPlaceId(placeId2)).thenReturn(List.of(service2));

        mockMvc.perform(get("/api/providers/my-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(serviceId1))
                .andExpect(jsonPath("$[1].id").value(serviceId2))
                .andExpect(jsonPath("$[0].name").value("Service 1"))
                .andExpect(jsonPath("$[1].name").value("Service 2"));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getMyManagedServices_WithoutManagedPlaceIds_Success() throws Exception {
        User provider = createProvider(null);
        when(userRepository.findById(providerId)).thenReturn(Optional.of(provider));

        Place place1 = createPlace(placeId1, "Place 1");
        when(placeRepository.findByAdminId(adminId)).thenReturn(List.of(place1));

        Service service1 = createService(serviceId1, placeId1, "Service 1");
        when(serviceService.getServicesByPlaceId(placeId1)).thenReturn(List.of(service1));

        mockMvc.perform(get("/api/providers/my-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(serviceId1));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getMyManagedServices_ProviderNotFound() throws Exception {
        when(userRepository.findById(providerId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/providers/my-services"))
                .andExpect(status().isBadRequest()); // was is5xxServerError
    }

    // ==================== ANALYTICS ====================

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getTokensOverTime_Success() throws Exception {
        Map<String, Object> data = Map.of(
                "dates", List.of("2026-03-01", "2026-03-02"),
                "counts", List.of(5, 8)
        );
        when(providerAnalyticsService.getTokensOverTime(providerId, 30)).thenReturn(data);

        mockMvc.perform(get("/api/providers/analytics/tokens-over-time")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-03-01"))
                .andExpect(jsonPath("$.counts[1]").value(8));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getBusiestHours_Success() throws Exception {
        Map<Integer, Double> data = Map.of(
                9, 12.5,
                10, 15.2,
                11, 18.0
        );
        when(providerAnalyticsService.getBusiestHours(providerId)).thenReturn(data);

        mockMvc.perform(get("/api/providers/analytics/busiest-hours"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.9").value(12.5))
                .andExpect(jsonPath("$.10").value(15.2));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void getAverageWaitTimeTrend_Success() throws Exception {
        Map<String, Object> data = Map.of(
                "dates", List.of("2026-03-01", "2026-03-02"),
                "averages", List.of(10.5, 12.3)
        );
        when(providerAnalyticsService.getAverageWaitTimeTrend(providerId, 30)).thenReturn(data);

        mockMvc.perform(get("/api/providers/analytics/average-wait-time")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-03-01"))
                .andExpect(jsonPath("$.averages[1]").value(12.3));
    }

    // ==================== providers who signed up without a place ====================

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void independentProviderSeesOnlyTheirOwnPlaceForQueues() throws Exception {
        User independent = User.builder().id(providerId).name("Indie").email("indie@test.com").build(); // no admin, no place
        when(userRepository.findById(providerId)).thenReturn(Optional.of(independent));
        Place own = createPlace(placeId1, "My Salon");
        own.setCreatedBy(providerId);
        when(placeRepository.findByCreatedBy(providerId)).thenReturn(List.of(own));

        mockMvc.perform(get("/api/providers/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("My Salon"));
        org.mockito.Mockito.verify(placeRepository, org.mockito.Mockito.never()).findByAdminId(any());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void newProviderWithoutPlaceGetsEmptyList() throws Exception {
        when(userRepository.findById(providerId)).thenReturn(Optional.of(User.builder().id(providerId).build()));
        mockMvc.perform(get("/api/providers/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
