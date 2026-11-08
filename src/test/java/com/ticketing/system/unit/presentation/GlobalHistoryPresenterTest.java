package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.support.ServiceErrors;

import jakarta.persistence.PersistenceException;

/**
 * {@link GlobalHistoryPresenter#loadAllRecords()} lists every order on the platform for the signed-in admin, one
 * row per order across every company's history (#189). Its outage path is in {@code FailedListLoadsTest}.
 */
class GlobalHistoryPresenterTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final LocalDateTime PURCHASED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private SystemAdminService admin;
    private GlobalHistoryPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        admin = mock(SystemAdminService.class);
        presenter = new GlobalHistoryPresenter(admin, new SalesSummaryCalculator());
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenNoSignedInAdmin_WhenLoadAllRecords_ThenNoRecordsAndTheServiceIsNotAsked() {
        assertEquals(new ListOutcome.Loaded<>(List.of()), presenter.loadAllRecords());
        verifyNoInteractions(admin);
    }

    @Test
    void GivenTwoCompaniesHistories_WhenLoadAllRecords_ThenEveryOrderOfBothInOrder() {
        signInAdmin();
        PurchaseRecordDTO first = order(1);
        PurchaseRecordDTO second = order(2);
        PurchaseRecordDTO third = order(3);
        when(admin.viewGlobalHistory(eq(ADMIN_TOKEN), any())).thenReturn(List.of(
            new PurchaseHistoryDTO(List.of(first, second)), new PurchaseHistoryDTO(List.of(third))));

        assertEquals(new ListOutcome.Loaded<>(List.of(first, second, third)), presenter.loadAllRecords());
    }

    @Test
    void GivenTheServiceRefusesTheCaller_WhenLoadAllRecords_ThenFailureCarriesTheRule() {
        signInAdmin();
        UnauthorizedActionException refusal = new UnauthorizedActionException("view global history");
        when(admin.viewGlobalHistory(eq(ADMIN_TOKEN), any())).thenThrow(refusal);

        assertEquals(new ListOutcome.Failure<>(refusal.getMessage()), presenter.loadAllRecords());
    }

    @Test
    void GivenAnInternalFailure_WhenLoadAllRecords_ThenFailureCarriesTheGenericText() {
        signInAdmin();
        when(admin.viewGlobalHistory(eq(ADMIN_TOKEN), any()))
            .thenThrow(new PersistenceException("could not extract ResultSet [select * from order_receipts]"));

        assertEquals(new ListOutcome.Failure<>(ServiceErrors.GENERIC_FAILURE_MESSAGE), presenter.loadAllRecords());
    }

    private static void signInAdmin() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, 1, "admin", true));
    }

    private static PurchaseRecordDTO order(int id) {
        return new PurchaseRecordDTO(id, 42, null, PURCHASED_AT, 100.0, PurchaseHistoryDTO.RefundStatus.NONE,
            List.of(), List.of(), "dana");
    }
}
