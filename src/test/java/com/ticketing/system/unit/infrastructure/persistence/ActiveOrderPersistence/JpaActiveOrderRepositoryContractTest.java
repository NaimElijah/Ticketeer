package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.adapter.out.persistence.JpaActiveOrderRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataActiveOrderRepository;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.shared.exception.ConcurrentReservationException;

/**
 * Runs the {@link IActiveOrderRepositoryContractTest} suite against the JPA adapter on an embedded H2
 * schema. {@code @Import} adds {@link JpaActiveOrderRepository};
 * {@code @DataJpaTest} provides H2 + real {@code active_orders} and {@code active_order_items} tables.
 * Each test starts from an empty schema ({@link #cleanTable()}) so the suite is order-independent;
 * deleting an order cascades to its item collection. CI never touches a real database.
 */
@DataJpaTest
@Import(JpaActiveOrderRepository.class)
class JpaActiveOrderRepositoryContractTest extends IActiveOrderRepositoryContractTest {

    @Autowired
    private JpaActiveOrderRepository repository;

    @Autowired
    private SpringDataActiveOrderRepository data;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private static final int MEMBER_ID = 5;
    private static final LocalDateTime CREATED = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final String PRE_V10_ORDER_KEY = "pre-v10-guest-cart";
    private static final String PRE_V10_SESSION_ID = "sid-pre-v10";

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected ActiveOrderRepository newRepository() {
        return repository;
    }

    // The login merge loads the guest cart and gives it the member's id inside the login transaction, so
    // the save's first query flushes that UPDATE. If another device stored the member's first cart in the
    // meantime, uk_active_orders_user_id fails right there, and the login must see a retryable concurrent
    // change, not a raw integrity error. It runs outside the test transaction so each save commits.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenAGuestCartGivenTheIdOfAMemberWhoseCartWasStoredMeanwhile_WhenSaved_ThenAConcurrentChange() {
        repository.save(ActiveOrder.forGuest("sid-guest", CREATED));
        repository.save(ActiveOrder.forMember(MEMBER_ID, "sid-other-device", CREATED));

        assertThrows(ConcurrentReservationException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    ActiveOrder guestCart = repository.getBySessionId("sid-guest").orElseThrow();
                    guestCart.attachToUser(MEMBER_ID);
                    repository.save(guestCart);
                }));
    }

    // V10 header and requirements §1 #8 (#224): a guest cart that code from before V10 wrote during a rolling
    // deploy has a null guest_session_id, and the key holds it once code from V10 on writes the row again. An
    // items-only change is that write's least case: the entity's own columns stay as they were.
    @Test
    void GivenAGuestCartRowWrittenWithoutItsGuestSessionKey_WhenItGainsALineAndIsSaved_ThenTheKeyIsFilled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into active_orders (order_key, user_id, session_id, status, created_at, version)"
                + " values (?, null, ?, 'PRE_CHECKOUT', ?, 0)", PRE_V10_ORDER_KEY, PRE_V10_SESSION_ID, CREATED);

        ActiveOrder cart = repository.getBySessionId(PRE_V10_SESSION_ID).orElseThrow();
        cart.addStandingReservation(1, 10, 1, 50.0, CREATED);
        repository.save(cart);

        assertEquals(PRE_V10_SESSION_ID, jdbc.queryForObject(
                "select guest_session_id from active_orders where order_key = ?", String.class, PRE_V10_ORDER_KEY),
                "the rewritten guest cart's guest_session_id");
    }
}
