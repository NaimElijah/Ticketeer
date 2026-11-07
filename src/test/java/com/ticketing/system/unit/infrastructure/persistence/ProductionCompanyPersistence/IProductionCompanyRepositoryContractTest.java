package com.ticketing.system.unit.infrastructure.persistence.ProductionCompanyPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicyJsonConverter;
import com.ticketing.system.testutil.RepositoryLockProbe;

/**
 * Contract every {@link ProductionCompanyRepository} implementation must satisfy. The Memory and
 * JPA adapters each subclass this with their own {@link #newRepository()} factory; the tests are
 * reused. The owner/manager and policy round-trip tests pin the acceptance: the two id lists and the
 * purchase-policy tree survive save/reload on both adapters.
 */
abstract class IProductionCompanyRepositoryContractTest {

    protected abstract ProductionCompanyRepository newRepository();

    private ProductionCompanyRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    private ProductionCompany company(int id, int founderId, String name, CompanyStatus status) {
        return new ProductionCompany(id, founderId, name, status, "a description", 4.5);
    }

    @Test
    void GivenASavedCompany_WhenGetCompanyById_ThenReturnsTheSavedCompany() {
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));

        ProductionCompany found = repo.getCompanyById(1);
        assertEquals("Acme", found.getName());
        assertEquals(7, found.getFounderId());
        assertEquals(CompanyStatus.ACTIVE, found.getStatus());
    }

    // The Jpa table holds the status under a check constraint that a migration must widen for each new
    // constant (ADR-007). Inherited, so the save commits and a missing value fails here.
    @ParameterizedTest
    @EnumSource(CompanyStatus.class)
    void GivenACompanyInEachStatus_WhenSaved_ThenItIsFoundWithThatStatus(CompanyStatus status) {
        repo.save(company(1, 7, "Acme", status));

        assertEquals(status, repo.getCompanyById(1).getStatus());
    }

    @Test
    void GivenNoSuchCompany_WhenGetCompanyById_ThenThrowsCompanyNotFound() {
        assertThrows(CompanyNotFoundException.class, () -> repo.getCompanyById(9999));
    }

    @Test
    void GivenASavedCompany_WhenFindByName_ThenReturnsSavedAndEmptyOtherwise() {
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));

        assertTrue(repo.findByName("Acme").isPresent());
        assertFalse(repo.findByName("Ghost").isPresent());
        assertFalse(repo.findByName(null).isPresent());
    }

    @Test
    void GivenARepositoryBeforeAndAfterASave_WhenExistsByName_ThenTrueAfterSaveFalseOtherwise() {
        assertFalse(repo.existsByName("Acme"));
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));
        assertTrue(repo.existsByName("Acme"));
    }

    @Test
    void GivenActiveAndInactiveCompanies_WhenFindActive_ThenReturnsOnlyActiveCompanies() {
        repo.save(company(1, 7, "ActiveCo", CompanyStatus.ACTIVE));
        repo.save(company(2, 7, "InactiveCo", CompanyStatus.INACTIVE));

        assertEquals(Set.of("ActiveCo"),
                repo.findActive().stream().map(ProductionCompany::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void GivenCompaniesOfTwoFounders_WhenFindByFounder_ThenReturnsCompaniesFoundedByThatUser() {
        repo.save(company(1, 7, "First", CompanyStatus.ACTIVE));
        repo.save(company(2, 7, "Second", CompanyStatus.ACTIVE));
        repo.save(company(3, 9, "Other", CompanyStatus.ACTIVE));

        assertEquals(Set.of("First", "Second"),
                repo.findByFounder(7).stream().map(ProductionCompany::getName).collect(java.util.stream.Collectors.toSet()));
        assertTrue(repo.findByFounder(123).isEmpty());
    }

    @Test
    void GivenRepository_WhenNextIdCalledTwice_ThenProducesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        assertNotEquals(a, b);
        assertTrue(b > a);
    }

    @Test
    void GivenACompanyWithOwnersAndAManager_WhenSave_ThenPersistsOwnerAndManagerLists() {
        ProductionCompany c = company(1, 7, "Acme", CompanyStatus.ACTIVE); // founder 7 is the first owner
        c.addOwner(7, 8);   // owner 7 adds owner 8
        c.addManager(9);    // 9 becomes a manager
        repo.save(c);

        ProductionCompany found = repo.getCompanyById(1);
        assertEquals(Set.of(7, 8), new HashSet<>(found.getOwnerIds()));
        assertEquals(Set.of(9), new HashSet<>(found.getManagers()));
    }

    @Test
    void GivenACompanyWithTheDefaultPolicy_WhenSave_ThenRoundTripsTheDefaultNoPurchasePolicy() {
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));
        assertTrue(repo.getCompanyById(1).getPurchasePolicy() instanceof NoPurchasePolicy);
    }

    @Test
    void GivenACompanyWithAComposedPurchasePolicy_WhenSave_ThenRoundTripsTheComposedPurchasePolicyTree() {
        ProductionCompany c = company(1, 7, "Acme", CompanyStatus.ACTIVE);
        c.setPurchasePolicy(new AndPurchasePolicy(new AgePurchasePolicy(18), new MaxTicketsPurchasePolicy(4)));
        repo.save(c);

        ProductionCompany found = repo.getCompanyById(1);
        // Structural equality: the reloaded tree serializes identically to the original.
        PurchasePolicyJsonConverter converter = new PurchasePolicyJsonConverter();
        assertEquals(converter.convertToDatabaseColumn(c.getPurchasePolicy()),
                converter.convertToDatabaseColumn(found.getPurchasePolicy()));
    }

    // IRepository's lock contract: reentrant, and released by one unlock per lock. The second
    // lockForUpdate would hang a non-reentrant lock, hence the timeout on a separate thread. The Jpa
    // adapter makes both calls no-ops (@Version guards its writes), so it passes trivially.
    @Test
    @Timeout(value = RepositoryLockProbe.TEST_TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void GivenALockTakenTwiceAndReleasedTwice_WhenAnotherThreadLocksTheSameId_ThenItGetsTheLock() throws Exception {
        repo.lockForUpdate(1);
        repo.lockForUpdate(1);
        repo.unlock(1);
        repo.unlock(1);

        RepositoryLockProbe.assertAnotherThreadCanLock(repo, 1);
    }
}
