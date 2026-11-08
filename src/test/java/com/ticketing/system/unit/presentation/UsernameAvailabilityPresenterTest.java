package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter.Outcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.PersistenceException;
import com.ticketing.system.ui.support.ServiceErrors;
import static org.mockito.Mockito.verifyNoInteractions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsernameAvailabilityPresenterTest {

    private MemberQueryService memberQueryService;
    private AuthenticationService authenticationService;
    private UsernameAvailabilityPresenter presenter;

    @BeforeEach
    void setUp() {
        memberQueryService = mock(MemberQueryService.class);
        authenticationService = mock(AuthenticationService.class);
        presenter = new UsernameAvailabilityPresenter(memberQueryService, authenticationService);
    }

    @Test
    void GivenNullEmptyOrBlankInput_WhenCheck_ThenReturnsEmpty() {
        assertInstanceOf(Outcome.Empty.class, presenter.check(null));
        assertInstanceOf(Outcome.Empty.class, presenter.check(""));
        assertInstanceOf(Outcome.Empty.class, presenter.check("   "));
    }

    @Test
    void GivenATooShortName_WhenCheck_ThenReturnsInvalidFormat() {
        assertInstanceOf(Outcome.InvalidFormat.class, presenter.check("ab"));
    }

    @Test
    void GivenDisallowedCharacters_WhenCheck_ThenReturnsInvalidFormat() {
        assertInstanceOf(Outcome.InvalidFormat.class, presenter.check("with space"));
        assertInstanceOf(Outcome.InvalidFormat.class, presenter.check("with#symbol"));
    }

    @Test
    void GivenAnAvailableNameWithSurroundingWhitespace_WhenCheck_ThenInvalidFormat() {
        when(memberQueryService.usernameExists("brand.new")).thenReturn(false);

        assertInstanceOf(Outcome.InvalidFormat.class, presenter.check(" brand.new"),
                "register refuses a name with surrounding whitespace, so it must not be called available");
        assertInstanceOf(Outcome.InvalidFormat.class, presenter.check("brand.new "));
    }

    @Test
    void GivenAnAdminPoolName_WhenCheck_ThenReturnsAdminReserved() {
        // Which names are reserved is the identity service's answer, the one register enforces.
        when(authenticationService.isUsernameReserved("admin")).thenReturn(true);
        when(authenticationService.isUsernameReserved("platform.admin")).thenReturn(true);

        assertInstanceOf(Outcome.AdminReserved.class, presenter.check("admin"));
        assertInstanceOf(Outcome.AdminReserved.class, presenter.check("platform.admin"));
    }

    @Test
    void GivenAnExistingMemberName_WhenCheck_ThenReturnsTaken() {
        when(memberQueryService.usernameExists("naim.founder")).thenReturn(true);

        assertInstanceOf(Outcome.Taken.class, presenter.check("naim.founder"));
    }

    @Test
    void GivenANewValidName_WhenCheck_ThenReturnsAvailable() {
        when(memberQueryService.usernameExists("brand.new")).thenReturn(false);

        assertInstanceOf(Outcome.Available.class, presenter.check("brand.new"));
    }

    @Test
    void GivenTheServiceThrowsIllegalStateException_WhenCheck_ThenFailureCarriesItsMessage() {
        when(memberQueryService.usernameExists("anyone")).thenThrow(new IllegalStateException("Name checks are paused"));

        Outcome.Failure failure = assertInstanceOf(Outcome.Failure.class, presenter.check("anyone"));
        assertEquals("Name checks are paused", failure.reason());
    }

    // ---- an internal failure reads as generic text, never as its own (#189) ----

    @Test
    void GivenTheLookupFailsInternally_WhenCheck_ThenFailureCarriesTheGenericText() {
        when(memberQueryService.usernameExists("anyone"))
            .thenThrow(new PersistenceException("could not prepare statement [select * from users]"));

        assertEquals(new Outcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE), presenter.check("anyone"));
    }

    // ---- the length bounds, 3 to 32 characters (#189) ----

    @ParameterizedTest
    @ValueSource(ints = {3, 32})
    void GivenAFreeNameOnALengthBound_WhenCheck_ThenAvailable(int length) {
        String name = "a".repeat(length);
        when(memberQueryService.usernameExists(name)).thenReturn(false);

        assertEquals(new Outcome.Available(), presenter.check(name));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 33})
    void GivenANameJustOutsideTheLengthBounds_WhenCheck_ThenInvalidFormatAndNoLookup(int length) {
        assertEquals(new Outcome.InvalidFormat(), presenter.check("a".repeat(length)));
        verifyNoInteractions(memberQueryService);
    }
}
