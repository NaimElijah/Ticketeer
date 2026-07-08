package com.ticketing.system.unit.infrastructure.persistence.NotificationPersistence;

import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.adapter.out.persistence.MemoryNotificationRepository;

class MemoryNotificationRepositoryContractTest extends INotificationRepositoryContractTest {

    @Override
    protected NotificationRepository newRepository() {
        return new MemoryNotificationRepository();
    }
}
