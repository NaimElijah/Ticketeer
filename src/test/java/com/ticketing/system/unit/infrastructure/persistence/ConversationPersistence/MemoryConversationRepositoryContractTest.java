package com.ticketing.system.unit.infrastructure.persistence.ConversationPersistence;

import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.adapter.out.persistence.MemoryConversationRepository;

class MemoryConversationRepositoryContractTest extends IConversationRepositoryContractTest {

    @Override
    protected ConversationRepository newRepository() {
        return new MemoryConversationRepository();
    }
}
