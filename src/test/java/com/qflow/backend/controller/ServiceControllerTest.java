package com.qflow.backend.controller;

import tools.jackson.databind.ObjectMapper;
import com.qflow.backend.config.RateLimitConfig;
import com.qflow.backend.config.TestSecurityConfig;
import com.qflow.backend.dto.ServiceDTO;
import com.qflow.backend.model.Service;
import com.qflow.backend.service.PlaceService;
import com.qflow.backend.service.ServiceService;
import com.qflow.backend.service.CatalogAccessService;
import com.qflow.backend.enums.Role;
import com.qflow.backend.model.Place;
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

@WebMvcTest(controllers = ServiceController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@Import({RateLimitConfig.class, TestSecurityConfig.class, CatalogAccessService.class, ServiceControllerTest.MethodSecurityConfig.class})
@AutoConfigureMockMvc
class ServiceControllerTest {

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
    private ServiceService serviceService;

    @MockitoBean
    private PlaceService placeService;

    @Autowired
    private ObjectMapper objectMapper;

    private final String adminId = "admin123";
    private final String placeId = "place123";
    private final String serviceId = "service123";

    private Service createTestService() {
        return Service.builder()
                .id(serviceId)
                .placeId(placeId)
                .name("Test Service")
                .description("Description")
                .averageServiceTime(10)
                .supportsGroupToken(true)
                .emergencySupport(false)
                .isActive(true)
                .build();
    }

    private ServiceDTO createTestServiceDTO() {
        ServiceDTO dto = new ServiceDTO();
        dto.setPlaceId(placeId);
        dto.setName("Test Service");
        dto.setDescription("Description");
        dto.setAverageServiceTime(10);
        dto.setSupportsGroupToken(true);
        dto.setEmergencySupport(false);
        dto.setIsActive(true);
        return dto;
    }

    // ==================== CREATE SERVICE ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void createService_Success() throws Exception {
        ServiceDTO request = createTestServiceDTO();
        Service service = createTestService();

        when(placeService.isPlaceOwnedByAdmin(placeId, adminId)).thenReturn(true);
        when(serviceService.createService(any(ServiceDTO.class), anyString(), anyString(), anyString())).thenReturn(service);

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serviceId))
                .andExpect(jsonPath("$.name").value("Test Service"));
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void createService_PlaceNotOwned() throws Exception {
        ServiceDTO request = createTestServiceDTO();

        when(placeService.isPlaceOwnedByAdmin(placeId, adminId)).thenReturn(false);

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void createService_ServiceException() throws Exception {
        ServiceDTO request = createTestServiceDTO();

        when(placeService.isPlaceOwnedByAdmin(placeId, adminId)).thenReturn(true);
        when(serviceService.createService(any(ServiceDTO.class), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }

    // ==================== GET SERVICE BY ID ====================

    @Test
    void getService_Success() throws Exception {
        Service service = createTestService();
        when(serviceService.getServiceById(serviceId)).thenReturn(service);

        mockMvc.perform(get("/api/services/{id}", serviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serviceId))
                .andExpect(jsonPath("$.name").value("Test Service"));
    }

    @Test
    void getService_NotFound() throws Exception {
        when(serviceService.getServiceById(serviceId)).thenThrow(new RuntimeException("Not found"));

        mockMvc.perform(get("/api/services/{id}", serviceId))
                .andExpect(status().isNotFound());
    }

    // ==================== GET SERVICES BY PLACE ====================

    @Test
    void getServicesByPlace_Success() throws Exception {
        Service service = createTestService();
        when(serviceService.getServicesByPlaceId(placeId)).thenReturn(List.of(service));

        mockMvc.perform(get("/api/services/place/{placeId}", placeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(serviceId))
                .andExpect(jsonPath("$[0].name").value("Test Service"));
    }

    @Test
    void getServicesByPlace_Empty() throws Exception {
        when(serviceService.getServicesByPlaceId(placeId)).thenReturn(List.of());

        mockMvc.perform(get("/api/services/place/{placeId}", placeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ==================== UPDATE SERVICE ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void updateService_Success() throws Exception {
        ServiceDTO request = createTestServiceDTO();
        request.setName("Updated Name");

        Service updatedService = createTestService();
        updatedService.setName("Updated Name");

        when(serviceService.isServiceOwnedByAdmin(serviceId, adminId)).thenReturn(true);
        when(serviceService.updateService(eq(serviceId), any(ServiceDTO.class))).thenReturn(updatedService);

        mockMvc.perform(put("/api/services/{id}", serviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void updateService_Forbidden() throws Exception {
        ServiceDTO request = createTestServiceDTO();

        when(serviceService.isServiceOwnedByAdmin(serviceId, adminId)).thenReturn(false);

        mockMvc.perform(put("/api/services/{id}", serviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void updateService_NotFound() throws Exception {
        ServiceDTO request = createTestServiceDTO();

        when(serviceService.isServiceOwnedByAdmin(serviceId, adminId)).thenReturn(true);
        when(serviceService.updateService(eq(serviceId), any(ServiceDTO.class)))
                .thenThrow(new RuntimeException("Not found"));

        mockMvc.perform(put("/api/services/{id}", serviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError()); // controller returns 500 for any exception
    }

    // ==================== DELETE SERVICE ====================

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void deleteService_Success() throws Exception {
        when(serviceService.isServiceOwnedByAdmin(serviceId, adminId)).thenReturn(true);

        mockMvc.perform(delete("/api/services/{id}", serviceId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void deleteService_Forbidden() throws Exception {
        when(serviceService.isServiceOwnedByAdmin(serviceId, adminId)).thenReturn(false);

        mockMvc.perform(delete("/api/services/{id}", serviceId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void deleteService_NotFound() throws Exception {
        when(serviceService.isServiceOwnedByAdmin(serviceId, adminId)).thenReturn(true);
        doThrow(new RuntimeException("Not found")).when(serviceService).deleteService(serviceId);

        mockMvc.perform(delete("/api/services/{id}", serviceId))
                .andExpect(status().isInternalServerError());
    }

    // ==================== GET ALL SERVICES ====================

    @Test
    void getAllServices_Success() throws Exception {
        Service service = createTestService();
        when(serviceService.getAllServices()).thenReturn(List.of(service));

        mockMvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(serviceId))
                .andExpect(jsonPath("$[0].name").value("Test Service"));
    }

    // ==================== PROVIDER CATALOG (services created by providers) ====================

    private static final String providerId = "provider1";

    private User provider(List<String> managed) {
        User u = new User();
        u.setId(providerId);
        u.setName("Priya Provider");
        u.setRole(Role.PROVIDER);
        u.setAdminId(adminId);
        u.setManagedPlaceIds(new java.util.ArrayList<>(managed));
        return u;
    }

    private Place place(String id, String createdBy) {
        Place p = new Place();
        p.setId(id);
        p.setName("Place " + id);
        p.setAdminId(adminId);
        p.setCreatedBy(createdBy);
        p.setCreatedByRole(adminId.equals(createdBy) ? "ADMIN" : "PROVIDER");
        return p;
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCreatesServiceUnderOwnPlace() throws Exception {
        when(placeRepository.findById(placeId)).thenReturn(java.util.Optional.of(place(placeId, providerId)));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(List.of())));
        Service created = createTestService();
        created.setCreatedBy(providerId);
        created.setCreatedByRole("PROVIDER");
        created.setAdminId(adminId);
        when(serviceService.createService(any(ServiceDTO.class), eq(providerId), eq("PROVIDER"), eq(adminId))).thenReturn(created);

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTestServiceDTO())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value(providerId))
                .andExpect(jsonPath("$.createdByRole").value("PROVIDER"))
                .andExpect(jsonPath("$.adminId").value(adminId));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCannotCreateServiceUnderOtherPlace() throws Exception {
        when(placeRepository.findById(placeId)).thenReturn(java.util.Optional.of(place(placeId, "provider2")));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(List.of())));

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTestServiceDTO())))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(serviceService, org.mockito.Mockito.never())
                .createService(any(ServiceDTO.class), anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCreatesServiceUnderAssignedPlace() throws Exception {
        when(placeRepository.findById(placeId)).thenReturn(java.util.Optional.of(place(placeId, adminId)));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(List.of(placeId))));
        when(serviceService.createService(any(ServiceDTO.class), eq(providerId), eq("PROVIDER"), eq(adminId)))
                .thenReturn(createTestService());

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTestServiceDTO())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCreateServiceUnknownPlace_NotFound() throws Exception {
        when(placeRepository.findById(placeId)).thenReturn(java.util.Optional.empty());
        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTestServiceDTO())))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerUpdatesOwnService() throws Exception {
        Service own = createTestService();
        own.setCreatedBy(providerId);
        when(serviceRepository.findById(serviceId)).thenReturn(java.util.Optional.of(own));
        when(placeRepository.findById(placeId)).thenReturn(java.util.Optional.of(place(placeId, adminId)));
        when(serviceService.updateService(eq(serviceId), any(ServiceDTO.class))).thenReturn(own);

        mockMvc.perform(put("/api/services/" + serviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTestServiceDTO())))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCannotDeleteServiceOfAnotherProvidersPlace() throws Exception {
        Service other = createTestService();
        other.setCreatedBy("provider2");
        when(serviceRepository.findById(serviceId)).thenReturn(java.util.Optional.of(other));
        when(placeRepository.findById(placeId)).thenReturn(java.util.Optional.of(place(placeId, "provider2")));
        when(userRepository.findById(providerId)).thenReturn(java.util.Optional.of(provider(List.of())));

        mockMvc.perform(delete("/api/services/" + serviceId)).andExpect(status().isForbidden());
        org.mockito.Mockito.verify(serviceService, org.mockito.Mockito.never()).deleteService(anyString());
    }

    @Test
    @WithMockUser(username = adminId, roles = {"ADMIN"})
    void adminSeesProviderCreatedServicesWithCreatorAndPlace() throws Exception {
        Place p = place(placeId, providerId);
        when(placeService.getPlacesByAdminId(adminId)).thenReturn(List.of(p));
        Service byProvider = createTestService();
        byProvider.setCreatedBy(providerId);
        byProvider.setCreatedByRole("PROVIDER");
        byProvider.setAdminId(adminId);
        Service legacy = createTestService();
        legacy.setId("legacy");
        when(serviceService.getServicesByPlaceIds(List.of(placeId))).thenReturn(List.of(byProvider, legacy));
        User admin = new User();
        admin.setId(adminId);
        admin.setName("Alex Admin");
        when(userRepository.findAllById(any())).thenReturn(List.of(provider(List.of()), admin));

        mockMvc.perform(get("/api/services/admin/my-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].createdByRole").value("PROVIDER"))
                .andExpect(jsonPath("$[0].createdByName").value("Priya Provider"))
                .andExpect(jsonPath("$[0].placeName").value("Place " + placeId))
                .andExpect(jsonPath("$[1].createdByRole").value("ADMIN"))
                .andExpect(jsonPath("$[1].createdBy").value(adminId))
                .andExpect(jsonPath("$[1].createdByName").value("Alex Admin"));
    }

    @Test
    @WithMockUser(username = providerId, roles = {"PROVIDER"})
    void providerCannotUseAdminServiceList() throws Exception {
        mockMvc.perform(get("/api/services/admin/my-services")).andExpect(status().isForbidden());
    }
}
