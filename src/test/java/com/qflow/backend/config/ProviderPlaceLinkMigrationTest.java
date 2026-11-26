package com.qflow.backend.config;

import com.qflow.backend.model.Place;
import com.qflow.backend.repository.PlaceRepository;
import com.qflow.backend.service.CatalogAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderPlaceLinkMigrationTest {

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private CatalogAccessService catalogAccessService;

    @InjectMocks
    private ProviderPlaceLinkMigration migration;

    private Place providerPlace(String id, String creator) {
        Place p = new Place();
        p.setId(id);
        p.setCreatedBy(creator);
        p.setCreatedByRole("PROVIDER");
        return p;
    }

    @Test
    void linksEveryProviderCreatedPlaceToItsCreator() {
        when(placeRepository.findByCreatedByRole("PROVIDER"))
                .thenReturn(List.of(providerPlace("p1", "prov1"), providerPlace("p2", "prov2"), providerPlace("p3", null)));

        migration.run(null);

        verify(catalogAccessService).linkCreatedPlaceToProvider("prov1", "p1");
        verify(catalogAccessService).linkCreatedPlaceToProvider("prov2", "p2");
        verifyNoMoreInteractions(catalogAccessService);
    }

    @Test
    void neverBlocksStartup() {
        when(placeRepository.findByCreatedByRole("PROVIDER")).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> migration.run(null));
    }
}
