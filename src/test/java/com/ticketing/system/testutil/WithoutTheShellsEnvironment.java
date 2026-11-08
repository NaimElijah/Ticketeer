package com.ticketing.system.testutil;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * Test helper: removes the process environment and the JVM's system properties from a test context's
 * {@code Environment}, so each {@code ${ENV:default}} value in {@code application.yml} resolves to its
 * committed default whatever the developer's shell exports ({@code SEED_MODE}, {@code PLATFORM_TIME_ZONE}
 * and the rest). Values the test itself sets, such as an {@code ApplicationContextRunner}'s
 * {@code withPropertyValues}, live in a property source of their own and still apply.
 *
 * <p>Use it in a test that asserts the shipped defaults; a context that needs a real environment must not.
 */
public final class WithoutTheShellsEnvironment
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    }
}
