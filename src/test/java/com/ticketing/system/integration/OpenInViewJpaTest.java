package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins {@code spring.jpa.open-in-view: false} (#186). With it on, Spring Boot registers an interceptor
 * that keeps one EntityManager, and the connection it acquired, open until a Spring MVC request ends,
 * rather than until its transaction does. Vaadin's root mapping carries no MVC interceptors, so today
 * nothing passes through it; this keeps the first Spring MVC handler from holding connections either.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenInViewJpaTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void GivenTheJpaWebContext_WhenItStarts_ThenNoRequestHoldsAnEntityManagerOpen() {
        assertEquals(0, context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class).length,
                "spring.jpa.open-in-view must stay false");
    }
}
