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

import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.Core.Domain.policies.purchase.AgePurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.AndPurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.MaxTicketsPurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.NoPurchasePolicy;
import com.ticketing.system.Core.Domain.policies.purchase.PurchasePolicyJsonConverter;

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
    void save_thenGetCompanyById_returnsTheSavedCompany() {
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));

        ProductionCompany found = repo.getCompanyById(1);
        assertEquals("Acme", found.getName());
        assertEquals(7, found.getFounderId());
        assertEquals(CompanyStatus.ACTIVE, found.getStatus());
    }

    @Test
    void getCompanyById_throwsWhenMissing() {
        assertThrows(RuntimeException.class, () -> repo.getCompanyById(9999));
    }

    @Test
    void findByName_returnsSavedAndEmptyOtherwise() {
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));

        assertTrue(repo.findByName("Acme").isPresent());
        assertFalse(repo.findByName("Ghost").isPresent());
        assertFalse(repo.findByName(null).isPresent());
    }

    @Test
    void existsByName_trueAfterSaveFalseOtherwise() {
        assertFalse(repo.existsByName("Acme"));
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));
        assertTrue(repo.existsByName("Acme"));
    }

    @Test
    void findActive_returnsOnlyActiveCompanies() {
        repo.save(company(1, 7, "ActiveCo", CompanyStatus.ACTIVE));
        repo.save(company(2, 7, "InactiveCo", CompanyStatus.INACTIVE));

        assertEquals(Set.of("ActiveCo"),
                repo.findActive().stream().map(ProductionCompany::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void findByFounder_returnsCompaniesFoundedByThatUser() {
        repo.save(company(1, 7, "First", CompanyStatus.ACTIVE));
        repo.save(company(2, 7, "Second", CompanyStatus.ACTIVE));
        repo.save(company(3, 9, "Other", CompanyStatus.ACTIVE));

        assertEquals(Set.of("First", "Second"),
                repo.findByFounder(7).stream().map(ProductionCompany::getName).collect(java.util.stream.Collectors.toSet()));
        assertTrue(repo.findByFounder(123).isEmpty());
    }

    @Test
    void nextId_producesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        assertNotEquals(a, b);
        assertTrue(b > a);
    }

    @Test
    void save_persistsOwnerAndManagerLists() {
        ProductionCompany c = company(1, 7, "Acme", CompanyStatus.ACTIVE); // founder 7 is the first owner
        c.addOwner(7, 8);   // owner 7 adds owner 8
        c.addManager(9);    // 9 becomes a manager
        repo.save(c);

        ProductionCompany found = repo.getCompanyById(1);
        assertEquals(Set.of(7, 8), new HashSet<>(found.getOwnerIds()));
        assertEquals(Set.of(9), new HashSet<>(found.getManagers()));
    }

    @Test
    void save_roundTripsTheDefaultNoPurchasePolicy() {
        repo.save(company(1, 7, "Acme", CompanyStatus.ACTIVE));
        assertTrue(repo.getCompanyById(1).getPurchasePolicy() instanceof NoPurchasePolicy);
    }

    @Test
    void save_roundTripsAComposedPurchasePolicyTree() {
        ProductionCompany c = company(1, 7, "Acme", CompanyStatus.ACTIVE);
        c.setPurchasePolicy(new AndPurchasePolicy(new AgePurchasePolicy(18), new MaxTicketsPurchasePolicy(4)));
        repo.save(c);

        ProductionCompany found = repo.getCompanyById(1);
        // Structural equality: the reloaded tree serializes identically to the original.
        PurchasePolicyJsonConverter converter = new PurchasePolicyJsonConverter();
        assertEquals(converter.convertToDatabaseColumn(c.getPurchasePolicy()),
                converter.convertToDatabaseColumn(found.getPurchasePolicy()));
    }
}
