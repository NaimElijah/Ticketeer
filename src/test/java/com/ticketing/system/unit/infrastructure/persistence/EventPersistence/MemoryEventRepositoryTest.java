package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.catalog.adapter.out.persistence.MemoryEventRepository;

public class MemoryEventRepositoryTest extends IEventRepositoryContractTest {

    @Override
    protected EventRepository newRepository() {
        return new MemoryEventRepository();
    }

    // === delete — write-lock enforcement (Memory-specific concurrency contract) ===
    // delete() is a structural change; like save(), it must reject an unlocked caller so it
    // can't race with buyer read locks or other lifecycle writes.

    @Test
    void givenExistingEvent_whenDeletedWithoutHoldingWriteLock_thenThrows() {
        EventRepository repo = newRepository();
        repo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.CANCELED, EventCategory.CONCERT));

        assertThrows(IllegalStateException.class, () -> repo.delete(1));
    }

    @Test
    void givenExistingEvent_whenDeletedHoldingWriteLock_thenEventIsRemoved() {
        EventRepository repo = newRepository();
        repo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.CANCELED, EventCategory.CONCERT));

        repo.lockForUpdate(1);
        try {
            repo.delete(1);
        } finally {
            repo.unlock(1);
        }

        assertThrows(EventNotFoundException.class, () -> repo.findById(1));
    }

    // The Jpa adapter lower-cases a term with Locale.ROOT, so this one must too: under a Turkish default
    // locale "TITLE".toLowerCase() is "tıtle", with a dotless i, and would match nothing.
    @Test
    void GivenATurkishDefaultLocale_WhenSearchingForAnUppercaseTerm_ThenItMatchesIgnoringCase() {
        EventRepository repo = newRepository();
        repo.save(buildEvent(1, "Title Night", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            List<Event> found = repo.searchAll(new CatalogSearchFiltersDTO(
                    "TITLE", null, null, null, null, null, null, null, null, null, null, null, null));

            assertEquals(List.of(1), found.stream().map(Event::getId).toList());
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }
}
