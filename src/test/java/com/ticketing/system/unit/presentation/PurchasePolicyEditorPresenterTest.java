package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.ContextOutcome;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Group;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.LoadOutcome;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Node;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Op;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Rule;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.RuleType;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.SaveOutcome;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Validity;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

import jakarta.persistence.PersistenceException;

/**
 * {@link PurchasePolicyEditorPresenter}'s context, load and save outcomes, and the tree operations the
 * editor's buttons call: validation, deletion and the plain-English preview (#189). The tree JSON and a
 * kind the editor does not know have classes of their own ({@link PurchasePolicyEditorPresenterTreeJsonTest},
 * {@link PurchasePolicyEditorPresenterUnknownKindTest}).
 */
class PurchasePolicyEditorPresenterTest {

    private static final String TOKEN = "owner-token";
    private static final int COMPANY_ID = 7;
    private static final int EVENT_ID = 9;
    private static final int NO_EVENT = 0;
    private static final boolean EVENT_LEVEL = true;
    private static final boolean COMPANY_LEVEL = false;
    private static final PurchasePolicyDTO AGE_18 = new PurchasePolicyDTO("AGE", 18, null, null, null);
    private static final PurchasePolicyDTO AT_MOST_4 = new PurchasePolicyDTO("MAX_TICKETS", null, null, 4, null);
    private static final PurchasePolicyDTO NONE = new PurchasePolicyDTO("NONE", null, null, null, null);

    private CompanyManagementService companies;
    private EventManagementService events;
    private PurchasePolicyEditorPresenter presenter;

    @BeforeEach
    void setUp() {
        companies = mock(CompanyManagementService.class);
        events = mock(EventManagementService.class);
        presenter = new PurchasePolicyEditorPresenter(companies, events, mock(SessionIdentity.class));
    }

    // ---- resolveContext: which companies the editor offers ----

    @Test
    void GivenANullToken_WhenResolveContext_ThenNotAuthenticatedAndNoServiceCall() {
        assertEquals(new ContextOutcome.NotAuthenticated(), presenter.resolveContext(null));
        verifyNoInteractions(companies, events);
    }

    @Test
    void GivenTheCallersCompanies_WhenResolveContext_ThenReadyWithThem() {
        List<MyCompanyDTO> mine = List.of(new MyCompanyDTO(COMPANY_ID, "Acme", "Founder"),
                new MyCompanyDTO(11, "Globex", "Manager"));
        when(companies.findMyCompanies(TOKEN)).thenReturn(mine);

        assertEquals(new ContextOutcome.Ready(mine), presenter.resolveContext(TOKEN));
    }

    @Test
    void GivenNoCompany_WhenResolveContext_ThenNoCompany() {
        when(companies.findMyCompanies(TOKEN)).thenReturn(List.of());

        assertEquals(new ContextOutcome.NoCompany(), presenter.resolveContext(TOKEN));
    }

    @Test
    void GivenARevokedToken_WhenResolveContext_ThenNotAuthenticated() {
        when(companies.findMyCompanies(TOKEN)).thenThrow(new InvalidTokenException("revoked"));

        assertEquals(new ContextOutcome.NotAuthenticated(), presenter.resolveContext(TOKEN));
    }

    @Test
    void GivenAnExpiredSession_WhenResolveContext_ThenNotAuthenticated() {
        when(companies.findMyCompanies(TOKEN)).thenThrow(new SessionExpiredException());

        assertEquals(new ContextOutcome.NotAuthenticated(), presenter.resolveContext(TOKEN));
    }

