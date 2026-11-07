package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;

/**
 * {@link ViewHarness}'s router answers as Flow's does: a link to a route whose mandatory parameter it does not
 * give has no URL, so building it throws, as the /owner workspace crash did in production. A stub that gave
 * every target a URL let such a link pass every view test (#189).
 */
class ViewHarnessRouterTest {

    // ReceiptView's route is "receipt/:receiptId".
    private static final String RECEIPT_ID = "receiptId";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenARouteWithAMandatoryParameter_WhenALinkTargetsItWithoutTheParameter_ThenBuildingTheLinkThrows() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new RouterLink("Receipt", ReceiptView.class));

        assertEquals("Cannot set route for non registered class " + ReceiptView.class.getName(), thrown.getMessage(),
                "Flow's RouterLink refuses a target it finds no URL for");
    }

    @Test
    void GivenARouteWithAMandatoryParameter_WhenALinkGivesTheParameter_ThenTheLinkGetsAUrlWithTheParameterInItsPath() {
        RouterLink link = new RouterLink("Receipt", ReceiptView.class, new RouteParameters(RECEIPT_ID, "7"));

        assertEquals("stub/receipt/7", link.getHref());
    }

    // A view test reads where a link leads from its URL, so two links to different pages must differ (#189).
    @Test
    void GivenLinksToTwoRoutes_WhenBuilt_ThenEachUrlNamesItsOwnRoutesPath() {
        RouterLink browse = new RouterLink("Browse", BrowseEventsView.class);
        RouterLink event = new RouterLink("Event", EventDetailsView.class, new RouteParameters("eventId", "5"));

        assertEquals("stub/browse", browse.getHref());
        assertEquals("stub/events/5", event.getHref());
        assertEquals(event.getHref(), ViewHarness.linkTo(EventDetailsView.class, "eventId", "5"));
    }
}
