package com.ticketing.system.ui.views.order;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkCountdown;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.CartBadgeCache;
import com.ticketing.system.ui.session.OrderSession;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route(value = "checkout", layout = MainLayout.class)
@PageTitle("Checkout · TicketHub")
@AnonymousAllowed
public class CheckoutView extends LkPage implements BeforeEnterObserver, AfterNavigationObserver {

    private static final String IDEMPOTENCY_ATTR = "checkout.idempotencyKey";
    private static final long MILLIS_PER_SECOND = 1000L;

    private final CheckoutPresenter presenter;
    private final Clock clock;
    private final Object uiLock = new Object();

    private String  memberToken;
    private String  sessionId;
    private boolean isMember;
    private int     resolvedUserId;

    private volatile UI ui;
    private boolean listenerRegistered = false;

    private ActiveOrderDTO activeOrder;
    private long subtotalCents;
    private long totalCents;
    /** When the loaded order's hold ends, in epoch ms on the application clock; never sent to the browser. */
    private long holdEndsAtMs;

    private final AtomicBoolean paymentInProgress = new AtomicBoolean(false);

    private final TextField    cardholder = new TextField("Cardholder name");
    private final TextField    cardNumber  = new TextField("Card number");
    private final TextField    expiry      = new TextField("Expiry");
    private final TextField    cvc         = new TextField("CVC");
    private final EmailField   guestEmail  = new EmailField("Your email");
    private final IntegerField guestAge    = new IntegerField("Your age");

    private Div   linesContainer;
    private Span  subtotalSpan;
    private Span  totalSpan;
    private LkBtn payButton;
    private Span  timerSpan;
    private LkBtn editCartBtn;

    private final CheckoutPresenter.ExpiryListener expiryListener =
        new CheckoutPresenter.ExpiryListener() {
            @Override
            public boolean matches(int userId, String sessionId) {
                return isMember
                    ? (userId == resolvedUserId && resolvedUserId != 0)
                    : (CheckoutView.this.sessionId != null && !CheckoutView.this.sessionId.isBlank()
                        && CheckoutView.this.sessionId.equals(sessionId));
            }

            @Override
            public void onExpired() {
                UI currentUi;
                synchronized (uiLock) {
                    currentUi = ui;
                }
                if (currentUi == null || !currentUi.isAttached()) return;
                currentUi.access(() -> {
                    clearOrder();
                    syncDynamicUI();
                    Toasts.failure("Your reservation has expired. Please add tickets again.");
                });
            }
        };

