package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.support.Repositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.bootstrap.dev.seed.JpaRepoCleaner;
import com.ticketing.system.bootstrap.dev.seed.RepoCleaner;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataAdminRepository;
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

    // The platform admin is infrastructure the reset needs to reopen the market.
    private static final Set<Class<?>> KEPT_BY_THE_WIPE = Set.of(SpringDataAdminRepository.class);

    // deleteAll(), deleteAllInBatch() and the like all empty the table.
    private static final String DELETE_ALL = "deleteAll";

    @Autowired private RepoCleaner repoCleaner;
    @Autowired private SpringDataUserRepository userRows;
    @Autowired private SpringDataProductionCompanyRepository companyRows;
    @Autowired private SpringDataCompanyAppointmentRepository appointmentRows;
    @Autowired private UserRepository users;
    @Autowired private ProductionCompanyRepository companies;
    @Autowired private CompanyAppointmentRepository appointments;
    @Autowired private Clock clock;
    @Autowired private ApplicationContext applicationContext;

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

        assertEquals(FIRST_ID, userId, "precondition: the recreated user reuses the previous boot's id");
        assertEquals(FIRST_ID, companyId, "precondition: the recreated company reuses the previous boot's id");
        List<CompanyAppointment> inherited = appointments.findByTargetId(userId);
        assertEquals(List.of(), inherited,
                "user " + userId + " was created after the wipe, so no appointment may name it; found "
                        + inherited.size() + " left over from before the wipe");
        List<CompanyAppointment> companyAppointments = appointments.findByCompanyId(companyId);
        assertEquals(List.of(), companyAppointments,
                "company " + companyId + " was created after the wipe, so no appointment may name it; found "
                        + companyAppointments.size() + " left over from before the wipe");
    }

    /**
     * #170 also asked whether any other aggregate is missed the same way. Checking that by behaviour
     * would need a row in every table, a checkout and the asynchronous notifications included, so this
     * guard checks coverage instead: it builds the cleaner on a test double of each repository it takes
     * and requires a delete-all on every Spring Data repository in the context except the admins'.
     * A new aggregate's repository that the cleaner never empties then fails here, before a reseed
     * hands its old rows to new ids. The admins are kept on purpose (see {@link JpaRepoCleaner}).
     */
    @Test
    void GivenEverySpringDataRepository_WhenTheCleanerClearsAll_ThenEachOneButTheAdminsIsEmptied() throws Exception {
        Set<Class<?>> expected = springDataRepositories().stream()
                .filter(repository -> !KEPT_BY_THE_WIPE.contains(repository))
                .collect(Collectors.toSet());
        Map<Class<?>, Object> doubles = new HashMap<>();
        Constructor<?>[] constructors = JpaRepoCleaner.class.getConstructors();
        assertEquals(1, constructors.length, "the cleaner should take its repositories through one constructor");
        Constructor<?> constructor = constructors[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(type -> doubles.computeIfAbsent(type, repository -> Mockito.mock(repository)))
                .toArray();

        ((RepoCleaner) constructor.newInstance(arguments)).clearAll();

        Set<Class<?>> emptied = doubles.entrySet().stream()
                .filter(entry -> Mockito.mockingDetails(entry.getValue()).getInvocations().stream()
                        .anyMatch(invocation -> invocation.getMethod().getName().startsWith(DELETE_ALL)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertEquals(sortedNames(expected), sortedNames(emptied),
                "the seed wipe must empty every Spring Data repository except the admins'");
    }

    private Set<Class<?>> springDataRepositories() {
        Repositories repositories = new Repositories(applicationContext);
        Set<Class<?>> interfaces = new HashSet<>();
        for (Class<?> domainType : repositories) {
            repositories.getRepositoryInformationFor(domainType)
                    .ifPresent(information -> interfaces.add(information.getRepositoryInterface()));
        }
        return interfaces;
    }

    private static List<String> sortedNames(Set<Class<?>> types) {
        return types.stream().map(Class::getSimpleName).sorted().toList();
    }
}
