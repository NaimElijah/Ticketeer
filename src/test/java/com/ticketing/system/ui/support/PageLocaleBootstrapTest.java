package com.ticketing.system.ui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.testutil.DefaultLocale;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinService;

/**
 * Vaadin's own components, the date pickers among them, format and parse in the UI's locale, which Vaadin
 * starts at the server JVM's default when the app has no {@code I18NProvider} (ADR-027, #189).
 */
class PageLocaleBootstrapTest {

    @AfterEach
    void forgetTheCurrentUi() {
        UI.setCurrent(null);
    }

    @Test
    void GivenAServerWhoseLocaleIsGerman_WhenAUiStarts_ThenItAndItsDatePickersUseThePageLocale() {
        try (DefaultLocale.Swap ignored = DefaultLocale.swapTo(Locale.GERMANY)) {
            UI ui = new UI();
            assertEquals(Locale.GERMANY, ui.getLocale(), "precondition: a new UI starts in the server's locale");
            DatePicker picker = new DatePicker();
            ui.add(picker);
            UI.setCurrent(ui);

            start(ui);

            assertEquals(DisplayLocale.PAGE, ui.getLocale());
            assertEquals(DisplayLocale.PAGE, picker.getLocale());
        }
    }

    // Registers the bootstrap as Vaadin does, then starts the UI through every UI listener it added.
    private static void start(UI ui) {
        VaadinService service = mock(VaadinService.class);
        ServiceInitEvent init = mock(ServiceInitEvent.class);
        when(init.getSource()).thenReturn(service);
        new PageLocaleBootstrap().serviceInit(init);

        ArgumentCaptor<UIInitListener> listeners = ArgumentCaptor.forClass(UIInitListener.class);
        verify(service, atLeast(0)).addUIInitListener(listeners.capture());
        UIInitEvent started = new UIInitEvent(ui, service);
        listeners.getAllValues().forEach(listener -> listener.uiInit(started));
    }
}
