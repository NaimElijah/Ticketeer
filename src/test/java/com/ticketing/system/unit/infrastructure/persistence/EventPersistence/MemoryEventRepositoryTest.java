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
import com.ticketing.system.testutil.DefaultLocale;

public class MemoryEventRepositoryTest extends IEventRepositoryContractTest {

    @Override
    protected EventRepository newRepository() {
        return new MemoryEventRepository();
    }

    // === delete — write-lock enforcement (Memory-specific concurrency contract) ===
    // delete() is a structural change; like save(), it must reject an unlocked caller so it
    // can't race with buyer read locks or other lifecycle writes.

    @Test
    void GivenExistingEvent_WhenDeletedWithoutHoldingWriteLock_ThenThrows() {
        EventRepository repo = newRepository();
        repo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.CANCELED, EventCategory.CONCERT));

        assertThrows(IllegalStateException.class, () -> repo.delete(1));
    }

    @Test
    void GivenExistingEvent_WhenDeletedHoldingWriteLock_ThenEventIsRemoved() {
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

    // === save of a stored event — write-lock enforcement, as for delete ===
    // The Jpa adapter accepts this save: @Version guards it there (JpaEventRepositoryContractTest). The
    // change is made on a second instance, because this adapter's findById returns the stored one itself.

    @Test
    void GivenAStoredEvent_WhenAChangedInstanceIsSavedWithoutItsLock_ThenTheSaveIsRefusedAndTheStoredEventStays() {
        EventRepository repo = newRepository();
        repo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.SCHEDULED, EventCategory.CONCERT));
        Event changed = buildEvent(1, "Rock Night", 4.5, 10, EventStatus.SCHEDULED, EventCategory.CONCERT);
        changed.transitionToOnSale();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> repo.save(changed));

        assertEquals("Event 1 must be locked before saving", refused.getMessage());
        assertEquals(EventStatus.SCHEDULED, repo.findById(1).getStatus());
    }

    // A buyer operation holds the shared lock and may save the event (a sell-out flip, ADR-015).
    @Test
    void GivenAStoredEvent_WhenSavedUnderTheBuyerLock_ThenTheSaveIsAccepted() {
        EventRepository repo = newRepository();
        repo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        Event soldOut = buildEvent(1, "Rock Night", 4.5, 10, EventStatus.SOLD_OUT, EventCategory.CONCERT);

        repo.lockForBuyerOperation(1);
        try {
            repo.save(soldOut);
        } finally {
            repo.unlockBuyerOperation(1);
        }

        assertEquals(EventStatus.SOLD_OUT, repo.findById(1).getStatus());
    }

    // The Jpa adapter lower-cases a term with Locale.ROOT, so this one must too: under a Turkish default
    // locale "TITLE".toLowerCase() is "tıtle", with a dotless i, and would match nothing.
    @Test
    void GivenATurkishDefaultLocale_WhenSearchingForAnUppercaseTerm_ThenItMatchesIgnoringCase() {
        EventRepository repo = newRepository();
        repo.save(buildEvent(1, "Title Night", 4.5, 10, EventStatus.ON_SALE, EventCategory.CONCERT));
        List<Event> found = DefaultLocale.callUnder(Locale.forLanguageTag("tr-TR"), () -> repo.searchAll(
                new CatalogSearchFiltersDTO("TITLE", null, null, null, null, null, null, null, null, null, null, null, null)));

        assertEquals(List.of(1), found.stream().map(Event::getId).toList());
    }
}
