package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter;
import com.ticketing.system.ui.session.SessionIdentity;

/**
 * A policy can hold a rule kind the editor does not know, for example one the backend gained after
 * the editor was written. Loading it and saving it back must never turn that rule into another one:
 * the editor either refuses it or keeps it as it is.
 */
class PurchasePolicyEditorPresenterUnknownKindTest {

    private static final String TOKEN = "owner-jwt";
    private static final int COMPANY_ID = 7;
    private static final int NO_EVENT = 0;
    private static final boolean COMPANY_LEVEL = false;

    private CompanyManagementService companyManagementService;
    private PurchasePolicyEditorPresenter presenter;

    @BeforeEach
    void setUp() {
        companyManagementService = mock(CompanyManagementService.class);
        presenter = new PurchasePolicyEditorPresenter(companyManagementService, mock(EventManagementService.class),
                mock(SessionIdentity.class));
    }

    // ---- UI-B15: an unknown policy kind is never saved as "Age >= 0" (#187) ----

    @Test
    void GivenAPolicyWithAKindTheEditorDoesNotKnow_WhenLoadedAndSavedBack_ThenTheSavedPolicyIsTheLoadedOne() {
        PurchasePolicyDTO unknownRule = new PurchasePolicyDTO("MIN_SPEND", null, null, null, null);
        PurchasePolicyDTO stored = new PurchasePolicyDTO("AND", null, null, null,
                List.of(new PurchasePolicyDTO("AGE", 18, null, null, null), unknownRule));
        when(companyManagementService.getCompanyPurchasePolicy(TOKEN, COMPANY_ID)).thenReturn(stored);

        PurchasePolicyEditorPresenter.LoadOutcome.Success loaded = assertInstanceOf(
                PurchasePolicyEditorPresenter.LoadOutcome.Success.class,
                presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));
        presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, loaded.root());

        assertEquals(List.of(stored), savedCompanyPolicies(), "a rule kind the editor does not know must be saved as it was");
    }

    // The editor keeps an unknown kind: a policy that is nothing else is wrapped in a root group on load
    // and unwrapped on save, and must come back as it was.
    @Test
    void GivenAPolicyThatIsOnlyAKindTheEditorDoesNotKnow_WhenLoadedAndSavedBack_ThenTheSavedPolicyIsTheLoadedOne() {
        PurchasePolicyDTO stored = new PurchasePolicyDTO("MIN_SPEND", null, null, null, null);
        when(companyManagementService.getCompanyPurchasePolicy(TOKEN, COMPANY_ID)).thenReturn(stored);

        PurchasePolicyEditorPresenter.LoadOutcome.Success loaded = assertInstanceOf(
                PurchasePolicyEditorPresenter.LoadOutcome.Success.class,
                presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));
        presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, loaded.root());

        assertEquals(List.of(stored), savedCompanyPolicies(), "a rule kind the editor does not know must be saved as it was");
    }

    @Test
    void GivenAGroupWithANestedNone_WhenLoadedAndSavedBack_ThenTheSavedPolicyIsTheLoadedOne() {
        PurchasePolicyDTO stored = new PurchasePolicyDTO("AND", null, null, null,
                List.of(new PurchasePolicyDTO("AGE", 18, null, null, null),
                        new PurchasePolicyDTO("NONE", null, null, null, null)));
        when(companyManagementService.getCompanyPurchasePolicy(TOKEN, COMPANY_ID)).thenReturn(stored);

        PurchasePolicyEditorPresenter.LoadOutcome.Success loaded = assertInstanceOf(
                PurchasePolicyEditorPresenter.LoadOutcome.Success.class,
                presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));
        presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, loaded.root());

        assertEquals(List.of(stored), savedCompanyPolicies(), "a nested NONE must be saved as it was");
    }

    private List<PurchasePolicyDTO> savedCompanyPolicies() {
        return mockingDetails(companyManagementService).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("setCompanyPolicies"))
                .map(invocation -> invocation.getArgument(1, CompanyPolicyConfigDTO.class).defaultPurchasePolicy())
                .toList();
    }
}
