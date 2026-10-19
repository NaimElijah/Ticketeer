package com.ticketing.system.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL container every {@code *IT} runs against (ADR-006).
 *
 * <p>Each IT class declares its own static container from {@link #newContainer()}, so no rows or
 * connections outlive the class. Wire it in with {@code @Container @ServiceConnection}.
 */
public final class PostgresContainers {

    /**
     * PostgreSQL 17, pinned to a minor so CI and local runs pull the same image. No production
     * engine version is chosen yet; follow the RDS one once #87 picks it.
     */
    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:17.11-alpine");

    private PostgresContainers() {
    }

    /** @return a new, not yet started container on {@link #IMAGE} */
    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>(IMAGE);
    }
}
