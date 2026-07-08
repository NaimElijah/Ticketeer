package com.ticketing.system.Infrastructure.dev.seed;

import com.ticketing.system.Infrastructure.persistence.ActiveOrderPersistence.MemoryActiveOrderRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemoryAdminRepository;
import com.ticketing.system.Infrastructure.persistence.ConversationPersistence.MemoryConversationRepository;
import com.ticketing.system.catalog.adapter.out.persistence.MemoryEventRepository;
import com.ticketing.system.Infrastructure.persistence.NotificationPersistence.MemoryNotificationRepository;
import com.ticketing.system.Infrastructure.persistence.OrderReceiptPersistence.MemoryOrderReceiptRepository;
import com.ticketing.system.organization.adapter.out.persistence.MemoryProductionCompanyRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.Infrastructure.persistence.TicketPersistence.MemoryTicketRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Wipes every in-memory repository back to an empty state. Used by
 * a {@code seed.mode=wipe} run so a reset starts from a
 * deterministic clean slate without restarting the JVM.
 *
 * <p>Uses reflection rather than adding {@code clear()} methods to each
 * memory repo because (a) {@code clear()} would have no place on the
 * domain {@code IRepository} interface and (b) the same reflection
 * works the day a new memory repo is added. Resets non-final instance
 * Maps, Collections, and AtomicInteger / AtomicLong counters; leaves
 * locks and final configuration alone.
 *
 * <p>Each {@code Memory*Repository} is injected via {@link ObjectProvider}, so it is
 * OPTIONAL: as an aggregate migrates to JPA (the {@code jpa} profile) its
 * {@code Memory*Repository} bean is {@code @Profile("!jpa")} and disappears — it then
 * simply drops out of the wipe (its data lives in the database; clearing that is the
 * {@code seed.mode} reset-safety work, #366). So this cleaner keeps working through the
 * whole Memory→JPA migration without edits.
 *
 * <p>{@code @Profile("!jpa")} — active on the in-memory backends (test / default-fast). Under the
 * {@code jpa} profile (dev / supabase / deploy) {@code JpaRepoCleaner} performs the wipe instead.
 */
@Component
@Profile("!jpa")
@Slf4j
public class MemoryRepoCleaner implements RepoCleaner {

    private final List<Object> repositories;

    public MemoryRepoCleaner(
            ObjectProvider<MemoryUserRepository> userRepo,
            ObjectProvider<MemorySessionRepository> sessionRepo,
            ObjectProvider<MemoryActiveOrderRepository> activeOrderRepo,
            ObjectProvider<MemoryEventRepository> eventRepo,
            ObjectProvider<MemoryTicketRepository> ticketRepo,
            ObjectProvider<MemoryOrderReceiptRepository> orderReceiptRepo,
            ObjectProvider<MemoryNotificationRepository> notificationRepo,
            ObjectProvider<MemoryConversationRepository> conversationRepo,
            ObjectProvider<MemoryProductionCompanyRepository> companyRepo,
            ObjectProvider<MemoryAdminRepository> adminRepo) {
        // getIfAvailable() is null for any aggregate already migrated to JPA
        // (its Memory* bean is @Profile("!jpa"), absent in the jpa profile) — it's skipped.
        this.repositories = Stream.of(
                userRepo, sessionRepo, activeOrderRepo, eventRepo, ticketRepo,
                orderReceiptRepo, notificationRepo, conversationRepo, companyRepo, adminRepo)
            .<Object>map(ObjectProvider::getIfAvailable)
            .filter(Objects::nonNull)
            .toList();
    }

    @Override
    public void clearAll() {
        for (Object repo : repositories) {
            try {
                reset(repo);
            } catch (RuntimeException e) {
                log.warn("failed to reset {}: {}", repo.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private static void reset(Object repo) {
        for (Field f : repo.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
            } catch (RuntimeException ignored) {
                continue;
            }
            Object value;
            try {
                value = f.get(repo);
            } catch (IllegalAccessException ignored) {
                continue;
            }
            if (value instanceof Map<?, ?> m) {
                m.clear();
            } else if (value instanceof Collection<?> c) {
                c.clear();
            } else if (value instanceof AtomicInteger a) {
                a.set(1);
            } else if (value instanceof AtomicLong a) {
                a.set(1L);
            }
        }
    }
}
