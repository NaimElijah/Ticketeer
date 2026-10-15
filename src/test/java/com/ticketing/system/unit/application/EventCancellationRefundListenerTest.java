package com.ticketing.system.unit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.sales.adapter.in.event.EventCancellationRefundListener;
import com.ticketing.system.sales.application.service.EventCancellationRefundService;

/**
 * Unit test for {@link EventCancellationRefundListener}, the sales inbound adapter for catalog's
 * {@link EventCancelledEvent}. The refund behaviour itself is specified by
 * {@code EventCancellationRefundServiceTest}.
 */
class EventCancellationRefundListenerTest {

    @Test
    void GivenEventCancelledEvent_WhenOn_ThenTheCancelledEventIsRefunded() {
        EventCancellationRefundService refundService = mock(EventCancellationRefundService.class);
        EventCancellationRefundListener listener = new EventCancellationRefundListener(refundService);

        listener.on(new EventCancelledEvent(10, "Concert"));

        verify(refundService).refundCancelledEvent(10, "Concert");
    }
}
