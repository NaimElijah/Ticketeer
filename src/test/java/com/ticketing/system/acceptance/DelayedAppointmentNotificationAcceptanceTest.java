package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.AppointmentResponseDTO;
import com.ticketing.system.Core.Application.dto.CompanyRegistrationDTO;
import com.ticketing.system.Core.Application.dto.LoginDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.Core.Application.dto.NotificationDTO;
import com.ticketing.system.Core.Application.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.identity.application.port.out.UserRepository;

/**
 * Eval point 2 — delayed (offline) notifications.
 *
 * <p>u1 appoints u2 as a company owner while u2 is offline. The appointment notification is
 * stored PENDING; when u2 next logs in it is delivered <b>immediately</b> in the login payload
 * (which the UI drops straight into the notification bell), and u2 can then confirm the
 * appointment. This drives the real service path
 * ({@code appointOwner} → login {@code deliverPending} → {@code respondToAppointment}).
 */
@SpringBootTest
@ActiveProfiles("test")
class DelayedAppointmentNotificationAcceptanceTest {

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ProductionCompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void GivenOfflineAppointee_WhenTheyLogIn_ThenSeeAppointmentNotificationAndCanConfirm() {
        // --- u1: a founder/owner with a company ---
        AuthTokenDTO u1 = registerAndLogin("apptOwner");
        int companyId = companyService.registerCompany(
                u1.token(), new CompanyRegistrationDTO("ApptCo", "desc")).companyId();

        // --- u2: registered but OFFLINE (never logged in / no delivery yet) ---
        String u2Name = register("apptTarget");
        int u2Id = userRepository.findByUsername(u2Name).orElseThrow().getUserId();
        assertTrue(notificationRepository.findByRecipientAndStatus(u2Id, NotificationStatus.PENDING).isEmpty(),
                "no notifications before the appointment");

        // --- u1 appoints u2 as owner while u2 is offline -> a PENDING notification is stored ---
        companyService.appointOwner(u1.token(), new OwnerAppointmentRequestDTO(companyId, u2Id));
        assertEquals(1, notificationRepository.findByRecipientAndStatus(u2Id, NotificationStatus.PENDING).size(),
                "the offline appointment must be queued as a pending notification");

        // --- u2 logs in -> the delayed notification is delivered immediately in the login payload ---
        LoginDTO u2Login = login(u2Name);
        List<NotificationDTO> delivered = u2Login.notifications();
        assertTrue(delivered.stream().anyMatch(n -> "OWNER_APPOINTMENT_PENDING".equals(n.type())),
                "u2 must immediately see the owner-appointment notification on login; got: " + delivered);
        assertTrue(notificationRepository.findByRecipientAndStatus(u2Id, NotificationStatus.PENDING).isEmpty(),
                "delivered notifications move out of PENDING (no re-delivery)");

        // --- u2 confirms the appointment -> u2 is now a company owner ---
        companyService.respondToAppointment(
                u2Login.authToken().token(), new AppointmentResponseDTO(companyId, true));
        assertTrue(companyRepository.getCompanyById(companyId).isOwner(u2Id),
                "after confirming, u2 is an owner of the company");
        assertFalse(companyRepository.getCompanyById(companyId).isOwner(-1));
    }

    // ---- helpers --------------------------------------------------------

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = register(baseName);
        return login(name).authToken();
    }

    /** Register a fresh user and return the unique username (does NOT establish a session). */
    private String register(String baseName) {
        String name = baseName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 30));
        return name;
    }

    private LoginDTO login(String name) {
        String sid = authService.startGuestSession().sessionId();
        return authService.login(new LoginRequestDTO(name, "Password1", sid));
    }
}
