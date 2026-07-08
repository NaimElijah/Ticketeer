package com.ticketing.system.ui.presenters.auth;

import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.ui.session.AuthSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Live username feedback presenter for {@code RegisterView}. Holds no
 * Vaadin imports; the view debounces input and renders the typed
 * outcome.
 *
 * <p>Mirrors the pattern set by {@code LoginPresenter} (PR #299): a
 * sealed {@link Outcome} hierarchy with one variant per user-facing
 * state. Order of checks matters — empty / format / admin-reserved /
 * existence — so the view always shows the most specific feedback
 * first.
 */
@Component
public class UsernameAvailabilityPresenter {

    /**
     * Allowed username shape: 3–32 chars, letters / digits / dot /
     * underscore / hyphen. The shape matches the placeholder text on
     * {@code RegisterView} plus the dot-bearing seed users
     * ({@code naim.founder}, {@code avi.avocado}, …).
     */
    private static final Pattern USERNAME_PATTERN =
        Pattern.compile("^[A-Za-z0-9._-]{3,32}$");

    private final MemberQueryService memberQueryService;

    @Autowired
    public UsernameAvailabilityPresenter(MemberQueryService memberQueryService) {
        this.memberQueryService = memberQueryService;
    }

    public Outcome check(String username) {
        if (username == null || username.isBlank()) {
            return new Outcome.Empty();
        }
        String trimmed = username.trim();
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            return new Outcome.InvalidFormat();
        }
        if (AuthSession.isAdminUsername(trimmed)) {
            return new Outcome.AdminReserved();
        }
        try {
            if (memberQueryService.usernameExists(trimmed)) {
                return new Outcome.Taken();
            }
            return new Outcome.Available();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** What the view switches on to drive icon + helper text. */
    public sealed interface Outcome {
        record Empty() implements Outcome { }
        record InvalidFormat() implements Outcome { }
        record AdminReserved() implements Outcome { }
        record Taken() implements Outcome { }
        record Available() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
