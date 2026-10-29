package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.bootstrap.dev.seed.RepoCleaner;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataUserRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataCompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataProductionCompanyRepository;
import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;

/**
 * #170: a seed wipe ({@code seed.mode=wipe}/{@code reset}) must leave no company appointment behind,
 * or reseeded users inherit roles nobody granted them (SLR.7, persistence).
 *
 * <p>The rows of a "previous boot" go straight into the tables, so the adapters' id counters are
 * still unseeded, as in a fresh process that wipes a persistent database before it mints any id.
 * After the wipe the counters seed from an empty table, so the re-created user and company get the
 * ids the previous boot used, and only the appointment table still links them. The dedicated
 * in-memory database URL gives this class its own context and counters, and {@code @DirtiesContext}
 * keeps that context, wiped, away from other classes.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seedwipe;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
@DirtiesContext
class SeedWipeJpaTest {

    // The first id an unseeded counter mints over an empty table, which the previous boot also used.
    private static final int FIRST_ID = 1;
    private static final String PASSWORD_HASH = "$2a$10$previousbootpreviousbootpreviousbootpreviousbo";
    private static final int MEMBER_AGE = 30;
    private static final double COMPANY_RATING = 4.0;

    @Autowired private RepoCleaner repoCleaner;
    @Autowired private SpringDataUserRepository userRows;
    @Autowired private SpringDataProductionCompanyRepository companyRows;
    @Autowired private SpringDataCompanyAppointmentRepository appointmentRows;
    @Autowired private UserRepository users;
    @Autowired private ProductionCompanyRepository companies;
    @Autowired private CompanyAppointmentRepository appointments;
    @Autowired private Clock clock;

    @Test
    void GivenAnAppointmentFromAPreviousBoot_WhenWipedAndAUserAndCompanyAreRecreated_ThenTheNewUserHasNoAppointment() {
        userRows.saveAndFlush(new User(FIRST_ID, "previous-owner", "previous-owner@test.com", PASSWORD_HASH, MEMBER_AGE));
        companyRows.saveAndFlush(new ProductionCompany(FIRST_ID, FIRST_ID, "PreviousCo", CompanyStatus.ACTIVE,
                "desc", COMPANY_RATING));
        appointmentRows.saveAndFlush(CompanyAppointment.FoundingAppointment(
                FIRST_ID, FIRST_ID, FIRST_ID, FIRST_ID, LocalDateTime.now(clock)));

        repoCleaner.clearAll();
        int userId = users.nextId();
        users.save(new User(userId, "fresh-member", "fresh-member@test.com", PASSWORD_HASH, MEMBER_AGE));
        int companyId = companies.nextId();
        companies.save(new ProductionCompany(companyId, userId, "FreshCo", CompanyStatus.ACTIVE, "desc",
                COMPANY_RATING));

        List<CompanyAppointment> inherited = appointments.findByTargetId(userId);
        assertEquals(List.of(), inherited,
                "user " + userId + " was created after the wipe, so no appointment may name it; found "
                        + inherited.size() + " left over from before the wipe");
    }
}
