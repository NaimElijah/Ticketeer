package com.ticketing.system.unit.infrastructure.persistence.EventPersistence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.Infrastructure.persistence.EventPersistence.MemoryEventRepository;

public class MemoryEventRepositoryTest extends IEventRepositoryContractTest {

    @Override
    protected IEventRepository newRepository() {
        return new MemoryEventRepository();
    }

    // === delete — write-lock enforcement (Memory-specific concurrency contract) ===
    // delete() is a structural change; like save(), it must reject an unlocked caller so it
    // can't race with buyer read locks or other lifecycle writes.

    @Test
    void givenExistingEvent_whenDeletedWithoutHoldingWriteLock_thenThrows() {
        IEventRepository repo = newRepository();
        repo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.CANCELED, EventCategory.CONCERT));

        assertThrows(IllegalStateException.class, () -> repo.delete(1));
    }

    @Test
    void givenExistingEvent_whenDeletedHoldingWriteLock_thenEventIsRemoved() {
        IEventRepository repo = newRepository();
        repo.save(buildEvent(1, "Rock Night", 4.5, 10, EventStatus.CANCELED, EventCategory.CONCERT));

        repo.lockForUpdate(1);
        try {
            repo.delete(1);
        } finally {
            repo.unlock(1);
        }

        assertThrows(EventNotFoundException.class, () -> repo.findById(1));
    }
}
