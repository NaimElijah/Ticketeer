package com.ticketing.system.governance.adapter.out;

import org.springframework.stereotype.Component;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.sales.application.port.out.MarketGate;

/**
 * Governance-side adapter that satisfies the sales {@link MarketGate} outbound port.
 *
 * <p>Dependency inversion for the market gate: sales depends on the {@code MarketGate} port and does
 * not reference governance. Governance implements the port here and delegates to its own
 * {@code SystemAdminService}, so the dependency points from governance into sales' port.
 */
@Component
public class MarketGateAdapter implements MarketGate {

    private final SystemAdminService systemAdminService;

    /**
     * Creates the adapter around the governance service that owns the market lifecycle.
     *
     * @param systemAdminService the governance service whose market state is exposed to sales
     */
    public MarketGateAdapter(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    /**
     * Answers the sales-side market-open query by delegating to governance.
     *
     * @return {@code true} when the platform market is open, {@code false} otherwise
     */
    @Override
    public boolean isOpen() {
        return systemAdminService.isMarketOpen();
    }
}
