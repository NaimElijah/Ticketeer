package com.ticketing.system.sales.domain;

public class NoPurchasePolicy implements PurchasePolicy {

    @Override
    public boolean isSatisfiedBy(PurchaseContext context) {
        return true;
    }

    @Override
    public String getFailureMessage() {
        return "";
    }
}