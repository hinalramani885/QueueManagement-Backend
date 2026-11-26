package com.qflow.backend.controller;

import tools.jackson.databind.ObjectMapper;
import com.qflow.backend.config.RateLimitConfig;
import com.qflow.backend.config.TestSecurityConfig;
import com.qflow.backend.dto.ContentPageDTO;
import com.qflow.backend.exception.ResourceNotFoundException;
import com.qflow.backend.service.ContentPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ContentPageController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@Import({RateLimitConfig.class, TestSecurityConfig.class, ContentPageControllerTest.MethodSecurityConfig.class})
@AutoConfigureMockMvc
class ContentPageControllerTest {

    /** Enables @PreAuthorize-based annotations such as @AdminOnly in this slice test. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ContentPageService contentPageService;

    private ContentPageDTO page() {
        ContentPageDTO p = new ContentPageDTO();
        p.setTitle("Privacy Policy");
        p.setSections(List.of(new ContentPageDTO.SectionDTO("data", "Data", "Body")));
        return p;
    }

    @Test
    void anyoneCanReadAPage() throws Exception {
        ContentPageDTO saved = page();
        saved.setSlug("privacy-policy");
        when(contentPageService.getPage("privacy-policy")).thenReturn(saved);

        mockMvc.perform(get("/api/content/privacy-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Privacy Policy"))
                .andExpect(jsonPath("$.sections[0].body").value("Body"));
    }

    @Test
    void notCustomisedPageIs404() throws Exception {
        when(contentPageService.getPage("documentation")).thenThrow(new ResourceNotFoundException("none"));
        mockMvc.perform(get("/api/content/documentation")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    void adminSavesPage() throws Exception {
        when(contentPageService.savePage(eq("privacy-policy"), any(ContentPageDTO.class), eq("admin1"))).thenReturn(page());

        mockMvc.perform(put("/api/content/privacy-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(page())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Privacy Policy"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    void saveValidatesInput() throws Exception {
        ContentPageDTO bad = page();
        bad.setTitle(" ");
        mockMvc.perform(put("/api/content/privacy-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
        verify(contentPageService, never()).savePage(anyString(), any(), anyString());
    }

    @Test
    @WithMockUser(username = "prov1", roles = {"PROVIDER"})
    void nonAdminCannotSave() throws Exception {
        mockMvc.perform(put("/api/content/privacy-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(page())))
                .andExpect(status().isForbidden());
        verify(contentPageService, never()).savePage(anyString(), any(), anyString());
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    void adminDeletesPage() throws Exception {
        mockMvc.perform(delete("/api/content/about-us")).andExpect(status().isNoContent());
        verify(contentPageService).deletePage("about-us");
    }

    @Test
    @WithMockUser(username = "u1", roles = {"USER"})
    void userCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/content/about-us")).andExpect(status().isForbidden());
        verify(contentPageService, never()).deletePage(anyString());
    }
}
