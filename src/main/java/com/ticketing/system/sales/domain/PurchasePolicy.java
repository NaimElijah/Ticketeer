package com.ticketing.system.sales.domain;

public interface PurchasePolicy {

    boolean isSatisfiedBy(PurchaseContext context);

    String getFailureMessage();
}