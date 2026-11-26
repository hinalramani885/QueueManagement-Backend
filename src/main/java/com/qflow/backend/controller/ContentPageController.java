package com.qflow.backend.controller;

import com.qflow.backend.dto.ContentPageDTO;
import com.qflow.backend.security.annotations.AdminOnly;
import com.qflow.backend.service.ContentPageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
@Tag(name = "Content pages", description = "Admin-editable information pages: privacy policy, terms, documentation, about, how to use")
public class ContentPageController {

    private final ContentPageService contentPageService;

    @GetMapping("/{slug}")
    @Operation(summary = "Get a page", description = "Returns the saved page. 404 when the page has not been customised "
            + "(the app then shows its built-in default text). Public access.")
    @ApiResponse(responseCode = "200", description = "Saved page", content = @Content(schema = @Schema(implementation = ContentPageDTO.class)))
    @ApiResponse(responseCode = "404", description = "Unknown page or no saved content")
    public ResponseEntity<ContentPageDTO> getPage(
            @Parameter(description = "privacy-policy, terms-of-service, documentation, about-us or how-to-use") @PathVariable String slug) {
        return ResponseEntity.ok(contentPageService.getPage(slug));
    }

    @PutMapping("/{slug}")
    @AdminOnly
    @Operation(summary = "Save a page", description = "Creates or replaces the page content (title, subtitle and Markdown sections). Admin only.")
    @ApiResponse(responseCode = "200", description = "Saved page", content = @Content(schema = @Schema(implementation = ContentPageDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "Unknown page")
    public ResponseEntity<ContentPageDTO> savePage(@PathVariable String slug,
                                                   @Valid @RequestBody ContentPageDTO page,
                                                   Authentication authentication) {
        log.info("Saving content page {} by admin {}", slug, authentication.getName());
        return ResponseEntity.ok(contentPageService.savePage(slug, page, authentication.getName()));
    }

    @DeleteMapping("/{slug}")
    @AdminOnly
    @Operation(summary = "Delete a page's saved content", description = "Deletes the saved content so the built-in default text is shown again. Admin only.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "Unknown page or nothing saved")
    public ResponseEntity<Void> deletePage(@PathVariable String slug, Authentication authentication) {
        log.warn("Deleting content page {} by admin {}", slug, authentication.getName());
        contentPageService.deletePage(slug);
        return ResponseEntity.noContent().build();
    }
}
