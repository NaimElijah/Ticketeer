package com.ticketing.system.bootstrap.dev.seed;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.sales.adapter.out.persistence.SpringDataActiveOrderRepository;
import com.ticketing.system.messaging.adapter.out.persistence.SpringDataConversationRepository;
import com.ticketing.system.catalog.adapter.out.persistence.SpringDataEventRepository;
import com.ticketing.system.notifications.adapter.out.persistence.SpringDataNotificationRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataOrderReceiptRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataCompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataProductionCompanyRepository;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataSessionRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataTicketRepository;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataUserRepository;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

import lombok.extern.slf4j.Slf4j;

/**
 * The database wipe behind {@code seed.mode=wipe}/{@code reset} (#366), active wherever the Jpa
 * repository adapters are (dev's H2 and the Supabase Postgres alike).
 *
 * <p>{@code deleteAll()} per aggregate root removes the root rows and cascades to their owned
 * sub-tables (venue maps → zones → seats, receipt lines, messages, …). Cross-aggregate references
 * are by-id (no FK between roots), so the order between roots is irrelevant; it's still written
 * leaf-first for clarity. Pure JPA — no native SQL — so it behaves identically on H2 and Postgres.
 *
 * <p>Every aggregate's table except {@code admins} (below) is cleared, company appointments included: their {@code companyId} and
 * {@code targetId} are plain columns, and the id counters restart from the emptied tables, so an
 * appointment left behind would hand its role to whichever user and company are created next (#170).
 *
 * <p>The {@code admins} table is <strong>deliberately not</strong> cleared: the bootstrap platform
 * admin is infrastructure (re-created by {@code PlatformInitializationRunner} anyway), and a reset
 * needs an admin to re-open the market for the reseed's checkouts.
 *
 * <p><strong>DESTRUCTIVE.</strong> Only invoked from a {@code wipe}/{@code reset} run, which is
 * opt-in (default {@code seed.mode=off}) and refused by {@code ScenarioRunner} unless
 * {@code seed.assume-yes=true}, an environment flag rather than a console prompt.
 */
@Component
@Profile(PersistenceProfiles.JPA_ADAPTERS)
@Slf4j
public class JpaRepoCleaner implements RepoCleaner {

    private final SpringDataTicketRepository tickets;
    private final SpringDataOrderReceiptRepository receipts;
    private final SpringDataActiveOrderRepository activeOrders;
    private final SpringDataEventRepository events;
    private final SpringDataProductionCompanyRepository companies;
    private final SpringDataCompanyAppointmentRepository appointments;
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
            SpringDataCompanyAppointmentRepository appointments,
            SpringDataConversationRepository conversations,
            SpringDataNotificationRepository notifications,
            SpringDataSessionRepository sessions,
            SpringDataUserRepository users) {
        this.tickets = tickets;
        this.receipts = receipts;
        this.activeOrders = activeOrders;
        this.events = events;
        this.companies = companies;
        this.appointments = appointments;
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
        appointments.deleteAll();
        companies.deleteAll();
        conversations.deleteAll();
        notifications.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
    }
}
