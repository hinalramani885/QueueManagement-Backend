package com.qflow.backend.config;

import com.qflow.backend.model.Place;
import com.qflow.backend.repository.PlaceRepository;
import com.qflow.backend.service.CatalogAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent data fix that runs on startup: providers now sign up without a place and add their own
 * place afterwards, which is then linked to their account ({@code users.placeId} and {@code users.ownedPlaceIds}).
 * Places that providers created before this link existed are linked here. Running it again changes nothing.
 * Disable with {@code app.migrations.provider-place-link=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.migrations.provider-place-link", havingValue = "true", matchIfMissing = true)
public class ProviderPlaceLinkMigration implements ApplicationRunner {

    private final PlaceRepository placeRepository;
    private final CatalogAccessService catalogAccessService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int linked = 0;
            for (Place place : placeRepository.findByCreatedByRole("PROVIDER")) {
                if (place.getCreatedBy() != null && catalogAccessService.linkCreatedPlaceToProvider(place.getCreatedBy(), place.getId())) {
                    linked++;
                }
            }
            if (linked > 0) {
                log.info("Linked {} provider-created place(s) to their providers", linked);
            }
        } catch (Exception e) {
            // Never block startup because of this data fix; it runs again on the next start
            log.warn("Provider place link migration skipped: {}", e.getMessage());
        }
    }
}
