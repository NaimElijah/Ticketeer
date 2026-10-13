package com.ticketing.system.ui.components.buyer;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Modal that renders a single ticket as a real ticket-stub card —
 * gradient header keyed to category, event title + datetime, venue/zone/
 * seat/price details block, dashed perforation, and a barcode panel
 * with the order reference. Opened from {@code MyAccountView}'s tickets
 * grid and from {@code ReceiptView}.
 */
public final class BzTicketDialog {

    public record TicketInfo(
        String event,
        String category,
        String date,
        String venue,
        String zone,
        String seat,
        String price,
        String barcode,
        String orderId
    ) { }

    private BzTicketDialog() { }

    public static void show(TicketInfo ticket) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Your ticket");
        d.setWidth("min(440px, 100vw - 32px)");
        d.setMaxWidth("92vw");
        d.add(buildCard(ticket));

        Button close = new Button("Close", e -> d.close());
        close.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        d.getFooter().add(close);
        d.open();
    }

    private static Component buildCard(TicketInfo t) {
        Div card = new Div();
        card.getStyle()
            .set("border", "1px solid var(--border)")
            .set("border-radius", "14px")
            .set("overflow", "hidden")
            .set("box-shadow", "0 8px 24px -8px rgba(15,23,42,0.18)")
            .set("background", "#fff");

        card.add(buildHeader(t), buildBody(t), buildPerforation(), buildStub(t));
        return card;
    }

    private static Div buildHeader(TicketInfo t) {
        String[] grad = gradientFor(t.category);
        Div header = new Div();
        header.getStyle()
            .set("background", "linear-gradient(135deg, " + grad[0] + ", " + grad[1] + ")")
            .set("color", "#fff")
            .set("padding", "22px 22px 20px");

        Span cat = new Span(t.category.toUpperCase());
        cat.getStyle()
            .set("font-size", "0.66rem").set("font-weight", "800").set("letter-spacing", "0.16em")
            .set("opacity", "0.92").set("display", "block")
            .set("padding", "3px 9px").set("background", "rgba(0,0,0,0.22)").set("border-radius", "999px")
            .set("width", "fit-content");

        Span title = new Span(t.event);
        title.getStyle()
            .set("font-size", "1.25rem").set("font-weight", "800").set("display", "block")
            .set("margin-top", "10px").set("letter-spacing", "-0.015em").set("line-height", "1.25");

        Span date = new Span(t.date);
        date.getStyle()
            .set("font-size", "0.9rem").set("display", "block").set("margin-top", "6px")
            .set("opacity", "0.92");

        header.add(cat, title, date);
        return header;
    }

    private static Div buildBody(TicketInfo t) {
        Div body = new Div();
        body.getStyle().set("padding", "20px 22px 18px");
        body.add(field("Venue", t.venue));

        Div grid = new Div();
        grid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "1fr 1fr 1fr")
            .set("gap", "16px")
            .set("margin-top", "14px");
        grid.add(field("Zone", t.zone), field("Seat", t.seat), field("Price", t.price));
        body.add(grid);
        return body;
    }

    private static Div buildPerforation() {
        Div p = new Div();
        p.getStyle()
            .set("border-top", "2px dashed var(--border-strong)")
            .set("margin", "0 14px")
            .set("position", "relative");
        // Side notches via box-shadow on a tiny dot positioned at each end
        Div leftDot  = new Div();
        Div rightDot = new Div();
        for (Div dot : new Div[]{leftDot, rightDot}) {
            dot.getStyle()
                .set("position", "absolute").set("top", "-12px")
                .set("width", "22px").set("height", "22px")
                .set("background", "#fff")
                .set("border", "1px solid var(--border-strong)")
                .set("border-radius", "50%");
        }
        leftDot.getStyle().set("left", "-25px");
        rightDot.getStyle().set("right", "-25px");
        p.add(leftDot, rightDot);
        return p;
    }

    private static Div buildStub(TicketInfo t) {
        Div stub = new Div();
        stub.getStyle()
            .set("padding", "20px 22px 22px")
            .set("background", "#fafbfc")
            .set("text-align", "center");

        Div barcode = new Div();
        barcode.getStyle()
            .set("height", "64px")
            .set("background",
                "repeating-linear-gradient(to right, #0f172a 0 2px, transparent 2px 4px," +
                " #0f172a 4px 5px, transparent 5px 8px," +
                " #0f172a 8px 11px, transparent 11px 12px," +
                " #0f172a 12px 16px, transparent 16px 18px)")
            .set("border-radius", "4px")
            .set("margin", "0 8px");

        Span code = new Span(t.barcode);
        code.getStyle()
            .set("font-family", "var(--mono)")
            .set("font-size", "1rem").set("font-weight", "800")
            .set("letter-spacing", "0.18em").set("color", "#0f172a")
            .set("display", "block").set("margin-top", "12px");

        Span order = new Span("Order #" + t.orderId);
        order.getStyle()
            .set("font-family", "var(--mono)")
            .set("font-size", "0.78rem").set("color", "var(--muted)")
            .set("display", "block").set("margin-top", "6px");

        stub.add(barcode, code, order);
        return stub;
    }

    private static Div field(String label, String value) {
        Div block = new Div();
        Span l = new Span(label.toUpperCase());
        l.getStyle()
            .set("font-size", "0.66rem").set("font-weight", "800").set("letter-spacing", "0.1em")
            .set("color", "var(--muted)").set("display", "block").set("margin-bottom", "3px");
        Span v = new Span(value);
        v.getStyle()
            .set("font-size", "0.92rem").set("font-weight", "700")
            .set("color", "#0f172a").set("display", "block");
        block.add(l, v);
        return block;
    }

    private static String[] gradientFor(String category) {
        return switch (category == null ? "" : category.toLowerCase()) {
            case "concert"    -> new String[]{"#8b5cf6", "#ec4899"};
            case "sport"      -> new String[]{"#0ea5e9", "#10b981"};
            case "theatre"    -> new String[]{"#dc2626", "#f59e0b"};
            case "conference" -> new String[]{"#0d9488", "#1d4ed8"};
            default           -> new String[]{"#475569", "#1a5490"};
        };
    }
}
