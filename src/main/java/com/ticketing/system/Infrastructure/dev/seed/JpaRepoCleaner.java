package com.ticketing.system.Infrastructure.dev.seed;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.Infrastructure.persistence.ActiveOrderPersistence.SpringDataActiveOrderRepository;
import com.ticketing.system.Infrastructure.persistence.ConversationPersistence.SpringDataConversationRepository;
import com.ticketing.system.catalog.adapter.out.persistence.SpringDataEventRepository;
import com.ticketing.system.Infrastructure.persistence.NotificationPersistence.SpringDataNotificationRepository;
import com.ticketing.system.Infrastructure.persistence.OrderReceiptPersistence.SpringDataOrderReceiptRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataProductionCompanyRepository;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataSessionRepository;
import com.ticketing.system.Infrastructure.persistence.TicketPersistence.SpringDataTicketRepository;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataUserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The real database wipe behind {@code seed.mode=wipe}/{@code reset} (#366), active on the
 * {@code jpa} profile (dev's H2 and the Supabase Postgres alike).
 *
 * <p>{@code deleteAll()} per aggregate root removes the root rows and cascades to their owned
 * sub-tables (venue maps → zones → seats, receipt lines, messages, …). Cross-aggregate references
 * are by-id (no FK between roots), so the order between roots is irrelevant; it's still written
 * leaf-first for clarity. Pure JPA — no native SQL — so it behaves identically on H2 and Postgres.
 *
 * <p>The {@code admins} table is <strong>deliberately not</strong> cleared: the bootstrap platform
 * admin is infrastructure (re-created by {@code PlatformInitializationRunner} anyway), and a reset
 * needs an admin to re-open the market for the reseed's checkouts.
 *
 * <p><strong>DESTRUCTIVE.</strong> Only invoked from a {@code wipe}/{@code reset} run, which is
 * opt-in (default {@code seed.mode=off}) and gated behind an interactive confirmation in the runner.
 */
@Component
@Profile("jpa")
@Slf4j
public class JpaRepoCleaner implements RepoCleaner {

    private final SpringDataTicketRepository tickets;
    private final SpringDataOrderReceiptRepository receipts;
    private final SpringDataActiveOrderRepository activeOrders;
    private final SpringDataEventRepository events;
    private final SpringDataProductionCompanyRepository companies;
    private final SpringDataConversationRepository conversations;
    private final SpringDataNotificationRepository notifications;
    private final SpringDataSessionRepository sessions;
    private final SpringDataUserRepository users;

    public JpaRepoCleaner(
            SpringDataTicketRepository tickets,
            SpringDataOrderReceiptRepository receipts,
            SpringDataActiveOrderRepository activeOrders,
            SpringDataEventRepository events,
            SpringDataProductionCompanyRepository companies,
            SpringDataConversationRepository conversations,
            SpringDataNotificationRepository notifications,
            SpringDataSessionRepository sessions,
            SpringDataUserRepository users) {
        this.tickets = tickets;
        this.receipts = receipts;
        this.activeOrders = activeOrders;
        this.events = events;
        this.companies = companies;
        this.conversations = conversations;
        this.notifications = notifications;
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    @Transactional
    public void clearAll() {
        log.warn("seed wipe — deleting all business data from the database (keeping the bootstrap admin)");
        tickets.deleteAll();
        receipts.deleteAll();
        activeOrders.deleteAll();
        events.deleteAll();
        companies.deleteAll();
        conversations.deleteAll();
        notifications.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
    }
}
