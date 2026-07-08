package com.ticketing.system.ui.dev;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Core.Application.dto.GuestSessionDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Boot-time seed for the {@code dev} profile: registers the single
 * {@code dev.member} persona the {@link DevPanel} signs in as via the
 * real {@link AuthenticationService} login path.
 *
 * <p>Idempotent — if the user already exists (e.g. a JPA-backed dev
 * environment that persists across restarts) the seeder logs and moves
 * on. With the current Memory* repositories the user needs
 * (re-)creating on every boot.
 *
 * <p>There is no dev admin persona. Admin access goes through the real
 * admin sign-in ({@code /admin/sign-in}) with the default platform admin
 * from {@code platform.admin.*} in {@code application.yml}; the DevPanel
 * "Admin" toggle drives that flow. This bean only loads under
 * {@code @Profile("dev")}.
 */
@Component
@Profile("dev")
@Order(1)
@Slf4j
public class DevUserSeeder implements ApplicationRunner {

    static final String MEMBER_USERNAME = "dev.member";
    static final String MEMBER_EMAIL    = "dev.member@dev.test";
    static final String SHARED_PASSWORD = "password123";
    static final int    SEED_AGE        = 25;

    private final AuthenticationService authenticationService;

    public DevUserSeeder(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(MEMBER_USERNAME, MEMBER_EMAIL);
    }

    private void seed(String username, String email) {
        try {
            GuestSessionDTO guest = authenticationService.startGuestSession();
            authenticationService.register(new RegisterRequestDTO(
                username, email, SHARED_PASSWORD, guest.sessionId(), SEED_AGE
            ));
            log.info("dev user seeded username={}", username);
        } catch (DuplicateUsernameException | DuplicateEmailException existing) {
            log.debug("dev user already present username={}", username);
        } catch (RuntimeException e) {
            log.warn("dev user seed failed username={} reason={}", username, e.getMessage());
        }
    }
}
