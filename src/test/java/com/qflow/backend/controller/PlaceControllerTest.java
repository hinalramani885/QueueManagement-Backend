package com.qflow.backend.controller;

import tools.jackson.databind.ObjectMapper;
import com.qflow.backend.config.RateLimitConfig;
import com.qflow.backend.config.TestSecurityConfig;
import com.qflow.backend.dto.PlaceDTO;
import com.qflow.backend.model.Place;
import com.qflow.backend.service.PlaceService;
import com.qflow.backend.service.CatalogAccessService;
import com.qflow.backend.enums.Role;
import com.qflow.backend.model.User;
import com.qflow.backend.repository.PlaceRepository;
import com.qflow.backend.repository.ServiceRepository;
import com.qflow.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlaceController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@Import({RateLimitConfig.class, TestSecurityConfig.class, CatalogAccessService.class, PlaceControllerTest.MethodSecurityConfig.class})
@AutoConfigureMockMvc
class PlaceControllerTest {

    /** Enables @PreAuthorize-based annotations such as @AdminOrProviderOnly in this slice test. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PlaceRepository placeRepository;

    @MockitoBean
    private ServiceRepository serviceRepository;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlaceService placeService;

    @Autowired
    private ObjectMapper objectMapper;

    private final String adminId = "admin123";
    private final String placeId = "place123";
    private final Place testPlace;

    PlaceControllerTest() {
        testPlace = new Place();
        testPlace.setId(placeId);
        testPlace.setName("Test Place");
        testPlace.setType("SHOP");
        testPlace.setAddress("123 Main St");
        testPlace.setLocation(new GeoJsonPoint(10.0, 20.0));
        testPlace.setAdminId(adminId);
        testPlace.setIsActive(true);
    }

    // ==================== CREATE PLACE ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void createPlace_Success() throws Exception {
        PlaceDTO request = PlaceDTO.fromEntity(testPlace);
        request.setAdminId(adminId);

        when(placeService.createPlace(any(PlaceDTO.class), anyString(), anyString())).thenReturn(testPlace);

        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(placeId))
                .andExpect(jsonPath("$.name").value("Test Place"));
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void createPlace_AdminIdMismatch() throws Exception {
        PlaceDTO request = PlaceDTO.fromEntity(testPlace);
        request.setAdminId("otherAdmin"); // mismatched adminId

        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void createPlace_ServiceException() throws Exception {
        PlaceDTO request = PlaceDTO.fromEntity(testPlace);
        request.setAdminId(adminId);

        when(placeService.createPlace(any(PlaceDTO.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }

    // ==================== GET PLACE BY ID ====================

    @Test
    void getPlace_Success() throws Exception {
        when(placeService.getPlaceById(placeId)).thenReturn(testPlace);

        mockMvc.perform(get("/api/places/{id}", placeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(placeId));
    }

    @Test
    void getPlace_NotFound() throws Exception {
        when(placeService.getPlaceById(placeId)).thenThrow(new RuntimeException("Not found"));

        mockMvc.perform(get("/api/places/{id}", placeId))
                .andExpect(status().isNotFound());
    }

    // ==================== GET MY PLACES ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void getMyPlaces_Success() throws Exception {
        when(placeService.getPlacesByAdminId(adminId)).thenReturn(List.of(testPlace));

        mockMvc.perform(get("/api/places/admin/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(placeId));
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void getMyPlaces_Empty() throws Exception {
        when(placeService.getPlacesByAdminId(adminId)).thenReturn(List.of());

        mockMvc.perform(get("/api/places/admin/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ==================== GET PLACES BY ADMIN (PATH VARIABLE) ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void getPlacesByAdmin_Success() throws Exception {
        when(placeService.getPlacesByAdminId(adminId)).thenReturn(List.of(testPlace));

        mockMvc.perform(get("/api/places/admin/{adminId}", adminId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(placeId));
    }

    @Test
    @WithMockUser(username = "otherAdmin", roles = {"ADMIN"})
    void getPlacesByAdmin_Forbidden() throws Exception {
        mockMvc.perform(get("/api/places/admin/{adminId}", adminId))
                .andExpect(status().isForbidden());
    }

    // ==================== GET PLACES BY TYPE ====================

    @Test
    void getPlacesByType_Success() throws Exception {
        when(placeService.getPlacesByType("SHOP")).thenReturn(List.of(testPlace));

        mockMvc.perform(get("/api/places/type/SHOP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SHOP"));
    }

    // ==================== GET NEARBY PLACES ====================

    @Test
    void getNearbyPlaces_Success() throws Exception {
        when(placeService.getNearbyPlaces(10.0, 20.0, 5.0)).thenReturn(List.of(testPlace));

        mockMvc.perform(get("/api/places/nearby")
                        .param("longitude", "10.0")
                        .param("latitude", "20.0")
                        .param("radius", "5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(placeId));
    }

    // ==================== UPDATE PLACE ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void updatePlace_Success() throws Exception {
        PlaceDTO updateRequest = new PlaceDTO();
        updateRequest.setName("Updated Place");
        updateRequest.setType("RESTAURANT");
        updateRequest.setAddress("456 New St");
        updateRequest.setLocation(new double[]{30.0, 40.0});
        updateRequest.setAdminId(adminId);
        updateRequest.setIsActive(false);

        Place updatedPlace = new Place();
        updatedPlace.setId(placeId);
        updatedPlace.setName("Updated Place");
        updatedPlace.setType("RESTAURANT");
        updatedPlace.setAddress("456 New St");
        updatedPlace.setLocation(new GeoJsonPoint(30.0, 40.0));
        updatedPlace.setAdminId(adminId);
        updatedPlace.setIsActive(false);

        when(placeService.isPlaceOwnedByAdmin(placeId, adminId)).thenReturn(true);
        when(placeService.updatePlace(eq(placeId), any(PlaceDTO.class))).thenReturn(updatedPlace);

        mockMvc.perform(put("/api/places/{id}", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Place"))
                .andExpect(jsonPath("$.type").value("RESTAURANT"));
    }

    @Test
    @WithMockUser(username = "otherAdmin", roles = {"ADMIN"})
    void updatePlace_Forbidden() throws Exception {
        PlaceDTO updateRequest = new PlaceDTO();
        updateRequest.setName("Updated Place");
        updateRequest.setType("RESTAURANT");
        updateRequest.setAddress("456 New St");
        updateRequest.setLocation(new double[]{30.0, 40.0});
        updateRequest.setAdminId(adminId);
        updateRequest.setIsActive(false);

        when(placeService.isPlaceOwnedByAdmin(placeId, "otherAdmin")).thenReturn(false);

        mockMvc.perform(put("/api/places/{id}", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void updatePlace_ServiceException() throws Exception {
        PlaceDTO updateRequest = new PlaceDTO();
        updateRequest.setName("Updated Place");
        updateRequest.setType("RESTAURANT");
        updateRequest.setAddress("456 New St");
        updateRequest.setLocation(new double[]{30.0, 40.0});
        updateRequest.setAdminId(adminId);
        updateRequest.setIsActive(false);

        when(placeService.isPlaceOwnedByAdmin(placeId, adminId)).thenReturn(true);
        when(placeService.updatePlace(eq(placeId), any(PlaceDTO.class)))
                .thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(put("/api/places/{id}", placeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isInternalServerError());
    }

    // ==================== DELETE PLACE ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void deletePlace_Success() throws Exception {
        when(placeService.isPlaceOwnedByAdmin(placeId, adminId)).thenReturn(true);

        mockMvc.perform(delete("/api/places/{id}", placeId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "otherAdmin", roles = {"ADMIN"})
    void deletePlace_Forbidden() throws Exception {
        when(placeService.isPlaceOwnedByAdmin(placeId, "otherAdmin")).thenReturn(false);

        mockMvc.perform(delete("/api/places/{id}", placeId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void deletePlace_ServiceException() throws Exception {
        when(placeService.isPlaceOwnedByAdmin(placeId, adminId)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(placeService).deletePlace(placeId);

        mockMvc.perform(delete("/api/places/{id}", placeId))
                .andExpect(status().isInternalServerError());
    }

    // ==================== GET ALL PLACES ====================

    @Test
    void getAllPlaces_Success() throws Exception {
        when(placeService.getAllPlaces()).thenReturn(List.of(testPlace));

        mockMvc.perform(get("/api/places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(placeId));
    }

    // ==================== GET PAGINATED PLACES ====================

    @Test
    void getPlacesPaginated_Success() throws Exception {
        Page<Place> page = new PageImpl<>(List.of(testPlace), PageRequest.of(0, 20), 1);
        when(placeService.getAllPlacesPaginated(any())).thenReturn(page);

        mockMvc.perform(get("/api/places/paginated")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(placeId));
    }

    // ==================== GET TOP RATED PLACES ====================

    @Test
    void getTopRatedPlaces_Success() throws Exception {
        when(placeService.getTopRatedPlaces(3)).thenReturn(List.of(testPlace));

        mockMvc.perform(get("/api/places/top-rated")
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(placeId));
    }

    // ==================== PROVIDER CATALOG (places created by providers) ====================

    private static final String providerId = "provider1";

    private User provider(String id, String admin, List<String> managed) {
        User u = new User();
        u.setId(id);
        u.setName("Priya Provider");
        u.setRole(Role.PROVIDER);
        u.setAdminId(admin);
        u.setManagedPlaceIds(new java.util.ArrayList<>(managed));
        return u;
    }

    private Place placeCreatedBy(String id, String creator, String creatorRole) {
        Place p = new Place();
        p.setId(id);
        p.setName("Provider Clinic");
        p.setType("CLINIC");
        p.setAddress("1 Side St");
        p.setLocation(new GeoJsonPoint(10.0, 20.0));
        p.setAdminId(adminId);
        p.setIsActive(true);
        p.setCreatedBy(creator);
        p.setCreatedByRole(creatorRole);
        return p;
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCreatesPlace_OwnedByTheirAdmin() throws Exception {
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(providerId, adminId, List.of())));
        Place created = placeCreatedBy("newPlace", providerId, "PROVIDER");
        when(placeService.createPlace(any(PlaceDTO.class), eq(providerId), eq("PROVIDER"))).thenReturn(created);

        PlaceDTO request = PlaceDTO.fromEntity(created);
        request.setId(null);
        request.setAdminId(null); // the server decides the owning admin

        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("newPlace"))
                .andExpect(jsonPath("$.adminId").value(adminId))
                .andExpect(jsonPath("$.createdBy").value(providerId))
                .andExpect(jsonPath("$.createdByRole").value("PROVIDER"));

        org.mockito.Mockito.verify(placeService).createPlace(
                org.mockito.ArgumentMatchers.argThat(dto -> adminId.equals(dto.getAdminId())), eq(providerId), eq("PROVIDER"));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void independentProviderCreatesPlaceAndItIsLinkedToThem() throws Exception {
        // Signed up via "Be a Provider": no admin and no place yet
        User independent = provider(providerId, null, List.of());
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(independent));
        Place created = placeCreatedBy("firmPlace", providerId, "PROVIDER");
        created.setAdminId(null);
        when(placeService.createPlace(any(PlaceDTO.class), eq(providerId), eq("PROVIDER"))).thenReturn(created);

        PlaceDTO request = PlaceDTO.fromEntity(created);
        request.setId(null);

        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("firmPlace"))
                .andExpect(jsonPath("$.createdBy").value(providerId));

        org.mockito.Mockito.verify(placeService).createPlace(
                org.mockito.ArgumentMatchers.argThat(dto -> dto.getAdminId() == null), eq(providerId), eq("PROVIDER"));
        org.junit.jupiter.api.Assertions.assertEquals("firmPlace", independent.getPlaceId());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("firmPlace"), independent.getOwnedPlaceIds());
        org.mockito.Mockito.verify(userRepository).save(independent);
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void userCannotCreatePlace() throws Exception {
        PlaceDTO request = PlaceDTO.fromEntity(testPlace);
        mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerUpdatesOwnPlace() throws Exception {
        Place own = placeCreatedBy("ownPlace", providerId, "PROVIDER");
        when(placeRepository.findById("ownPlace")).thenReturn(java.util.Optional.of(own));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(providerId, adminId, List.of())));
        when(placeService.updatePlace(eq("ownPlace"), any(PlaceDTO.class))).thenReturn(own);

        mockMvc.perform(put("/api/places/ownPlace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PlaceDTO.fromEntity(own))))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerUpdatesAssignedPlace() throws Exception {
        Place adminPlace = placeCreatedBy("assigned", adminId, "ADMIN");
        when(placeRepository.findById("assigned")).thenReturn(java.util.Optional.of(adminPlace));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(providerId, adminId, List.of("assigned"))));
        when(placeService.updatePlace(eq("assigned"), any(PlaceDTO.class))).thenReturn(adminPlace);

        mockMvc.perform(put("/api/places/assigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PlaceDTO.fromEntity(adminPlace))))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCannotUpdateAnotherProvidersPlace() throws Exception {
        Place other = placeCreatedBy("otherPlace", "provider2", "PROVIDER");
        when(placeRepository.findById("otherPlace")).thenReturn(java.util.Optional.of(other));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(providerId, adminId, List.of())));

        mockMvc.perform(put("/api/places/otherPlace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PlaceDTO.fromEntity(other))))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(placeService, org.mockito.Mockito.never()).updatePlace(anyString(), any(PlaceDTO.class));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCannotDeleteAnotherProvidersPlace() throws Exception {
        when(placeRepository.findById("otherPlace")).thenReturn(java.util.Optional.of(placeCreatedBy("otherPlace", "provider2", "PROVIDER")));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(providerId, adminId, List.of())));

        mockMvc.perform(delete("/api/places/otherPlace")).andExpect(status().isForbidden());
        org.mockito.Mockito.verify(placeService, org.mockito.Mockito.never()).deletePlace(anyString());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerDeleteUnknownPlace_NotFound() throws Exception {
        when(placeRepository.findById("missing")).thenReturn(java.util.Optional.empty());
        mockMvc.perform(delete("/api/places/missing")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void adminSeesProviderCreatedPlacesWithCreator() throws Exception {
        Place providerPlace = placeCreatedBy("provPlace", providerId, "PROVIDER");
        when(placeService.getPlacesByAdminId(adminId)).thenReturn(List.of(testPlace, providerPlace));
        User admin = new User();
        admin.setId(adminId);
        admin.setName("Alex Admin");
        when(userRepository.findAllById(any())).thenReturn(List.of(admin, provider(providerId, adminId, List.of())));

        mockMvc.perform(get("/api/places/admin/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].createdByRole").value("ADMIN"))
                .andExpect(jsonPath("$[0].createdByName").value("Alex Admin"))
                .andExpect(jsonPath("$[1].createdBy").value(providerId))
                .andExpect(jsonPath("$[1].createdByRole").value("PROVIDER"))
                .andExpect(jsonPath("$[1].createdByName").value("Priya Provider"));
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void adminCanDeleteProviderCreatedPlaceInTheirScope() throws Exception {
        when(placeService.isPlaceOwnedByAdmin("provPlace", adminId)).thenReturn(true);
        mockMvc.perform(delete("/api/places/provPlace")).andExpect(status().isOk());
        org.mockito.Mockito.verify(placeService).deletePlace("provPlace");
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerListsCreatedAndAssignedPlaces() throws Exception {
        when(placeRepository.findByCreatedBy(providerId)).thenReturn(List.of(placeCreatedBy("ownPlace", providerId, "PROVIDER")));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(providerId, adminId, List.of("assigned"))));
        when(placeRepository.findAllById(any())).thenReturn(List.of(placeCreatedBy("assigned", adminId, "ADMIN")));

        mockMvc.perform(get("/api/places/provider/my-places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("ownPlace"))
                .andExpect(jsonPath("$[1].id").value("assigned"));
    }
}
