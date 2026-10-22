package com.ticketing.system.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ticketing.system.shared.config.SecurityConfig;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * ArchUnit hexagonal-layering rules for the bounded-context modules.
 *
 * <p>The rules are driven through the Jupiter engine (importing production classes with
 * {@link ClassFileImporter} and calling {@code check(...)}) rather than ArchUnit's {@code @ArchTest}
 * field engine, because that engine is not reliably enforced by this project's Surefire setup — its
 * rules silently reported zero executed tests and a deliberately-failing rule did not fail the build.
 * Plain {@code @Test} methods are guaranteed to run under Surefire and to fail on a violation.
 *
 * <p>Alongside the hexagonal-layering invariants, {@link #bounded_contexts_are_acyclic()} enforces
     * that the bounded-context modules form a directed acyclic graph. This is the substantive cycle
     * guarantee: it uses ArchUnit slices (the same cycle engine Spring Modulith uses) run directly here,
     * so it applies even though the modules are declared <em>open</em> (open modules are exempt from
     * Modulith's own cycle check).
 */
class HexagonalRulesTest {

    // Production classes only (tests excluded), imported once and reused by every rule below.
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ticketing.system");

    /**
     * The domain is the innermost hexagon: a context's {@code domain} package must not depend on the
     * Spring or Vaadin frameworks, nor outward on the {@code application} or {@code adapter} layers.
     * {@code jakarta.persistence} is deliberately allowed — the aggregates are the JPA entities
     * (the "persistence-annotated domain" decision), which is a spec dependency, not a framework one.
     */
    @Test
    void domain_is_free_of_framework_and_outer_layers() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",   // no Spring in the domain
                        "com.vaadin..",             // no Vaadin in the domain
                        "..application..",           // domain must not reach up into the application layer
                        "..adapter..")               // domain must not reach out into adapters
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The application layer (use-case services + ports) drives the domain through ports; it must never
     * depend on a concrete {@code adapter} (adapters implement the outbound ports and are injected by
     * Spring — the dependency arrow points inward, from adapter to application, never the reverse).
     */
    @Test
    void application_does_not_depend_on_adapters() {
        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..adapter..")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The Vaadin {@code ui} module is the single driving (inbound) adapter: it may depend on the
     * backend contexts' application APIs, but no backend <em>bounded context</em> may depend back on
     * {@code ui}. This is the invariant that keeps the UI a leaf of the dependency graph.
     *
     * <p>The rule lists the backend bounded contexts explicitly rather than "everything but ui" on
     * purpose: {@code bootstrap} (the composition-root sink) is deliberately allowed to reference
     * {@code ui} — it wires the app together, e.g. the notification polling scheduler drives a UI
     * session — and {@code reporting} is a read-side consumer that never touches {@code ui}. Only the
     * eight true backend contexts below are gated here.
     */
    @Test
    void no_backend_context_depends_on_the_ui() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.ticketing.system.identity..", "com.ticketing.system.organization..",
                        "com.ticketing.system.catalog..", "com.ticketing.system.sales..",
                        "com.ticketing.system.messaging..", "com.ticketing.system.notifications..",
                        "com.ticketing.system.governance..", "com.ticketing.system.shared..")
                .should().dependOnClassesThat().resideInAPackage("com.ticketing.system.ui..")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * The bounded contexts must form a directed acyclic graph: no two top-level modules may depend on
     * each other, directly or transitively. This is the substantive guarantee that Spring Modulith's
     * {@code verify()} would give for cycles — enforced here with ArchUnit slices because it analyses
     * real bytecode package dependencies, independent of Modulith's module-encapsulation model (under
     * which OPEN modules are exempt from cycle checks).
     */
    
    @Test
    void bounded_contexts_are_acyclic() {
        slices()
                .matching("com.ticketing.system.(*)..")   // one slice per top-level module (catalog, sales, ...)
                .should().beFreeOfCycles()
                .check(PRODUCTION_CLASSES);
    }

    /**
     * ADR-004: every {@code shared.event} notice is consumed after the publisher commits, off its
     * thread. Publishers no longer guard {@code publishEvent}, so a synchronous listener on a notice
     * would let a notification failure fail the use case, e.g. report a paid checkout as failed.
     */
    @Test
    void GivenANoticeListener_WhenScanned_ThenItIsAnAsyncAfterCommitListener() {
        methods().that().areAnnotatedWith(EventListener.class)
                .or().areMetaAnnotatedWith(EventListener.class)
                .and(TAKE_A_SHARED_EVENT_NOTICE)
                .should(BE_ASYNC_AFTER_COMMIT_LISTENERS)
                .check(PRODUCTION_CLASSES);
    }

    private static final DescribedPredicate<JavaMethod> TAKE_A_SHARED_EVENT_NOTICE =
            DescribedPredicate.describe("take a shared.event notice", method -> method.getRawParameterTypes()
                    .stream().anyMatch(type -> type.getPackageName().equals("com.ticketing.system.shared.event")));

    private static final ArchCondition<JavaMethod> BE_ASYNC_AFTER_COMMIT_LISTENERS =
            new ArchCondition<>("be @TransactionalEventListener methods that run @Async") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean afterCommit = method.isAnnotatedWith(TransactionalEventListener.class);
                    boolean async = method.isAnnotatedWith(Async.class) || method.getOwner().isAnnotatedWith(Async.class);
                    events.add(new SimpleConditionEvent(method, afterCommit && async,
                            method.getFullName() + (afterCommit && async ? " is" : " is not")
                                    + " an @Async @TransactionalEventListener"));
                }
            };

    /**
     * ADR-008: every "now" comes from the one injected UTC {@link Clock}, and no zone comes from the
     * JVM. Production code must not call or reference (e.g. {@code Instant::now}) {@code now()} or
     * {@code now(ZoneId)} on a {@code java.time} type, both of which read the system clock, nor read
     * the default zone. {@code System.currentTimeMillis()} is left out on purpose: its uses are
     * zone-free browser countdowns and a cache TTL.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenNothingReadsTheSystemClockOrDefaultZone() {
        NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE.check(PRODUCTION_CLASSES);
    }

    /** ADR-008: the Clock bean ({@code SecurityConfig.systemClock}) is the only system clock. */
    @Test
    void GivenProductionCode_WhenScanned_ThenOnlyTheClockBeanCreatesASystemClock() {
        noClasses().that().doNotHaveFullyQualifiedName(SecurityConfig.class.getName())
                .should().callMethod(Clock.class, "systemUTC")
                .orShould().callMethod(Clock.class, "system", ZoneId.class)
                .check(PRODUCTION_CLASSES);
    }

    // The class comment records a rule engine that silently checked nothing; these prove the clock
    // rule fails on each way of reading the system clock, a method reference included.
    @Test
    void GivenACallToLocalDateTimeNow_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(CallsLocalDateTimeNow.class);
    }

    @Test
    void GivenAReferenceToInstantNow_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(ReferencesInstantNow.class);
    }

    @Test
    void GivenAReadOfTheDefaultZone_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(ReadsTheDefaultZone.class);
    }

    private static void assertClockRuleFails(Class<?> offender) {
        JavaClasses imported = new ClassFileImporter().importClasses(offender);
        assertThatThrownBy(() -> NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE.check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offender.getSimpleName());
    }

    private static final Set<String> CLOCK_READING_TYPES = Stream.of(Instant.class, LocalDate.class,
            LocalDateTime.class, LocalTime.class, OffsetDateTime.class, OffsetTime.class, ZonedDateTime.class,
            Year.class, YearMonth.class, MonthDay.class)
            .map(Class::getName)
            .collect(Collectors.toUnmodifiableSet());

    private static final DescribedPredicate<JavaAccess<?>> READ_THE_SYSTEM_CLOCK_OR_DEFAULT_ZONE =
            DescribedPredicate.describe("read the system clock or the JVM's default time zone", access -> {
                if (!(access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target)) {
                    return false;
                }
                String owner = target.getOwner().getName();
                String name = target.getName();
                List<JavaClass> parameters = target.getRawParameterTypes();
                // now() and now(ZoneId) read the system clock; now(Clock) is the allowed form.
                boolean systemNow = name.equals("now") && CLOCK_READING_TYPES.contains(owner)
                        && (parameters.isEmpty() || parameters.get(0).isEquivalentTo(ZoneId.class));
                boolean defaultZone = (owner.equals(ZoneId.class.getName()) && name.equals("systemDefault"))
                        || (owner.equals(TimeZone.class.getName()) && name.equals("getDefault"))
                        || (owner.equals(Clock.class.getName()) && name.equals("systemDefaultZone"));
                return systemNow || defaultZone;
            });

    private static final ArchRule NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE = noClasses()
            .should().accessTargetWhere(READ_THE_SYSTEM_CLOCK_OR_DEFAULT_ZONE)
            .because("ADR-008: read time from the injected Clock; zones come from the event or platform.time-zone");

    // Fixtures for the self-tests above, one per way of reading the system clock.
    static class CallsLocalDateTimeNow {
        LocalDateTime read() {
            return LocalDateTime.now();
        }
    }

    static class ReferencesInstantNow {
        Supplier<Instant> read() {
            return Instant::now;
        }
    }

    static class ReadsTheDefaultZone {
        ZoneId read() {
            return ZoneId.systemDefault();
        }
    }
}