    /**
     * @param clock the application clock (ADR-008), for the seconds the hold countdown has left
     */
    public CheckoutView(CheckoutPresenter presenter, Clock clock) {
        this.presenter = presenter;
        this.clock = clock;
        this.sessionId = "";
        this.linesContainer = new Div();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        synchronized (uiLock) {
            this.ui = attachEvent.getUI();
            if (!listenerRegistered) {
                presenter.registerExpiryListener(expiryListener);
                listenerRegistered = true;
            }
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        synchronized (uiLock) {
            if (listenerRegistered) {
                presenter.unregisterExpiryListener(expiryListener);
                listenerRegistered = false;
            }
            this.ui = null;
        }
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        resolveIdentity();
        if (getChildren().findAny().isEmpty()) {
            buildPage();
        } else {
            syncDynamicUI();
        }
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        CheckoutPresenter.LoadOutcome outcome = reload();
        // beforeEnter paints the page while activeOrder is still null; the order loads here, so
        // re-render the lines / subtotal / total / timer now that it is available.
        syncDynamicUI();
        if (outcome instanceof CheckoutPresenter.LoadOutcome.Failure fail) {
            Toasts.warn(Lk.withReason("Could not load your cart", fail.reason()));
        }
    }

    private void resolveIdentity() {
        CheckoutPresenter.Identity id = presenter.resolveIdentity();
        this.isMember       = id.member();
        this.memberToken    = id.memberToken();
        this.sessionId      = id.guestSessionId() != null ? id.guestSessionId() : "";
        this.resolvedUserId = id.userId();
    }

    private CheckoutPresenter.LoadOutcome reload() {
        CheckoutPresenter.LoadOutcome outcome = presenter.loadOrder();
        switch (outcome) {
            case CheckoutPresenter.LoadOutcome.Loaded l -> {
                this.activeOrder   = l.order();
                this.subtotalCents = l.pricing().subtotalCents();
                this.totalCents    = l.pricing().totalCents();
                this.holdEndsAtMs  = clock.millis() + l.order().remainingSecondsBeforeExpiry() * MILLIS_PER_SECOND;
            }
            case CheckoutPresenter.LoadOutcome.Empty e -> clearOrder();
            case CheckoutPresenter.LoadOutcome.Failure f -> clearOrder();
        }
        return outcome;
    }

    private void clearOrder() {
        this.activeOrder   = null;
        this.subtotalCents = 0L;
        this.totalCents    = 0L;
    }

    private void buildPage() {
        title("Checkout");
        add(buildCountdownBanner());
        add(buildSplit());
        syncDynamicUI();
    }

    private void syncDynamicUI() {
        if (linesContainer == null) return;

        linesContainer.removeAll();
        boolean hasItems = activeOrder != null && !activeOrder.lines().isEmpty();

        if (!hasItems) {
            Div empty = new Div();
            empty.getStyle()
                    .set("padding", "20px")
                    .set("text-align", "center")
                    .set("color", "var(--muted)")
                    .set("font-size", "13.5px");

            if (isMember) {
                empty.setText("No items in cart. Add tickets from Browse first.");
            } else {
                empty.setText("Sign in to view your saved cart, or browse events as a guest.");
                LkBtn signInBtn = new LkBtn("Sign In")
                        .variant(LkBtn.Variant.primary)
                        .onClick(e -> UI.getCurrent().navigate("login"));
                signInBtn.getStyle().set("margin-top", "10px");
                empty.add(signInBtn);
            }
            linesContainer.add(empty);
        } else {
            for (ActiveOrderDTO.CartLineDTO line : activeOrder.lines()) {
                linesContainer.add(buildOrderLine(line));
            }
        }

        if (subtotalSpan != null) subtotalSpan.setText(formatCents(subtotalCents));
        if (totalSpan    != null) totalSpan.setText(formatCents(totalCents));

        if (payButton != null) {
            payButton.setText("Pay " + formatCents(totalCents));
            payButton.getElement().setEnabled(totalCents > 0);
        }

        if (timerSpan != null && activeOrder != null
                && activeOrder.remainingSecondsBeforeExpiry() > 0) {
            // The page may be shown again without a reload (beforeEnter on a built page), so the seconds
            // left come from the hold's end on the server's clock, not from the order as first loaded.
            long secondsLeft = Math.max(0, (holdEndsAtMs - clock.millis()) / MILLIS_PER_SECOND);
            LkCountdown.start(timerSpan.getElement(), secondsLeft, "");
        }
    }

    private Component buildCountdownBanner() {
        LkBanner banner = new LkBanner();
        banner.tone(LkBanner.Tone.warn);
        banner.setIcon(new LkIcon("clock", 17));

        timerSpan = new Span("--:--");
        timerSpan.addClassName("lk-mono");
        timerSpan.getStyle().set("font-size", "18px").set("font-weight", "700");

        Span body = new Span();
        body.add(timerSpan);
        Span msg = new Span(" — seats are held for you. Complete payment before time runs out.");
        msg.getStyle().set("font-size", "13.5px");
        body.add(msg);
        banner.setBody(body);

        Span action = Lk.muted("auto-releases on expiry");
        action.getStyle().set("font-size", "12.5px");
        banner.setAction(action);
        return banner;
    }

    private Component buildSplit() {
        Div split = new Div();
        split.addClassName("bz-checkout-split");
        split.add(buildOrderColumn(), buildPaymentCard());
        return split;
    }

    private Component buildOrderColumn() {
        LkCol col = new LkCol().gap(14);
        LkCard orderCard = new LkCard("Your Order").pad(0);

        linesContainer.addClassName("bz-order-lines");
        orderCard.add(linesContainer);

        Div foot = new Div();
        foot.addClassName("bz-order-foot");

        subtotalSpan = new Span(formatCents(subtotalCents));
        totalSpan    = new Span(formatCents(totalCents));

        LkCol totals = new LkCol().gap(6);
        totals.getStyle().set("margin-top", "12px");
        totals.add(summaryRow("Subtotal", subtotalSpan, false));
        totals.add(summaryRow("Total",    totalSpan, true));
        foot.add(totals);

        editCartBtn = new LkBtn("Edit Cart")
                .variant(LkBtn.Variant.tertiary)
                .size(LkBtn.Size.s)
                .onClick(e -> UI.getCurrent().navigate("cart"));
        editCartBtn.getStyle().set("margin-top", "8px");

        orderCard.add(foot);
        col.add(orderCard);
        col.add(editCartBtn);
        return col;
    }

    private Component buildOrderLine(ActiveOrderDTO.CartLineDTO line) {
        Div row = new Div();
        row.addClassName("bz-order-line");

        String labelText = line.eventName()
                + (line.seatNumber() != null ? " · " + line.seatNumber() : "");
        Span label = new Span(labelText);

        long lineCents = Money.toCents(line.pricePerTicket());
        Span price = new Span(formatCents(lineCents));
        price.getStyle().set("font-weight", "700");

        row.add(label, price);
        return row;
    }

    private Component summaryRow(String label, Span valueSpan, boolean bold) {
        LkRow r = new LkRow().justify("space-between");
        Span labelSpan = new Span(label);
        if (bold) {
            labelSpan.getStyle().set("font-weight", "700").set("font-size", "16px");
            valueSpan.getStyle().set("font-weight", "700").set("font-size", "16px");
        } else {
            labelSpan.getStyle().set("color", "var(--muted)");
        }
        r.add(labelSpan, valueSpan);
        return r;
    }

    private Component buildPaymentCard() {
        LkCard card = new LkCard("Payment").pad(20);
        LkCol col = new LkCol().gap(14);

        cardholder.setPlaceholder("Name on card");
        cardholder.setRequired(true);
        cardholder.setWidthFull();

        cardNumber.setPlaceholder("1234 5678 9012 3456");
        cardNumber.setSuffixComponent(new LkIcon("card", 16));
        cardNumber.setRequired(true);
        cardNumber.setWidthFull();

        expiry.setPlaceholder("MM / YY");
        expiry.setRequired(true);
        cvc.setPlaceholder("123");
        cvc.setRequired(true);

        LkRow expiryCvc = new LkRow().gap(12);
        expiry.getStyle().set("flex", "1 1 0");
        cvc.getStyle().set("flex", "1 1 0");
        expiryCvc.add(expiry, cvc);

        col.add(cardholder, cardNumber, expiryCvc);

        if (!isMember) {
            guestEmail.setPlaceholder("you@example.com");
            guestEmail.setRequired(true);
            guestEmail.setWidthFull();
            // The platform sends no email yet (#102), so the field promises none.
            guestEmail.setHelperText("Kept with your order record; no email is sent.");

            guestAge.setPlaceholder("e.g. 25");
            guestAge.setMin(1);
            guestAge.setMax(120);
            guestAge.setRequired(true);
            guestAge.setHelperText("Required to verify age-restricted events.");
            guestAge.setWidthFull();

            col.add(guestEmail, guestAge);
        }

        col.add(Lk.divider());

        payButton = new LkBtn("Pay " + formatCents(totalCents))
                .variant(LkBtn.Variant.primary)
                .size(LkBtn.Size.l)
                .full()
                .onClick(e -> attemptPay());
        Shortcuts.addShortcutListener(payButton, this::attemptPay, Key.ENTER);
        payButton.getElement().setEnabled(totalCents > 0);
        col.add(payButton);

        Span hint = new Span();
        hint.getStyle()
                .set("font-size", "12px")
                .set("color", "var(--muted)")
                .set("text-align", "center")
                .set("display", "block");
        hint.add(new LkIcon("lock", 13));
        hint.add(new Span(
                " Payment and ticket issuance are atomic — if either fails, you are not charged."));
        col.add(hint);

        card.add(col);
        return card;
    }

    private void attemptPay() {
        if (totalCents <= 0L) {
            Toasts.failure("Your cart is empty.");
            return;
        }
        if (cardholder.isEmpty() || cardNumber.isEmpty()
                || expiry.isEmpty() || cvc.isEmpty()) {
            Toasts.failure("Please fill in every payment field.");
            return;
        }
        if (cardNumber.getValue().replaceAll("\\s+", "").length() != 16) {
            Toasts.failure("Card number must be 16 digits.");
            return;
        }
        if (!isMember) {
            if (guestEmail.isInvalid() || guestEmail.isEmpty()) {
                Toasts.failure("Please enter a valid email address.");
                return;
            }
            if (guestAge.isEmpty() || guestAge.getValue() == null
                    || guestAge.getValue() < 1) {
                Toasts.failure("Please enter your age.");
                return;
            }
        }

        CheckoutPresenter.LoadOutcome reloaded = reload();
        syncDynamicUI();
        switch (reloaded) {
            case CheckoutPresenter.LoadOutcome.Loaded l -> { /* still valid — continue */ }
            case CheckoutPresenter.LoadOutcome.Failure f -> {
                Toasts.failure(Lk.withReason("Could not check your cart before paying", f.reason()));
                return;
            }
            case CheckoutPresenter.LoadOutcome.Empty e -> {
                Toasts.failure("Your reservation has expired. Please add tickets again.");
                return;
            }
        }

        if (!paymentInProgress.compareAndSet(false, true)) {
            return;
        }
        // A kept confirmation is the last order's: drop it before this one, so a failed attempt
        // leaves no older order to reopen at order-confirmed.
        OrderSession.clear();
        payButton.getElement().setEnabled(false);

        try {
            String idempotencyKey = currentIdempotencyKey();
            CheckoutPresenter.PayOutcome outcome = isMember
                ? presenter.payAsMember(memberToken, idempotencyKey,
                                        cardNumber.getValue(), cvc.getValue(), expiry.getValue(), cardholder.getValue())
                : presenter.payAsGuest(sessionId, guestEmail.getValue().trim(), guestAge.getValue(), idempotencyKey,
                                       cardNumber.getValue(), cvc.getValue(), expiry.getValue(), cardholder.getValue());

            switch (outcome) {
                case CheckoutPresenter.PayOutcome.Success ok -> {
                    OrderSession.setResult(ok.result());
                    clearIdempotencyKey();
                    Toasts.success("Payment successful — "
                            + formatCents(Money.toCents(ok.result().totalCharged()))
                            + " charged.");
                    UI.getCurrent().navigate("order-confirmed");
                    return;
                }
                case CheckoutPresenter.PayOutcome.PolicyRejected p ->
                    Toasts.failure("This purchase isn't allowed under the event's purchase policy.");
                case CheckoutPresenter.PayOutcome.PaymentDeclined d ->
                    Toasts.failure("Your payment was declined — please check your card details and try again.");
                // A lost answer from the payment service may still have charged the card, so the
                // buyer checks their orders before paying again.
                case CheckoutPresenter.PayOutcome.GatewayUnavailable g ->
                    Toasts.failure("An unexpected error occurred. Check My account before you try again.");
                case CheckoutPresenter.PayOutcome.OrderNotCompleted n ->
                    Toasts.failure("We couldn't complete your order. Any charge is being refunded; "
                            + "check My account before you try again.");
                case CheckoutPresenter.PayOutcome.SoldOut s ->
                    Toasts.failure("Those seats just sold out. Please choose again.");
                case CheckoutPresenter.PayOutcome.OrderExpired e ->
                    Toasts.failure("Your reservation has expired. Please add tickets again.");
                case CheckoutPresenter.PayOutcome.DuplicateSubmission dup ->
                    Toasts.warn("This order was already submitted.");
                case CheckoutPresenter.PayOutcome.Failure f ->
                    Toasts.failure("Payment could not be completed. Please try again or contact support."
                            + (f.reason() != null && !f.reason().isBlank() ? " (" + f.reason() + ")" : ""));
            }
        } finally {
            // Whatever the outcome, the attempt may have changed the cart: a sale empties it, and a lost
            // race or a failed issue releases its holds.
            CartBadgeCache.invalidate();
            paymentInProgress.set(false);
            payButton.getElement().setEnabled(totalCents > 0);
        }
    }

    /**
     * Stable idempotency key for this checkout, persisted in the {@link VaadinSession} so a
     * retry after a network timeout / page refresh reuses it and {@code CheckoutService}'s
     * idempotency cache de-dupes the charge instead of billing the buyer twice. Dropped on
     * success by {@link #clearIdempotencyKey()}.
     */
    private String currentIdempotencyKey() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return UUID.randomUUID().toString();
        }
        String key = (String) session.getAttribute(IDEMPOTENCY_ATTR);
        if (key == null) {
            key = UUID.randomUUID().toString();
            session.setAttribute(IDEMPOTENCY_ATTR, key);
        }
        return key;
    }

    /** Drop the key after a successful purchase so the buyer's next order gets a fresh one. */
    private void clearIdempotencyKey() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setAttribute(IDEMPOTENCY_ATTR, null);
        }
    }

    private static String formatCents(long cents) {
        if (cents < 0 || cents > 99_999_999) {
            throw new IllegalArgumentException("Invalid price: " + cents + " cents");
        }
        long abs = Math.abs(cents);
        String sign = cents < 0 ? "-" : "";
        return sign + "$" + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}