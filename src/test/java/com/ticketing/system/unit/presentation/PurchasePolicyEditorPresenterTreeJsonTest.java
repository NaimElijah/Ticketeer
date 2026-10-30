package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Group;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Node;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.RuleType;
import com.ticketing.system.ui.session.SessionIdentity;

/**
 * {@link PurchasePolicyEditorPresenter#toTreeJson} builds the policy tree the browser reads with
 * {@code JSON.parse}. A rule's label carries the value the user typed, so the output must be valid
 * JSON for any value, control characters included: JSON forbids a raw character below U+0020 inside
 * a string. Jackson's default parser enforces the same rule as {@code JSON.parse}.
 */
class PurchasePolicyEditorPresenterTreeJsonTest {

    // Inner tab, newline, carriage return and U+0001: trim() keeps interior control characters.
    private static final String VALUE_WITH_CONTROL_CHARACTERS = "1\t8\n\r\u0001\"\\";
    private static final String EXPECTED_LABEL = "Age at least " + VALUE_WITH_CONTROL_CHARACTERS + " years";

    private final ObjectMapper json = new ObjectMapper();
    private PurchasePolicyEditorPresenter presenter;

    @BeforeEach
    void setUp() {
        presenter = new PurchasePolicyEditorPresenter(mock(CompanyManagementService.class),
                mock(EventManagementService.class), mock(SessionIdentity.class));
    }

    @Test
    void GivenARuleValueWithControlCharacters_WhenToTreeJson_ThenTheOutputParsesAndTheLabelRoundTrips() {
        Group root = presenter.emptyPolicy();
        presenter.addRule(root, root.id());
        Node rule = root.children().get(0);
        presenter.updateRule(root, rule.id(), RuleType.AgeAtLeast, VALUE_WITH_CONTROL_CHARACTERS);

        String treeJson = presenter.toTreeJson(root);

        JsonNode tree = assertDoesNotThrow(() -> json.readTree(treeJson),
                "the policy tree must be valid JSON, but was: " + treeJson);
        assertEquals(EXPECTED_LABEL, tree.get("children").get(0).get("label").asText(),
                "the rule label must survive the round trip unchanged");
    }
}
