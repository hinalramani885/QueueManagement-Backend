package com.qflow.backend.dto;

import com.qflow.backend.model.ContentPage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An admin-editable information page with Markdown sections")
public class ContentPageDTO {

    @Schema(description = "Page slug", example = "privacy-policy", accessMode = Schema.AccessMode.READ_ONLY)
    private String slug;

    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must be at most 200 characters")
    @Schema(example = "Privacy Policy")
    private String title;

    @Size(max = 500, message = "Subtitle must be at most 500 characters")
    private String subtitle;

    @NotNull(message = "Sections are required")
    @Size(max = 100, message = "A page can have at most 100 sections")
    @Valid
    private List<SectionDTO> sections = new ArrayList<>();

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private LocalDateTime updatedAt;

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private String updatedByName;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectionDTO {
        @Size(max = 80, message = "Section id must be at most 80 characters")
        private String id;

        @NotBlank(message = "Section title is required")
        @Size(max = 200, message = "Section title must be at most 200 characters")
        private String title;

        @NotNull(message = "Section body is required")
        @Size(max = 50000, message = "Section body must be at most 50000 characters")
        @Schema(description = "Markdown text")
        private String body;
    }

    public static ContentPageDTO fromEntity(ContentPage page) {
        ContentPageDTO dto = new ContentPageDTO();
        dto.setSlug(page.getSlug());
        dto.setTitle(page.getTitle());
        dto.setSubtitle(page.getSubtitle());
        dto.setSections(page.getSections() == null ? new ArrayList<>() : page.getSections().stream()
                .map(s -> new SectionDTO(s.getId(), s.getTitle(), s.getBody()))
                .toList());
        dto.setUpdatedAt(page.getUpdatedAt());
        dto.setUpdatedByName(page.getUpdatedByName());
        return dto;
    }
}
