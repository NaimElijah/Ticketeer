package com.ticketing.system.unit.infrastructure.persistence.ConversationPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.adapter.out.persistence.JpaConversationRepository;
import com.ticketing.system.messaging.adapter.out.persistence.SpringDataConversationRepository;

/**
 * Runs the {@link IConversationRepositoryContractTest} suite against the JPA adapter on an embedded H2
 * schema. {@code @ActiveProfiles("jpa")} activates {@link JpaConversationRepository};
 * {@code @DataJpaTest} provides H2 + real {@code conversations} and {@code messages} tables (with the
 * {@code message_order} order column). Each test starts from an empty schema ({@link #cleanTable()})
 * so the suite is order-independent; deleting a conversation cascades to its messages. CI never
 * touches a real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import(JpaConversationRepository.class)
class JpaConversationRepositoryContractTest extends IConversationRepositoryContractTest {

    @Autowired
    private JpaConversationRepository repository;

    @Autowired
    private SpringDataConversationRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected ConversationRepository newRepository() {
        return repository;
    }
}
