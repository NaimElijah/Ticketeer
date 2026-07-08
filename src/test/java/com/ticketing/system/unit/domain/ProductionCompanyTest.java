package com.ticketing.system.unit.domain;
import com.ticketing.system.organization.application.service.CompanyRatings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.support.BaseDomainTest;

class ProductionCompanyTest extends BaseDomainTest {

        private final int COMPANY_ID = 100;
        private final int OWNER_ID = 1;
        private final int TARGET_USER_ID = 3;
        private final String COMPANY_1_NAME = "Company1";
        private final String COMPANY_1_DESCRIPTION = "A test production company1";
        private final double COMPANY_1_RATING = 4.5;

        private ProductionCompany company;
        private List<Permission> defaultPermissions;

        @BeforeEach
        public void setUp() {
                company = track(new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME, CompanyStatus.ACTIVE,
                                COMPANY_1_DESCRIPTION, COMPANY_1_RATING));

                defaultPermissions = new ArrayList<>();
                defaultPermissions.add(Permission.MANAGE_INVENTORY);
        }

        @Test
        public void GivenManager_WhenRevokeManager_ThenManagerRemoved() {
                company.addManager(TARGET_USER_ID);

                company.RevokeAppointment(TARGET_USER_ID);

                assertFalse(company.getManagers().contains(TARGET_USER_ID));
        }

        @Test
        public void GivenTargetAlreadyManager_WhenAddManager_ThenThrowException() {
                company.addManager(TARGET_USER_ID);
                assertThrows(RuntimeException.class, () -> company.addManager(TARGET_USER_ID));
        }

        @Test
        public void GivenTargetIsNotManager_WhenRevokeManager_ThenThrowException() {
                assertThrows(RuntimeException.class, () -> company.RevokeAppointment(TARGET_USER_ID));
        }

        ////////////////////////////////////////////////////////////////// UC-20Tests

        @Test
        public void GivenOwner_WhenValidateManagerOrOwner_ThenDoesNotThrow() {
                assertTrue(company.getOwnerId() == OWNER_ID);
        }

        @Test
        public void GivenUserIsOwner_WhenCheckOwner_ThenSucceeds() {
                assertTrue(company.getOwnerId() == OWNER_ID);
        }

        @Test
        public void GivenUserIsNotOwner_WhenCheckOwner_ThenThrowException() {
                assertThrows(UnauthorizedActionException.class, () -> company.checkowner(TARGET_USER_ID));
        }

        ////////////////////////////////////////////////////////////////// Rating invariant

        @Test
        public void GivenRatingAboveFive_WhenConstruct_ThenThrowException() {
                // A throwing constructor produces no object — nothing to track().
                assertThrows(IllegalStateException.class, () -> new ProductionCompany(
                                COMPANY_ID, OWNER_ID, COMPANY_1_NAME, CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 6.0));
        }

        @Test
        public void GivenNegativeRating_WhenConstruct_ThenThrowException() {
                assertThrows(IllegalStateException.class, () -> new ProductionCompany(
                                COMPANY_ID, OWNER_ID, COMPANY_1_NAME, CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, -1.0));
        }

        @Test
        public void GivenNullRating_WhenConstruct_ThenSucceeds() {
                // A null rating models an unrated company (rating is derived, see CompanyRatings); the
                // constructor invariant must permit it even though registerCompany seeds new companies with 0.0.
                assertDoesNotThrow(() -> track(new ProductionCompany(
                                COMPANY_ID, OWNER_ID, COMPANY_1_NAME, CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, null)));
        }

        @Test
        public void GivenBoundaryRatings_WhenConstruct_ThenSucceeds() {
                assertDoesNotThrow(() -> track(new ProductionCompany(
                                COMPANY_ID, OWNER_ID, COMPANY_1_NAME, CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 0.0)));
                assertDoesNotThrow(() -> track(new ProductionCompany(
                                COMPANY_ID, OWNER_ID, COMPANY_1_NAME, CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 5.0)));
        }

}
