package com.qflow.backend.service;

import com.qflow.backend.dto.ContentPageDTO;
import com.qflow.backend.exception.ResourceNotFoundException;
import com.qflow.backend.model.ContentPage;
import com.qflow.backend.model.User;
import com.qflow.backend.repository.ContentPageRepository;
import com.qflow.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentPageServiceTest {

    @Mock
    private ContentPageRepository repository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ContentPageService service;

    private ContentPageDTO dto(String title, ContentPageDTO.SectionDTO... sections) {
        ContentPageDTO d = new ContentPageDTO();
        d.setTitle(title);
        d.setSubtitle("  sub  ");
        d.setSections(List.of(sections));
        return d;
    }

    @Test
    void getReturns404WhenNotCustomised() {
        when(repository.findBySlug("privacy-policy")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getPage("privacy-policy"));
    }

    @Test
    void unknownSlugIsRejected() {
        assertThrows(ResourceNotFoundException.class, () -> service.getPage("../users"));
        assertThrows(ResourceNotFoundException.class, () -> service.savePage("anything", dto("X"), "admin1"));
        verifyNoInteractions(repository);
    }

    @Test
    void saveCreatesPageWithAuthorAndCleanSectionIds() {
        when(repository.findBySlug("privacy-policy")).thenReturn(Optional.empty());
        when(repository.save(any(ContentPage.class))).thenAnswer(inv -> inv.getArgument(0));
        User admin = new User();
        admin.setId("admin1");
        admin.setName("Alex Admin");
        when(userRepository.findById("admin1")).thenReturn(Optional.of(admin));

        ContentPageDTO saved = service.savePage("Privacy-Policy", dto(" Privacy Policy ",
                new ContentPageDTO.SectionDTO(null, "Data Security", "We **hash** passwords."),
                new ContentPageDTO.SectionDTO("data-security", "Data Security again", "dup id"),
                new ContentPageDTO.SectionDTO("", "!!!", "no usable title")), "admin1");

        assertEquals("privacy-policy", saved.getSlug());
        assertEquals("Privacy Policy", saved.getTitle());
        assertEquals("sub", saved.getSubtitle());
        assertEquals(List.of("data-security", "data-security-2", "section-3"),
                saved.getSections().stream().map(ContentPageDTO.SectionDTO::getId).toList());
        assertEquals("We **hash** passwords.", saved.getSections().get(0).getBody());
        assertEquals("Alex Admin", saved.getUpdatedByName());
        assertNotNull(saved.getUpdatedAt());
        verify(auditLogService).logEvent(eq("CONTENT_PAGE_UPDATED"), anyString(), argThat(d -> "privacy-policy".equals(d.get("slug"))));
    }

    @Test
    void saveReplacesExistingPage() {
        ContentPage existing = ContentPage.builder().id("p1").slug("documentation").title("Old").build();
        when(repository.findBySlug("documentation")).thenReturn(Optional.of(existing));
        when(repository.save(any(ContentPage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.savePage("documentation", dto("New", new ContentPageDTO.SectionDTO("intro", "Intro", "Hi")), "admin1");

        verify(repository).save(argThat(p -> "p1".equals(p.getId()) && "New".equals(p.getTitle()) && p.getSections().size() == 1));
    }

    @Test
    void deleteRemovesSavedPage() {
        when(repository.findBySlug("about-us")).thenReturn(Optional.of(ContentPage.builder().slug("about-us").build()));
        service.deletePage("about-us");
        verify(repository).deleteBySlug("about-us");
        verify(auditLogService).logEvent(eq("CONTENT_PAGE_DELETED"), anyString(), anyMap());
    }

    @Test
    void deleteWithoutSavedPageIs404() {
        when(repository.findBySlug("about-us")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.deletePage("about-us"));
        verify(repository, never()).deleteBySlug(anyString());
    }
}
