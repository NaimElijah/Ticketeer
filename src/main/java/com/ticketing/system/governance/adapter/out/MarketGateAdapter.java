package com.ticketing.system.governance.adapter.out;

import org.springframework.stereotype.Component; // marks this as a Spring-managed bean so it wires into sales

import com.ticketing.system.governance.application.service.SystemAdminService; // governance owns the market lifecycle
import com.ticketing.system.sales.application.port.out.MarketGate; // the sales outbound port this adapter fulfils

/**
 * Governance-side adapter that satisfies the sales {@link MarketGate} outbound port.
 *
 * <p>Dependency inversion for the market gate: sales depends on the {@code MarketGate} port and no
 * longer references governance. Governance — the top-level consumer — implements the port here and
 * delegates to its own {@code SystemAdminService}, so the dependency now points from governance into
 * sales' port (the correct direction).
 */
@Component // registered as a bean so Spring injects it wherever sales needs a MarketGate
public class MarketGateAdapter implements MarketGate {

    private final SystemAdminService systemAdminService; // governance service that holds the market state

    /**
     * Creates the adapter around the governance service that owns the market lifecycle.
     *
     * @param systemAdminService the governance service whose market state is exposed to sales
     */
    public MarketGateAdapter(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService; // store the governance service for delegation
    }

    /**
     * Answers the sales-side market-open query by delegating to governance.
     *
     * @return {@code true} when the platform market is open, {@code false} otherwise
     */
    @Override
    public boolean isOpen() {
        return systemAdminService.isMarketOpen(); // delegate to governance's authoritative market state
    }
}
