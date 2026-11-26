package com.qflow.backend.service;

import com.qflow.backend.dto.ContentPageDTO;
import com.qflow.backend.exception.ResourceNotFoundException;
import com.qflow.backend.model.ContentPage;
import com.qflow.backend.model.User;
import com.qflow.backend.repository.ContentPageRepository;
import com.qflow.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stores admin edits of the information pages (privacy policy, terms, documentation, about, how to use). */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentPageService {

    /** Pages that can be edited. Anything else is rejected, so admins cannot create arbitrary documents. */
    public static final Set<String> EDITABLE_SLUGS = Set.of(
            "privacy-policy", "terms-of-service", "documentation", "about-us", "how-to-use");

    private final ContentPageRepository contentPageRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public static String normaliseSlug(String slug) {
        String s = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (!EDITABLE_SLUGS.contains(s)) {
            throw new ResourceNotFoundException("Unknown page: " + slug);
        }
        return s;
    }

    /** The saved page, or a 404 when the admin has not customised it (the frontend then shows its default). */
    public ContentPageDTO getPage(String slug) {
        String s = normaliseSlug(slug);
        return contentPageRepository.findBySlug(s)
                .map(ContentPageDTO::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("Page has no saved content: " + s));
    }

    /** Creates or replaces the saved page. Sections keep their order; missing/duplicate ids are fixed up. */
    public ContentPageDTO savePage(String slug, ContentPageDTO dto, String adminId) {
        String s = normaliseSlug(slug);
        ContentPage page = contentPageRepository.findBySlug(s).orElseGet(() -> ContentPage.builder()
                .slug(s)
                .createdAt(LocalDateTime.now())
                .build());

        page.setTitle(dto.getTitle().trim());
        page.setSubtitle(dto.getSubtitle() == null || dto.getSubtitle().isBlank() ? null : dto.getSubtitle().trim());
        page.setSections(toSections(dto.getSections()));
        page.setUpdatedAt(LocalDateTime.now());
        page.setUpdatedBy(adminId);
        page.setUpdatedByName(userRepository.findById(adminId).map(User::getName).orElse(null));

        ContentPage saved = contentPageRepository.save(page);
        log.info("Content page '{}' saved by {} ({} sections)", s, adminId, saved.getSections().size());
        auditLogService.logEvent("CONTENT_PAGE_UPDATED", "Page updated: " + s, details(s, saved.getSections().size()));
        return ContentPageDTO.fromEntity(saved);
    }

    /** Deletes the saved page, so the built-in default text is shown again. */
    public void deletePage(String slug) {
        String s = normaliseSlug(slug);
        if (contentPageRepository.findBySlug(s).isEmpty()) {
            throw new ResourceNotFoundException("Page has no saved content: " + s);
        }
        contentPageRepository.deleteBySlug(s);
        log.info("Content page '{}' deleted (back to default)", s);
        auditLogService.logEvent("CONTENT_PAGE_DELETED", "Page reset to default: " + s, details(s, 0));
    }

    private static List<ContentPage.Section> toSections(List<ContentPageDTO.SectionDTO> sections) {
        List<ContentPage.Section> result = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        int index = 0;
        for (ContentPageDTO.SectionDTO dto : sections) {
            index++;
            String base = slugOf(dto.getId() != null && !dto.getId().isBlank() ? dto.getId() : dto.getTitle());
            if (base.isEmpty()) {
                base = "section-" + index;
            }
            String id = base;
            for (int n = 2; !usedIds.add(id); n++) {
                id = base + "-" + n;
            }
            result.add(ContentPage.Section.builder()
                    .id(id)
                    .title(dto.getTitle().trim())
                    .body(dto.getBody())
                    .build());
        }
        return result;
    }

    private static String slugOf(String text) {
        String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static Map<String, Object> details(String slug, int sections) {
        Map<String, Object> details = new HashMap<>();
        details.put("slug", slug);
        details.put("sections", sections);
        return details;
    }
}