    @Test
    void GivenAnInternalFailure_WhenResolveContext_ThenFailureCarriesTheGenericText() {
        when(companies.findMyCompanies(TOKEN)).thenThrow(new PersistenceException("could not prepare statement"));

        assertEquals(new ContextOutcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE), presenter.resolveContext(TOKEN));
    }

    // ---- load: the stored policy as an editable tree ----

    @Test
    void GivenANullToken_WhenLoad_ThenNotAuthenticatedAndNoServiceCall() {
        assertEquals(new LoadOutcome.NotAuthenticated(), presenter.load(null, COMPANY_ID, EVENT_ID, EVENT_LEVEL));
        verifyNoInteractions(companies, events);
    }

    @Test
    void GivenTheEventLevelEditor_WhenLoad_ThenTheEventsPolicyIsReadAndNotTheCompanys() {
        when(events.getEventPurchasePolicy(TOKEN, COMPANY_ID, EVENT_ID)).thenReturn(AGE_18);

        Group root = loaded(presenter.load(TOKEN, COMPANY_ID, EVENT_ID, EVENT_LEVEL));

        assertEquals(List.of("AgeAtLeast 18"), describe(root.children()));
        verify(companies, never()).getCompanyPurchasePolicy(anyString(), anyInt());
    }

    // With no event chosen yet, the event-level editor has no event policy to read.
    @Test
    void GivenTheEventLevelEditorWithNoEvent_WhenLoad_ThenTheCompanysPolicyIsRead() {
        when(companies.getCompanyPurchasePolicy(TOKEN, COMPANY_ID)).thenReturn(AT_MOST_4);

        Group root = loaded(presenter.load(TOKEN, COMPANY_ID, NO_EVENT, EVENT_LEVEL));

        assertEquals(List.of("QuantityAtMost 4"), describe(root.children()));
        verify(events, never()).getEventPurchasePolicy(anyString(), anyInt(), anyInt());
    }

    @Test
    void GivenAStoredOrOfTwoRules_WhenLoad_ThenTheRootIsThatOrGroup() {
        when(companies.getCompanyPurchasePolicy(TOKEN, COMPANY_ID))
                .thenReturn(new PurchasePolicyDTO("OR", null, null, null, List.of(AGE_18, AT_MOST_4)));

        Group root = loaded(presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));

        assertEquals(Op.OR, root.op());
        assertEquals(List.of("AgeAtLeast 18", "QuantityAtMost 4"), describe(root.children()));
    }

    @Test
    void GivenNoStoredPolicy_WhenLoad_ThenAnEmptyAndRoot() {
        when(companies.getCompanyPurchasePolicy(TOKEN, COMPANY_ID)).thenReturn(null);

        Group root = loaded(presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));

        assertEquals(Op.AND, root.op());
        assertEquals(List.of(), root.children());
    }

    @Test
    void GivenARevokedToken_WhenLoad_ThenNotAuthenticated() {
        when(companies.getCompanyPurchasePolicy(TOKEN, COMPANY_ID)).thenThrow(new InvalidTokenException("revoked"));

        assertEquals(new LoadOutcome.NotAuthenticated(), presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));
    }

    @Test
    void GivenAnExpiredSession_WhenLoad_ThenNotAuthenticated() {
        when(events.getEventPurchasePolicy(TOKEN, COMPANY_ID, EVENT_ID)).thenThrow(new SessionExpiredException());

        assertEquals(new LoadOutcome.NotAuthenticated(), presenter.load(TOKEN, COMPANY_ID, EVENT_ID, EVENT_LEVEL));
    }

    @Test
    void GivenTheServiceRefusesTheCaller_WhenLoad_ThenFailureCarriesTheRule() {
        UnauthorizedActionException refusal = new UnauthorizedActionException("edit policies");
        when(companies.getCompanyPurchasePolicy(TOKEN, COMPANY_ID)).thenThrow(refusal);

        assertEquals(new LoadOutcome.Failure(refusal.getMessage()),
                presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));
    }

    @Test
    void GivenAnInternalFailure_WhenLoad_ThenFailureCarriesTheGenericText() {
        when(companies.getCompanyPurchasePolicy(TOKEN, COMPANY_ID))
                .thenThrow(new PersistenceException("could not read column [purchase_policy]"));

        assertEquals(new LoadOutcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
                presenter.load(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL));
    }

    // ---- save: the tree as the policy the service stores ----

    @Test
    void GivenANullToken_WhenSave_ThenNotAuthenticatedAndNothingIsSaved() {
        assertEquals(new SaveOutcome.NotAuthenticated(),
                presenter.save(null, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, rootWithAgeRule("18")));
        verifyNoInteractions(companies, events);
    }

    @Test
    void GivenARuleWithoutAWholeNumber_WhenSave_ThenInvalidWithTheReasonAndNothingIsSaved() {
        assertEquals(new SaveOutcome.Invalid("Rule value must be a whole number."),
                presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, rootWithAgeRule("eighteen")));
        verifyNoInteractions(companies, events);
    }

    @Test
    void GivenTheCompanyLevelEditor_WhenSave_ThenTheCompanysPolicyIsSetAndNotAnEvents() {
        assertEquals(new SaveOutcome.Success(),
                presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, rootWithAgeRule("18")));

        verify(companies).setCompanyPolicies(TOKEN, new CompanyPolicyConfigDTO(COMPANY_ID, AGE_18, List.of()));
        verifyNoInteractions(events);
    }

    @Test
    void GivenTheEventLevelEditor_WhenSave_ThenTheEventsPolicyIsSetAndNotTheCompanys() {
        assertEquals(new SaveOutcome.Success(),
                presenter.save(TOKEN, COMPANY_ID, EVENT_ID, EVENT_LEVEL, rootWithAgeRule("18")));

        verify(events).setEventPolicies(TOKEN, new EventPolicyConfigDTO(COMPANY_ID, EVENT_ID, AGE_18));
        verify(companies, never()).setCompanyPolicies(anyString(), any());
    }

    @Test
    void GivenAnEmptyRoot_WhenSave_ThenTheSavedPolicyIsNone() {
        presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, presenter.emptyPolicy());

        verify(companies).setCompanyPolicies(TOKEN, new CompanyPolicyConfigDTO(COMPANY_ID, NONE, List.of()));
    }

    @Test
    void GivenAnOrOfTwoRules_WhenSave_ThenTheSavedPolicyIsThatOr() {
        Group root = rootWithAgeRule("21");
        presenter.updateGroupOp(root, root.id(), Op.OR);
        presenter.addRule(root, root.id());
        presenter.updateRule(root, root.children().get(1).id(), RuleType.QuantityAtLeast, " 2 ");

        presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, root);

        PurchasePolicyDTO expected = new PurchasePolicyDTO("OR", null, null, null, List.of(
                new PurchasePolicyDTO("AGE", 21, null, null, null),
                new PurchasePolicyDTO("MIN_TICKETS", null, 2, null, null)));
        verify(companies).setCompanyPolicies(TOKEN, new CompanyPolicyConfigDTO(COMPANY_ID, expected, List.of()));
    }

    @Test
    void GivenARevokedToken_WhenSave_ThenNotAuthenticated() {
        doThrow(new InvalidTokenException("revoked")).when(companies).setCompanyPolicies(anyString(), any());

        assertEquals(new SaveOutcome.NotAuthenticated(),
                presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, rootWithAgeRule("18")));
    }

    @Test
    void GivenAnExpiredSession_WhenSave_ThenNotAuthenticated() {
        doThrow(new SessionExpiredException()).when(events).setEventPolicies(anyString(), any());

        assertEquals(new SaveOutcome.NotAuthenticated(),
                presenter.save(TOKEN, COMPANY_ID, EVENT_ID, EVENT_LEVEL, rootWithAgeRule("18")));
    }

    @Test
    void GivenTheServiceRefusesThePolicy_WhenSave_ThenFailureCarriesTheRule() {
        BusinessRuleViolationException rule = new BusinessRuleViolationException("A minimum age must be at least 0.");
        doThrow(rule).when(companies).setCompanyPolicies(anyString(), any());

        assertEquals(new SaveOutcome.Failure(rule.getMessage()),
                presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, rootWithAgeRule("18")));
    }

    @Test
    void GivenAnInternalFailure_WhenSave_ThenFailureCarriesTheGenericText() {
        doThrow(new PersistenceException("could not execute statement [update production_companies]"))
                .when(companies).setCompanyPolicies(anyString(), any());

        assertEquals(new SaveOutcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
                presenter.save(TOKEN, COMPANY_ID, NO_EVENT, COMPANY_LEVEL, rootWithAgeRule("18")));
    }

    // ---- validate ----

    @Test
    void GivenAnEmptyRoot_WhenValidate_ThenValidAsNoRestrictions() {
        assertEquals(new Validity(true, "Valid"), presenter.validate(presenter.emptyPolicy()));
    }

    @Test
    void GivenARuleWithABlankValue_WhenValidate_ThenInvalidSayingTheValueIsMissing() {
        assertEquals(new Validity(false, "A rule is missing its value."), presenter.validate(rootWithAgeRule("  ")));
    }

    @Test
    void GivenARuleWithADecimalValue_WhenValidate_ThenInvalidSayingItMustBeAWholeNumber() {
        assertEquals(new Validity(false, "Rule value must be a whole number."), presenter.validate(rootWithAgeRule("2.5")));
    }

    @Test
    void GivenAnEmptyNestedOrGroup_WhenValidate_ThenInvalidNamingTheGroup() {
        Group root = rootWithAgeRule("18");
        presenter.addGroup(root, root.id());
        presenter.updateGroupOp(root, root.children().get(1).id(), Op.OR);

        assertEquals(new Validity(false, "Empty OR group — add at least one rule."), presenter.validate(root));
    }

    @Test
    void GivenAnInvalidRuleDeepInsideAGroup_WhenValidate_ThenThatRuleMakesTheTreeInvalid() {
        Group root = rootWithAgeRule("18");
        presenter.addGroup(root, root.id());
        Node nested = root.children().get(1);
        presenter.addRule(root, nested.id());
        presenter.updateRule(root, ((Group) nested).children().get(0).id(), RuleType.QuantityAtMost, "x");

        assertEquals(new Validity(false, "Rule value must be a whole number."), presenter.validate(root));
    }

    @Test
    void GivenRulesWithWholeNumbersInsideAGroup_WhenValidate_ThenValid() {
        Group root = rootWithAgeRule("18");
        presenter.addGroup(root, root.id());
        presenter.addRule(root, root.children().get(1).id());

        assertEquals(new Validity(true, "Valid"), presenter.validate(root));
    }

    // ---- canDelete and deleteNode ----

    @Test
    void GivenTheRoot_WhenCanDelete_ThenFalse() {
        Group root = rootWithAgeRule("18");

        assertFalse(presenter.canDelete(root, root.id()));
    }

    @Test
    void GivenATopLevelRuleAndANestedRule_WhenCanDelete_ThenTrueForBoth() {
        Group root = rootWithAgeRule("18");
        presenter.addGroup(root, root.id());
        Group nested = (Group) root.children().get(1);
        presenter.addRule(root, nested.id());

        assertTrue(presenter.canDelete(root, root.children().get(0).id()), "a top-level rule");
        assertTrue(presenter.canDelete(root, nested.children().get(0).id()), "a rule inside a nested group");
    }

    @Test
    void GivenAnIdNotInTheTree_WhenCanDelete_ThenFalse() {
        assertFalse(presenter.canDelete(rootWithAgeRule("18"), "n-unknown"));
    }

    @Test
    void GivenANestedRule_WhenDeleteNode_ThenOnlyThatRuleGoes() {
        Group root = rootWithAgeRule("18");
        presenter.addGroup(root, root.id());
        Group nested = (Group) root.children().get(1);
        presenter.addRule(root, nested.id());
        presenter.addRule(root, nested.id());
        Node doomed = nested.children().get(0);
        Node kept = nested.children().get(1);

        presenter.deleteNode(root, doomed.id());

        assertEquals(List.of(kept), nested.children());
        assertEquals(2, root.children().size(), "the top level keeps its rule and the group");
        assertNull(presenter.findNode(root, doomed.id()), "the deleted rule is gone from the tree");
    }

    @Test
    void GivenTheRoot_WhenDeleteNode_ThenTheTreeIsUnchanged() {
        Group root = rootWithAgeRule("18");
        List<Node> before = List.copyOf(root.children());

        presenter.deleteNode(root, root.id());

        assertEquals(before, root.children());
    }

    // ---- toPlainEnglishHtml: the preview the editor shows as HTML ----

    @Test
    void GivenAnEmptyRoot_WhenToPlainEnglishHtml_ThenItSaysThereAreNoRestrictions() {
        assertEquals("No restrictions yet — click the root group and add a rule to get started.",
                presenter.toPlainEnglishHtml(presenter.emptyPolicy()));
    }

    @Test
    void GivenTwoRulesAndAnEmptyOrGroup_WhenToPlainEnglishHtml_ThenEachIsJoinedByAnd() {
        Group root = rootWithAgeRule("18");
        presenter.addRule(root, root.id());
        presenter.updateRule(root, root.children().get(1).id(), RuleType.QuantityAtMost, "4");
        presenter.addGroup(root, root.id());
        presenter.updateGroupOp(root, root.children().get(2).id(), Op.OR);

        assertEquals("Buyer must satisfy <b>Age at least 18 years</b> <span class='or'>AND</span> "
                + "<b>Quantity at most 4 tickets</b> <span class='or'>AND</span> (<i>(empty OR group)</i>).",
                presenter.toPlainEnglishHtml(root));
    }

    // The preview is set as HTML, and a rule's value is whatever the owner typed before validation.
    @Test
    void GivenARuleValueWithMarkup_WhenToPlainEnglishHtml_ThenItIsEscaped() {
        Group root = rootWithAgeRule("<img src=x onerror=alert(1)>&");

        assertEquals("Buyer must satisfy <b>Age at least &lt;img src=x onerror=alert(1)&gt;&amp; years</b>.",
                presenter.toPlainEnglishHtml(root));
    }

    private Group rootWithAgeRule(String value) {
        Group root = presenter.emptyPolicy();
        presenter.addRule(root, root.id());
        presenter.updateRule(root, root.children().get(0).id(), RuleType.AgeAtLeast, value);
        return root;
    }

    private static Group loaded(LoadOutcome outcome) {
        return assertInstanceOf(LoadOutcome.Success.class, outcome, "the policy must load: " + outcome).root();
    }

    private static List<String> describe(List<Node> nodes) {
        return nodes.stream().map(node -> {
            Rule rule = assertInstanceOf(Rule.class, node, "expected a rule: " + node);
            return rule.type() + " " + rule.value();
        }).toList();
    }
}
