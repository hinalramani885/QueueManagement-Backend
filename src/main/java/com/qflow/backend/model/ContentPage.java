package com.qflow.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An admin-editable information page (privacy policy, terms, documentation, about, how to use).
 * When no document exists for a slug, the frontend shows its built-in default text.
 */
@Document("content_pages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentPage {

    @Id
    private String id;

    @Indexed(unique = true)
    private String slug;

    private String title;

    private String subtitle;

    /** Ordered sections; each body is Markdown. */
    @Builder.Default
    private List<Section> sections = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String updatedBy;       // admin user id
    private String updatedByName;   // admin name at the time of the change

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section {
        private String id;      // stable anchor id, e.g. "data-security"
        private String title;
        private String body;    // Markdown
    }
}
