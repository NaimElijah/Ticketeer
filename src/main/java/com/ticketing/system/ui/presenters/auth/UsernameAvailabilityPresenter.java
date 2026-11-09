package com.ticketing.system.ui.presenters.auth;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.ui.support.ServiceErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Live username feedback presenter for {@code RegisterView}. Holds no
 * Vaadin imports; the view debounces input and renders the typed
 * outcome.
 *
 * <p>Mirrors the pattern set by {@code LoginPresenter} (PR upstream#299): a
 * sealed {@link Outcome} hierarchy with one variant per user-facing
 * state. Order of checks matters — empty / format / reserved /
 * existence — so the view always shows the most specific feedback
 * first. Which names are reserved is the identity service's answer
 * ({@link AuthenticationService#isUsernameReserved}), the same one
 * {@code register} enforces.
 */
@Component
public class UsernameAvailabilityPresenter {

    /**
     * Allowed username shape: 3–32 chars, letters / digits / dot /
     * underscore / hyphen. The shape matches the placeholder text on
     * {@code RegisterView} plus the dot-bearing seed users
     * ({@code naim.founder}, {@code avi.avocado}, …). The characters are
     * the ones {@code register} enforces; the length is checked here only
     * (OQ-8).
     */
    private static final Pattern USERNAME_PATTERN =
        Pattern.compile("^" + AuthenticationService.USERNAME_CHARACTERS + "{3,32}$");

    private final MemberQueryService memberQueryService;
    private final AuthenticationService authenticationService;

    /**
     * Creates the presenter.
     *
     * @param memberQueryService    answers whether a username is already taken
     * @param authenticationService answers whether a username is reserved
     */
    @Autowired
    public UsernameAvailabilityPresenter(MemberQueryService memberQueryService,
                                         AuthenticationService authenticationService) {
        this.memberQueryService = memberQueryService;
        this.authenticationService = authenticationService;
    }

    /** Whether no member may register {@code username}; {@code register} refuses such a name. */
    public boolean isReserved(String username) {
        return authenticationService.isUsernameReserved(username);
    }

    /**
     * Classifies a username as typed, for the live feedback under the field. The checks run in the
     * order of {@link Outcome}'s variants, so the first that applies wins.
     *
     * @param username the username as typed, unchanged: surrounding whitespace is not trimmed
     * @return the variant describing the name; {@link Outcome.Failure} when the existence check fails
     */
    public Outcome check(String username) {
        if (username == null || username.isBlank()) {
            return new Outcome.Empty();
        }
        // Matched as typed, not trimmed: register refuses a name with surrounding whitespace, so
        // such a name must not be called available here.
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return new Outcome.InvalidFormat();
        }
        if (isReserved(username)) {
            return new Outcome.AdminReserved();
        }
        try {
            if (memberQueryService.usernameExists(username)) {
                return new Outcome.Taken();
            }
            return new Outcome.Available();
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** What the view switches on to drive icon + helper text. */
    public sealed interface Outcome {
        /** The field is {@code null} or blank. */
        record Empty() implements Outcome { }

        /** The name has a character outside the allowed set, or a length outside 3 to 32. */
        record InvalidFormat() implements Outcome { }

        /** No member may register the name ({@link AuthenticationService#isUsernameReserved}). */
        record AdminReserved() implements Outcome { }

        /** A member already holds the name. */
        record Taken() implements Outcome { }

        /** The name is well formed, not reserved and not held by a member. */
        record Available() implements Outcome { }

        /**
         * The existence check failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
