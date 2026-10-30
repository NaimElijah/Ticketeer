package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.ui.presenters.company.CompanyRegistrationPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * {@link CompanyRegistrationPresenter} hands the view a reason the user may read for every refusal:
 * the service's own words for a business rule, and generic text for anything without them, never an
 * exception's raw text.
 */
class CompanyRegistrationPresenterTest {

    private static final String TOKEN = "member-jwt";
    private static final int USER_ID = 42;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;
    private static final String NAME = "BlueWave Productions";
    private static final String DESCRIPTION = "Concerts";

    private CompanyManagementService companyManagementService;
    private CompanyRegistrationPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, USER_ID, "member"));
        companyManagementService = mock(CompanyManagementService.class);
        presenter = new CompanyRegistrationPresenter(companyManagementService);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    // The domain bounds the name to its column (#186); the user must learn the limit, not "try again".
    @Test
    void GivenTheDomainRefusesTheName_WhenRegister_ThenFailureCarriesTheDomainReason() {
        String reason = "Company name can be at most 255 characters.";
        when(companyManagementService.registerCompany(eq(TOKEN), any()))
                .thenThrow(new BusinessRuleViolationException(reason));

        CompanyRegistrationPresenter.Outcome outcome = presenter.register(NAME, DESCRIPTION);

        CompanyRegistrationPresenter.Outcome.Failure failure =
                assertInstanceOf(CompanyRegistrationPresenter.Outcome.Failure.class, outcome);
        assertEquals(reason, failure.reason());
    }

    @Test
    void GivenTheServiceRefusesAMissingField_WhenRegister_ThenInvalidInputCarriesItsReason() {
        String reason = "All company fields (name, description) must be provided";
        when(companyManagementService.registerCompany(eq(TOKEN), any()))
                .thenThrow(new IllegalArgumentException(reason));

        CompanyRegistrationPresenter.Outcome outcome = presenter.register(NAME, DESCRIPTION);

        CompanyRegistrationPresenter.Outcome.InvalidInput invalid =
                assertInstanceOf(CompanyRegistrationPresenter.Outcome.InvalidInput.class, outcome);
        assertEquals(reason, invalid.reason());
    }

    // The view shows the InvalidInput reason, so it must pass the same filter as every other reason.
    @Test
    void GivenAnInvalidArgumentWithoutAMessage_WhenRegister_ThenInvalidInputCarriesTheGenericText() {
        when(companyManagementService.registerCompany(eq(TOKEN), any()))
                .thenThrow(new IllegalArgumentException());

        CompanyRegistrationPresenter.Outcome outcome = presenter.register(NAME, DESCRIPTION);

        CompanyRegistrationPresenter.Outcome.InvalidInput invalid =
                assertInstanceOf(CompanyRegistrationPresenter.Outcome.InvalidInput.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, invalid.reason());
    }
}
