package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.CompanyRegistrationDTO;
import com.ticketing.system.Core.Application.dto.EventCreationDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.Core.Application.services.CompanyManagementService;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.Core.Domain.events.Location;
import com.ticketing.system.Core.Domain.events.ShowDate;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.orders.OrderReceipt;
import com.ticketing.system.Core.Domain.orders.ReceiptLine;
import com.ticketing.system.Presentation.presenters.company.CompanySalesPresenter;

/**
 * Issue #430 [Eval 9] — after a successful purchase the sale must appear in the owner's
 * sales report (real receipt data, not mocked). These tests drive the exact path the
 * {@code owner/sales} view uses ({@link CompanySalesPresenter#load}) and assert that a
 * freshly-persisted purchase shows up on a subsequent {@code load()} — which is precisely
 * what the new Refresh button does at runtime. Existing suites hit the service directly;
 * this closes the gap by exercising the presenter and the reload (refresh) contract.
 */
@SpringBootTest
@ActiveProfiles("test")
class CompanySalesReportAcceptanceTest {

    @Autowired
    private AuthenticationService authService;
    @Autowired
    private CompanyManagementService companyService;
    @Autowired
    private EventManagementService eventManagementService;
    @Autowired
    private IOrderReceiptRepository orderReceiptRepository;
    @Autowired
    private CompanySalesPresenter presenter;

    private AuthTokenDTO registerAndLoginMember(String name) {
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 20));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
    }

    private int addEventForCompany(AuthTokenDTO owner, int companyId, String eventName) {
        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, eventName, "desc", List.of("Artist"),
                EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(3))),
                null));
        return Integer.parseInt(event.eventId());
    }

    /** Persists a completed purchase of {@code eventId} for {@code total} (one standing ticket). */
    private void recordPurchase(AuthTokenDTO buyer, int eventId, double total) {
        orderReceiptRepository.save(OrderReceipt.forMember(
                orderReceiptRepository.nextId(), buyer.userId(), total,
                List.of(new ReceiptLine(1, total, eventId, 1, null, LocalDateTime.now()))));
    }

    private CompanySalesPresenter.Outcome.Success loadSuccess(AuthTokenDTO owner) {
        CompanySalesPresenter.Outcome outcome = presenter.load(owner.token(), null);
        return assertInstanceOf(CompanySalesPresenter.Outcome.Success.class, outcome);
    }

    @Test
    void GivenCompletedPurchase_WhenOwnerReloadsSalesReport_ThenSaleAppears() {
        AuthTokenDTO owner = registerAndLoginMember("salesReportOwner1");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SalesReportCo1", "desc")).companyId();
        int eventId = addEventForCompany(owner, companyId, "Report Event");

        // Baseline: the report loads cleanly with no sales yet.
        CompanySalesPresenter.Outcome.Success before = loadSuccess(owner);
        assertEquals("SalesReportCo1", before.companyName());
        assertTrue(before.sales().records().isEmpty(), "no sales expected before any purchase");

        // A buyer completes a purchase of the company's event.
        recordPurchase(owner, eventId, 80.0);

        // Reloading the report (== clicking Refresh) surfaces the recent sale.
        CompanySalesPresenter.Outcome.Success after = loadSuccess(owner);
        assertEquals(1, after.sales().records().size());
        assertEquals(80.0, after.sales().records().get(0).totalPaid());
    }

    @Test
    void GivenSecondPurchase_WhenOwnerReloadsAgain_ThenBothSalesAppear() {
        AuthTokenDTO owner = registerAndLoginMember("salesReportOwner2");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("SalesReportCo2", "desc")).companyId();
        int eventId = addEventForCompany(owner, companyId, "Report Event 2");

        recordPurchase(owner, eventId, 80.0);
        assertEquals(1, loadSuccess(owner).sales().records().size());

        // A second purchase lands after the report was already viewed once.
        recordPurchase(owner, eventId, 50.0);

        // A further reload reflects the new sale too (no stale snapshot).
        assertEquals(2, loadSuccess(owner).sales().records().size());
    }
}
