package com.ticketing.system.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Calendar;
import java.util.Date;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.AttributeConverter;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ticketing.system.architecture.fixture.catalog.RoutedViewOutsideTheUi;
import com.ticketing.system.architecture.fixture.catalog.domain.ConverterUsingJackson;
import com.ticketing.system.architecture.fixture.catalog.domain.ConverterWithTypeReference;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingAnAdapter;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingHibernateAnnotation;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingHibernateSession;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingJackson;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingSharedExternal;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingSharedPersistence;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingSpring;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingTheApplicationLayer;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingTheRootPackage;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingVaadin;
import com.ticketing.system.architecture.fixture.identity.IdentityTypeUsingNotificationDispatch;
import com.ticketing.system.architecture.fixture.identity.application.service.ApplicationTypeUsingAnAdapter;
import com.ticketing.system.architecture.fixture.identity.application.service.AuthenticationService;
import com.ticketing.system.architecture.fixture.notifications.NotificationsTypeUsingOrganization;
import com.ticketing.system.architecture.fixture.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.architecture.fixture.organization.OrganizationFixtureType;
import com.ticketing.system.architecture.fixture.organization.OrganizationTypeUsingNotifications;
import com.ticketing.system.architecture.fixture.reporting.ReportingTypeUsingTheUi;
import com.ticketing.system.architecture.fixture.ui.RoutedUiView;
import com.ticketing.system.architecture.fixture.sales.domain.SalesDomainTypeUsingCatalogDomain;
import com.ticketing.system.architecture.fixture.ui.UiTypeBuildingADateFormatterWithoutALocale;
import com.ticketing.system.architecture.fixture.ui.UiTypeChangingCaseInTheJvmLocale;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingADateInTheJvmLocale;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingALocalizedDate;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingInAGivenLocale;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingThroughAMethodReference;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingWithDecimalFormat;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingWithFormatted;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingWithMessageFormat;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingWithNumberFormat;
import com.ticketing.system.architecture.fixture.ui.UiTypeFormattingWithStringFormat;
import com.ticketing.system.architecture.fixture.ui.UiTypeReadingTheDefaultLocale;
import com.ticketing.system.architecture.fixture.ui.UiTypeUsingADrivenPort;
import com.ticketing.system.architecture.fixture.ui.UiTypeUsingAnAdapter;
import com.ticketing.system.architecture.fixture.ui.UiTypeUsingAnApplicationService;
import com.ticketing.system.architecture.fixture.ui.UiTypeUsingAnInboundPort;
import com.ticketing.system.architecture.fixture.undocumented.UndocumentedModuleType;
import com.ticketing.system.notifications.config.NotificationAsyncConfig;
import com.ticketing.system.shared.config.SecurityConfig;
import com.ticketing.system.shared.event.PurchaseCompletedNotice;
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
import com.vaadin.flow.router.Route;

/**
 * ArchUnit hexagonal-layering rules for the bounded-context modules.
 *
 * <p>The rules are driven through the Jupiter engine (importing production classes with
 * {@link ClassFileImporter} and calling {@code check(...)}) rather than ArchUnit's {@code @ArchTest}
 * field engine, because that engine is not reliably enforced by this project's Surefire setup — its
 * rules silently reported zero executed tests and a deliberately-failing rule did not fail the build.
 * Plain {@code @Test} methods are guaranteed to run under Surefire and to fail on a violation.
 *
 * <p>Alongside the hexagonal-layering invariants, {@link #GivenProductionCode_WhenScanned_ThenBoundedContextsAreAcyclic()} enforces
     * that the bounded-context modules form a directed acyclic graph. This is the substantive cycle
     * guarantee: it uses ArchUnit slices (the same cycle engine Spring Modulith uses) run directly here,
     * so it applies even though the modules are declared <em>open</em> (open modules are exempt from
     * Modulith's own cycle check).
 *
 * <p>Acyclicity alone lets a new edge that closes no cycle through, so the module graph documented
 * in {@code .claude/CLAUDE.md} (Architecture) is enforced edge by edge as well, from
 * {@link #ALLOWED_MODULE_DEPENDENCIES}. The graph rules key on package names, so they are built
 * from a root package: the self-tests run them against offenders under {@link #FIXTURE_ROOT}.
 */
class HexagonalRulesTest {

    private static final String PRODUCTION_ROOT = "com.ticketing.system";

    // Fixture packages mirror module names below this root; see its package-info.
    private static final String FIXTURE_ROOT = "com.ticketing.system.architecture.fixture";

    // Production classes only (tests excluded), imported once and reused by the rules below. Only the
    // clock rules also run over the test classes, which testClassesOtherThanTheseFixtures imports.
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(PRODUCTION_ROOT);

    /**
     * The domain is the innermost hexagon: a context's {@code domain} package must not depend on the
     * Spring or Vaadin frameworks, nor outward on the {@code application} or {@code adapter} layers.
     * {@code jakarta.persistence} is deliberately allowed — the aggregates are the JPA entities
     * (the "persistence-annotated domain" decision), which is a spec dependency, not a framework one.
     *
     * <p>The shared kernel's infrastructure is outer layer too, even though the domain may otherwise use
     * {@code shared}: {@code shared.external} is the WSEP HTTP client and {@code shared.persistence}
     * holds the repository locks, the lock-translation aspect and the adapter profiles.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenTheDomainIsFreeOfFrameworkAndOuterLayers() {
        DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS.check(PRODUCTION_CLASSES);
    }

    // One offender per denied package, so a typo in any entry of the deny-list fails its own test.
    @Test
    void GivenADomainClassUsingSharedPersistence_WhenScanned_ThenTheDomainPurityRuleFails() {
        assertRuleFails(DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS, DomainTypeUsingSharedPersistence.class);
    }

    @Test
    void GivenADomainClassUsingSpring_WhenScanned_ThenTheDomainPurityRuleFails() {
        assertRuleFails(DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS, DomainTypeUsingSpring.class);
    }

    @Test
    void GivenADomainClassUsingVaadin_WhenScanned_ThenTheDomainPurityRuleFails() {
        assertRuleFails(DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS, DomainTypeUsingVaadin.class);
    }

    @Test
    void GivenADomainClassUsingTheApplicationLayer_WhenScanned_ThenTheDomainPurityRuleFails() {
        assertRuleFails(DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS, DomainTypeUsingTheApplicationLayer.class);
    }

    @Test
    void GivenADomainClassUsingAnAdapter_WhenScanned_ThenTheDomainPurityRuleFails() {
        assertRuleFails(DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS, DomainTypeUsingAnAdapter.class);
    }

    @Test
    void GivenADomainClassUsingSharedExternal_WhenScanned_ThenTheDomainPurityRuleFails() {
        assertRuleFails(DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS, DomainTypeUsingSharedExternal.class);
    }

    private static final ArchRule DOMAIN_IS_FREE_OF_FRAMEWORK_AND_OUTER_LAYERS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",   // no Spring in the domain
                    "com.vaadin..",             // no Vaadin in the domain
                    "..application..",           // domain must not reach up into the application layer
                    "..adapter..",               // domain must not reach out into adapters
                    "com.ticketing.system.shared.external..",     // no outbound HTTP client
                    "com.ticketing.system.shared.persistence..");  // no persistence infrastructure

    /**
     * The application layer (use-case services + ports) drives the domain through ports; it must never
     * depend on a concrete {@code adapter} (adapters implement the outbound ports and are injected by
     * Spring — the dependency arrow points inward, from adapter to application, never the reverse).
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenTheApplicationDoesNotDependOnAdapters() {
        APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS.check(PRODUCTION_CLASSES);
    }

    @Test
    void GivenAnApplicationClassUsingAnAdapter_WhenScanned_ThenTheApplicationRuleFails() {
        assertRuleFails(APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS, ApplicationTypeUsingAnAdapter.class);
    }

    private static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    /**
     * The Vaadin {@code ui} module is the single driving (inbound) adapter: it may depend on the
     * backend contexts' application APIs, but no backend <em>bounded context</em> may depend back on
     * {@code ui}. This is the invariant that keeps the UI a leaf of the dependency graph.
     *
     * <p>The rule lists the backend bounded contexts explicitly rather than "everything but ui" on
     * purpose: {@code bootstrap} (the composition-root sink) is deliberately allowed to reference
     * {@code ui} — it wires the app together, e.g. the notification polling scheduler drives a UI
     * session. {@code reporting} is gated too: the UI reads it, so it must not read the UI back.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenNoBackendContextDependsOnTheUi() {
        noBackendContextDependsOnTheUi(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    // Low risk, since the module map refuses these edges too; this proves the rule would still hold alone.
    @Test
    void GivenReportingUsingTheUi_WhenScanned_ThenTheNoBackendToUiRuleFails() {
        assertRuleFails(noBackendContextDependsOnTheUi(FIXTURE_ROOT), ReportingTypeUsingTheUi.class);
    }

    private static final List<String> BACKEND_MODULES = List.of("identity", "organization", "catalog", "sales",
            "messaging", "notifications", "governance", "reporting", "shared");

    private static ArchRule noBackendContextDependsOnTheUi(String root) {
        return noClasses()
                .that().resideInAnyPackage(BACKEND_MODULES.stream()
                        .map(module -> root + "." + module + "..")
                        .toArray(String[]::new))
                .should().dependOnClassesThat().resideInAPackage(root + ".ui..");
    }

    /**
     * {@code .claude/CLAUDE.md} (Architecture): each module depends only along the documented graph,
     * {@code shared ← identity ← organization ← catalog ← sales} and {@code organization ← messaging},
     * with {@code notifications} on {@code shared} alone, {@code governance} and {@code reporting}
     * reading the backend contexts other than {@code notifications} (a sink: it only consumes
     * notices), {@code ui} above them and {@code bootstrap} above everything. An
     * edge that closes no cycle would pass {@link #GivenProductionCode_WhenScanned_ThenBoundedContextsAreAcyclic()}, so only this rule
     * stops, say, {@code notifications} starting to read {@code organization}.
     *
     * <p>A class in a module that {@link #ALLOWED_MODULE_DEPENDENCIES} does not name fails outright,
     * so a new module is a decision about the graph rather than a silent addition to it: add it to the
     * map and to {@code .claude/CLAUDE.md} together.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenModulesDependOnlyAlongTheDocumentedGraph() {
        modulesDependOnlyAlongTheDocumentedGraph(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    /**
     * {@code .claude/CLAUDE.md} (Architecture): cross-context calls go through ports and events. A
     * context's domain therefore uses only its own context and the {@code shared} kernel, even where
     * the module graph lets the context itself depend on another one: a domain type reaching into
     * another context couples the two aggregates' models.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenEachDomainDependsOnlyOnItsOwnContextAndShared() {
        domainsDependOnlyOnTheirOwnContextAndShared(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    /**
     * {@code .claude/CLAUDE.md} (Architecture): domain classes double as the JPA entities, so Hibernate
     * mapping annotations ({@code @Fetch} on {@code VenueMap} and {@code Conversation}) are allowed in
     * the domain like {@code jakarta.persistence}. Hibernate's runtime API is not: a {@code Session}
     * or a Hibernate type in an aggregate would tie the model to one ORM's behaviour.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenTheDomainUsesOnlyHibernateAnnotations() {
        DOMAIN_USES_ONLY_HIBERNATE_ANNOTATIONS.check(PRODUCTION_CLASSES);
    }

    /**
     * {@code .claude/CLAUDE.md} (Architecture): domain classes double as the JPA entities, and a JSON
     * column needs a JPA {@code AttributeConverter} beside its entity, so Jackson is allowed in the
     * domain there and nowhere else ({@code PurchasePolicyJsonConverter},
     * {@code DiscountPolicyJsonConverter}, {@code NotificationDataJsonConverter}). The aggregates
     * themselves stay free of a serialisation library.
     *
     * <p>Classes nested in a converter, at any depth and anonymous ones included, count as the
     * converter: {@code new TypeReference<>() { }} compiles to a class of its own, but it is the
     * converter's own Jackson use, not a new dependency of the domain.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenOnlyAttributeConvertersUseJacksonInTheDomain() {
        DOMAIN_USES_JACKSON_ONLY_IN_ATTRIBUTE_CONVERTERS.check(PRODUCTION_CLASSES);
    }

    /**
     * {@code ui/package-info.java}: the UI is the single driving adapter and depends on every bounded
     * context's <em>inbound</em> application API. A driving adapter calls application services; a
     * context's {@code application.port.out} ports are its own driven side, and its adapters sit
     * behind them, so a view or presenter using either bypasses the use case (its authorisation,
     * transaction and invariants) and couples the UI to how the context stores and integrates.
     *
     * <p>{@code application.service}, {@code application.port.in}, {@code application.dto},
     * {@code application.event} and, for now, {@code domain} types stay open to the UI; the domain
     * types are tracked separately in #208.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenTheUiUsesNoOtherModulesDrivenPortsOrAdapters() {
        uiUsesNoOtherModulesDrivenPortsOrAdapters(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    /**
     * {@code application.yml}'s {@code vaadin.allowed-packages} limits Vaadin's route scan to
     * {@code com.ticketing.system.ui}, so a {@code @Route} class anywhere else is silently never routed.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenEveryRouteIsInTheUi() {
        routesResideInTheUi(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    @Test
    void GivenARouteOutsideTheUi_WhenScanned_ThenTheRouteRuleFails() {
        assertRuleFails(routesResideInTheUi(FIXTURE_ROOT), RoutedViewOutsideTheUi.class);
    }

    @Test
    void GivenARouteInTheUi_WhenScanned_ThenTheRouteRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(RoutedUiView.class);
        assertThatCode(() -> routesResideInTheUi(FIXTURE_ROOT).check(imported)).doesNotThrowAnyException();
    }

    private static ArchRule routesResideInTheUi(String root) {
        return classes().that().areAnnotatedWith(Route.class)
                .should().resideInAPackage(root + "." + UI_MODULE + "..")
                .because("application.yml's vaadin.allowed-packages routes only the ui package");
    }

    // The class comment records a rule engine that silently checked nothing; these prove each graph
    // rule fails on an offender and that its one exception stays as narrow as documented.
    @Test
    void GivenAUiClassUsingADrivenPort_WhenScanned_ThenTheUiDrivenSideRuleFails() {
        assertRuleFails(uiUsesNoOtherModulesDrivenPortsOrAdapters(FIXTURE_ROOT), UiTypeUsingADrivenPort.class);
    }

    @Test
    void GivenAUiClassUsingAnAdapter_WhenScanned_ThenTheUiDrivenSideRuleFails() {
        assertRuleFails(uiUsesNoOtherModulesDrivenPortsOrAdapters(FIXTURE_ROOT), UiTypeUsingAnAdapter.class);
    }

    @Test
    void GivenAUiClassUsingAnApplicationService_WhenScanned_ThenTheUiDrivenSideRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(UiTypeUsingAnApplicationService.class);
        assertThatCode(() -> uiUsesNoOtherModulesDrivenPortsOrAdapters(FIXTURE_ROOT).check(imported))
                .doesNotThrowAnyException();
    }

    @Test
    void GivenAUiClassUsingAnInboundPort_WhenScanned_ThenTheUiDrivenSideRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(UiTypeUsingAnInboundPort.class);
        assertThatCode(() -> uiUsesNoOtherModulesDrivenPortsOrAdapters(FIXTURE_ROOT).check(imported))
                .doesNotThrowAnyException();
    }

    @Test
    void GivenNotificationsDependingOnOrganization_WhenScanned_ThenTheModuleGraphRuleFails() {
        assertRuleFails(modulesDependOnlyAlongTheDocumentedGraph(FIXTURE_ROOT),
                NotificationsTypeUsingOrganization.class);
    }

    @Test
    void GivenAnotherIdentityClassUsingTheDispatchService_WhenScanned_ThenTheModuleGraphRuleFails() {
        assertRuleFails(modulesDependOnlyAlongTheDocumentedGraph(FIXTURE_ROOT),
                IdentityTypeUsingNotificationDispatch.class);
    }

    @Test
    void GivenAuthenticationServiceUsingTheDispatchServiceAndAnotherNotificationsType_WhenScanned_ThenOnlyTheOtherTypeFails() {
        JavaClasses imported = new ClassFileImporter().importClasses(AuthenticationService.class);
        assertThatThrownBy(() -> modulesDependOnlyAlongTheDocumentedGraph(FIXTURE_ROOT).check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(AuthenticationService.class.getName() + " depends on "
                        + NotificationsTypeUsingOrganization.class.getName())
                .hasMessageNotContaining(AuthenticationService.class.getName() + " depends on "
                        + NotificationDispatchService.class.getName());
    }

    @Test
    void GivenAModuleOutsideTheDocumentedGraph_WhenScanned_ThenTheModuleGraphRuleFails() {
        assertRuleFails(modulesDependOnlyAlongTheDocumentedGraph(FIXTURE_ROOT), UndocumentedModuleType.class);
    }

    /**
     * Each allowance in {@link #ALLOWED_CROSS_MODULE_EXCEPTIONS} documents a dependency that is due to
     * go (#53 moves the login's notification delivery behind an inbound port). Once production no
     * longer has the dependency, a left-over allowance would silently permit it to come back.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenEveryCrossModuleExceptionIsStillInUse() {
        assertEquals(List.of(), unusedCrossModuleExceptions(PRODUCTION_ROOT, PRODUCTION_CLASSES),
                "these cross-module exceptions no longer match a production dependency; delete them");
    }

    @Test
    void GivenNoClassHasTheExceptionsDependency_WhenScanned_ThenTheExceptionIsReportedUnused() {
        JavaClasses withoutTheCall = new ClassFileImporter().importClasses(IdentityTypeUsingNotificationDispatch.class);

        assertEquals(List.copyOf(ALLOWED_CROSS_MODULE_EXCEPTIONS),
                unusedCrossModuleExceptions(FIXTURE_ROOT, withoutTheCall));
    }

    // The case #53 creates: the origin class is still there, but its call to the target is gone.
    @Test
    void GivenTheOriginClassWithoutTheCall_WhenScanned_ThenTheExceptionIsReportedUnused() {
        JavaClasses callRemoved = new ClassFileImporter().importClasses(
                com.ticketing.system.architecture.fixture.stale.identity.application.service.AuthenticationService.class);

        assertEquals(List.copyOf(ALLOWED_CROSS_MODULE_EXCEPTIONS),
                unusedCrossModuleExceptions(FIXTURE_ROOT + ".stale", callRemoved));
    }

    @Test
    void GivenTheExceptionsDependencyIsPresent_WhenScanned_ThenNoExceptionIsReportedUnused() {
        JavaClasses withTheCall = new ClassFileImporter().importClasses(AuthenticationService.class);

        assertEquals(List.of(), unusedCrossModuleExceptions(FIXTURE_ROOT, withTheCall));
    }

    @Test
    void GivenADomainClassUsingTheRootPackage_WhenScanned_ThenTheDomainContainmentRuleFails() {
        assertRuleFails(domainsDependOnlyOnTheirOwnContextAndShared(FIXTURE_ROOT),
                DomainTypeUsingTheRootPackage.class);
    }

    @Test
    void GivenASalesDomainClassUsingTheCatalogDomain_WhenScanned_ThenTheDomainContainmentRuleFails() {
        assertRuleFails(domainsDependOnlyOnTheirOwnContextAndShared(FIXTURE_ROOT),
                SalesDomainTypeUsingCatalogDomain.class);
    }

    @Test
    void GivenADomainClassUsingAHibernateSession_WhenScanned_ThenTheHibernateRuleFails() {
        assertRuleFails(DOMAIN_USES_ONLY_HIBERNATE_ANNOTATIONS, DomainTypeUsingHibernateSession.class);
    }

    @Test
    void GivenADomainClassUsingAHibernateAnnotation_WhenScanned_ThenTheHibernateRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(DomainTypeUsingHibernateAnnotation.class);
        assertThatCode(() -> DOMAIN_USES_ONLY_HIBERNATE_ANNOTATIONS.check(imported)).doesNotThrowAnyException();
    }

    @Test
    void GivenADomainClassUsingJacksonOutsideAConverter_WhenScanned_ThenTheJacksonRuleFails() {
        assertRuleFails(DOMAIN_USES_JACKSON_ONLY_IN_ATTRIBUTE_CONVERTERS, DomainTypeUsingJackson.class);
    }

    @Test
    void GivenAnAttributeConverterUsingJackson_WhenScannedWithAnOffender_ThenOnlyTheOffenderIsReported() {
        assertOnlyTheJacksonOffenderIsReported(ConverterUsingJackson.class);
    }

    @Test
    void GivenAnAnonymousTypeReferenceInsideAConverter_WhenScannedWithAnOffender_ThenOnlyTheOffenderIsReported() {
        assertOnlyTheJacksonOffenderIsReported(ConverterWithTypeReference.class,
                ConverterWithTypeReference.MAP_TYPE.getClass());
    }

    // The offender keeps the rule's subject set non-empty, so the exemption is shown by the converter
    // classes being absent from the report rather than by a rule that checked nothing.
    private static void assertOnlyTheJacksonOffenderIsReported(Class<?> converter, Class<?>... nestedClasses) {
        JavaClasses imported = new ClassFileImporter().importClasses(Stream.concat(
                Stream.of(DomainTypeUsingJackson.class, converter), Stream.of(nestedClasses)).toList());
        assertThatThrownBy(() -> DOMAIN_USES_JACKSON_ONLY_IN_ATTRIBUTE_CONVERTERS.check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(DomainTypeUsingJackson.class.getName())
                .hasMessageNotContaining(converter.getName());
    }

    /**
     * The documented module graph ({@code .claude/CLAUDE.md}, Architecture): for each top-level module,
     * the other modules its classes may depend on. A module missing from this map is outside the
     * graph, and its classes fail the rule until the graph and its documentation name it.
     */
    private static final Map<String, Set<String>> ALLOWED_MODULE_DEPENDENCIES = Map.ofEntries(
            Map.entry("shared", Set.of()),
            Map.entry("identity", Set.of("shared")),
            Map.entry("organization", Set.of("shared", "identity")),
            Map.entry("catalog", Set.of("shared", "identity", "organization")),
            Map.entry("sales", Set.of("shared", "identity", "organization", "catalog")),
            Map.entry("messaging", Set.of("shared", "identity", "organization")),
            Map.entry("notifications", Set.of("shared")),
            Map.entry("governance", Set.of("shared", "identity", "organization", "catalog", "sales", "messaging")),
            Map.entry("reporting", Set.of("shared", "identity", "organization", "catalog", "sales", "messaging")),
            Map.entry("ui", Set.of("shared",
                    "identity", "organization", "catalog", "sales", "messaging", "notifications",
                    "governance", "reporting")),
            Map.entry("bootstrap", Set.of("shared",
                    "identity", "organization", "catalog", "sales", "messaging", "notifications",
                    "governance", "reporting", "ui")));

    // A class-to-class dependency, with both names relative to the root the rule is built from.
    private record ClassDependency(String origin, String target) {
    }

    // The documented known exception: login delivers pending notifications by calling the dispatch
    // service directly until it moves behind an inbound port. Pinned to this class pair so that it
    // cannot spread to other identity classes or to other notifications types.
    private static final Set<ClassDependency> ALLOWED_CROSS_MODULE_EXCEPTIONS = Set.of(new ClassDependency(
            "identity.application.service.AuthenticationService",
            "notifications.application.service.NotificationDispatchService"));

    private static final String SHARED_MODULE = "shared";

    private static final String DOMAIN_LAYER = "domain";

    private static final String UI_MODULE = "ui";

    // A context's driven side, below its module package: closed to the UI at any depth.
    private static final List<String> LAYERS_CLOSED_TO_THE_UI = List.of("application.port.out", "adapter");

    private static ArchRule uiUsesNoOtherModulesDrivenPortsOrAdapters(String root) {
        return classes().that(resideInModule(root, UI_MODULE))
                .should(notDependOnAnotherModulesDrivenSide(root))
                .because("ui/package-info.java: the UI depends on each bounded context's inbound application API");
    }

    private static ArchRule modulesDependOnlyAlongTheDocumentedGraph(String root) {
        return classes().that(resideInAModule(root))
                .should(dependOnlyOnTheirAllowedModules(root))
                .because(".claude/CLAUDE.md (Architecture) documents the module dependency graph");
    }

    private static ArchRule domainsDependOnlyOnTheirOwnContextAndShared(String root) {
        return classes().that(resideInADomain(root))
                .should(dependOnlyOnTheirOwnContextAndShared(root))
                .because(".claude/CLAUDE.md (Architecture): cross-context calls go through ports and events");
    }

    private static final ArchRule DOMAIN_USES_ONLY_HIBERNATE_ANNOTATIONS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(resideInAPackage("org.hibernate..")
                    .and(not(resideInAPackage("org.hibernate.annotations.."))))
            .because("the JPA entities may carry Hibernate mapping annotations, not Hibernate's runtime API");

    // Walks the enclosing classes too, so a converter's anonymous TypeReference counts as the converter.
    private static final DescribedPredicate<JavaClass> BELONG_TO_AN_ATTRIBUTE_CONVERTER = DescribedPredicate.describe(
            "implement AttributeConverter or are nested in a class that does", javaClass -> {
                for (Optional<JavaClass> current = Optional.of(javaClass); current.isPresent();
                        current = current.get().getEnclosingClass()) {
                    if (current.get().isAssignableTo(AttributeConverter.class)) {
                        return true;
                    }
                }
                return false;
            });

    private static final ArchRule DOMAIN_USES_JACKSON_ONLY_IN_ATTRIBUTE_CONVERTERS = noClasses()
            .that().resideInAPackage("..domain..")
            .and(not(BELONG_TO_AN_ATTRIBUTE_CONVERTER))
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
            .because("Jackson serialises JSON columns inside JPA attribute converters, never in the aggregates");

    // The top-level module a package belongs to: empty for the root package itself, whose
    // application class is no module, and for packages outside the root.
    private static Optional<String> moduleOf(String packageName, String root) {
        String prefix = root + ".";
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }
        String belowRoot = packageName.substring(prefix.length());
        int dot = belowRoot.indexOf('.');
        return Optional.of(dot < 0 ? belowRoot : belowRoot.substring(0, dot));
    }

    private static boolean isDomainPackage(String packageName, String root) {
        return moduleOf(packageName, root)
                .map(module -> isInOrBelow(packageName, root + "." + module + "." + DOMAIN_LAYER))
                .orElse(false);
    }

    private static boolean isInOrBelow(String packageName, String ancestor) {
        return packageName.equals(ancestor) || packageName.startsWith(ancestor + ".");
    }

    private static DescribedPredicate<JavaClass> resideInModule(String root, String module) {
        return DescribedPredicate.describe("reside in module " + module + " under " + root,
                javaClass -> isInOrBelow(javaClass.getPackageName(), root + "." + module));
    }

    private static ArchCondition<JavaClass> notDependOnAnotherModulesDrivenSide(String root) {
        return new ArchCondition<>("not depend on another module's " + LAYERS_CLOSED_TO_THE_UI + " packages") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String module = moduleOf(origin.getPackageName(), root).orElseThrow();
                dependencyTargetsOf(origin).forEach(target -> moduleOf(target.getPackageName(), root)
                        .filter(targetModule -> !targetModule.equals(module))
                        .filter(targetModule -> LAYERS_CLOSED_TO_THE_UI.stream().anyMatch(layer ->
                                isInOrBelow(target.getPackageName(), root + "." + targetModule + "." + layer)))
                        .ifPresent(targetModule -> events.add(SimpleConditionEvent.violated(origin,
                                origin.getName() + " depends on " + target.getName() + ", but the ui may use "
                                        + targetModule + " only through its application API"))));
            }
        };
    }

    // The classes an origin depends on, arrays reduced to their element type so that a Foo[] field
    // counts as a dependency on Foo.
    private static Stream<JavaClass> dependencyTargetsOf(JavaClass origin) {
        return origin.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getBaseComponentType())
                .distinct();
    }

    private static DescribedPredicate<JavaClass> resideInAModule(String root) {
        return DescribedPredicate.describe("reside in a module under " + root,
                javaClass -> moduleOf(javaClass.getPackageName(), root).isPresent());
    }

    private static DescribedPredicate<JavaClass> resideInADomain(String root) {
        return DescribedPredicate.describe("reside in a module's domain under " + root,
                javaClass -> isDomainPackage(javaClass.getPackageName(), root));
    }

    private static ArchCondition<JavaClass> dependOnlyOnTheirAllowedModules(String root) {
        return new ArchCondition<>("depend on other modules only along the documented module graph") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String module = moduleOf(origin.getPackageName(), root).orElseThrow();
                Set<String> allowed = ALLOWED_MODULE_DEPENDENCIES.get(module);
                if (allowed == null) {
                    events.add(SimpleConditionEvent.violated(origin, origin.getName() + " is in module "
                            + module + ", which the documented module graph does not name"));
                    return;
                }
                dependencyTargetsOf(origin).forEach(target -> moduleOf(target.getPackageName(), root)
                        .filter(targetModule -> !targetModule.equals(module) && !allowed.contains(targetModule))
                        .filter(targetModule -> !isAllowedException(origin, target, root))
                        .ifPresent(targetModule -> events.add(SimpleConditionEvent.violated(origin,
                                origin.getName() + " depends on " + target.getName() + ", but module " + module
                                        + " may depend only on " + new TreeSet<>(allowed)))));
            }
        };
    }

    private static boolean isAllowedException(JavaClass origin, JavaClass target, String root) {
        int relativeStart = root.length() + 1;
        return ALLOWED_CROSS_MODULE_EXCEPTIONS.contains(new ClassDependency(
                origin.getName().substring(relativeStart), target.getName().substring(relativeStart)));
    }

    // The allowances whose class pair no longer appears among the classes' direct dependencies.
    private static List<ClassDependency> unusedCrossModuleExceptions(String root, JavaClasses classes) {
        return ALLOWED_CROSS_MODULE_EXCEPTIONS.stream()
                .filter(allowance -> {
                    String origin = root + "." + allowance.origin();
                    String target = root + "." + allowance.target();
                    return !classes.contain(origin) || dependencyTargetsOf(classes.get(origin))
                            .noneMatch(dependency -> dependency.getName().equals(target));
                })
                .sorted(Comparator.comparing(ClassDependency::origin).thenComparing(ClassDependency::target))
                .toList();
    }

    private static ArchCondition<JavaClass> dependOnlyOnTheirOwnContextAndShared(String root) {
        return new ArchCondition<>("depend only on their own context and " + SHARED_MODULE) {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String module = moduleOf(origin.getPackageName(), root).orElseThrow();
                dependencyTargetsOf(origin)
                        .filter(target -> target.getPackageName().equals(root)
                                || moduleOf(target.getPackageName(), root)
                                        .filter(targetModule -> !targetModule.equals(module)
                                                && !targetModule.equals(SHARED_MODULE))
                                        .isPresent())
                        .forEach(target -> events.add(SimpleConditionEvent.violated(origin,
                                origin.getName() + " depends on " + target.getName() + ", but the " + module
                                        + " domain may depend only on " + module + " and " + SHARED_MODULE)));
            }
        };
    }

    /**
     * The bounded contexts must form a directed acyclic graph: no two top-level modules may depend on
     * each other, directly or transitively. This is the substantive guarantee that Spring Modulith's
     * {@code verify()} would give for cycles — enforced here with ArchUnit slices because it analyses
     * real bytecode package dependencies, independent of Modulith's module-encapsulation model (under
     * which OPEN modules are exempt from cycle checks).
     */
    
    @Test
    void GivenProductionCode_WhenScanned_ThenBoundedContextsAreAcyclic() {
        boundedContextsAreAcyclic(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    // Low risk, since an acyclic module map already implies it; this proves the rule would still hold alone.
    @Test
    void GivenTwoModulesDependingOnEachOther_WhenScanned_ThenTheAcyclicRuleFails() {
        JavaClasses imported = new ClassFileImporter().importClasses(OrganizationTypeUsingNotifications.class,
                NotificationsTypeUsingOrganization.class, OrganizationFixtureType.class);

        assertThatThrownBy(() -> boundedContextsAreAcyclic(FIXTURE_ROOT).check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OrganizationTypeUsingNotifications.class.getSimpleName())
                .hasMessageContaining(NotificationsTypeUsingOrganization.class.getSimpleName());
    }

    private static ArchRule boundedContextsAreAcyclic(String root) {
        return slices()
                .matching(root + ".(*)..")   // one slice per top-level module (catalog, sales, ...)
                .should().beFreeOfCycles();
    }

    /**
     * ADR-004: every {@code shared.event} notice is consumed after the publisher commits, off its
     * thread. Publishers no longer guard {@code publishEvent}, so a synchronous listener on a notice
     * would let a notification failure fail the use case, e.g. report a paid checkout as failed. A
     * listener in another phase would deliver work that has not committed, and one without fallback
     * execution would drop the notices checkout and event cancellation publish after their
     * transactions end. The listener must run on {@code notificationExecutor}, the single
     * {@code notify-} thread whose submission order ADR-004 relies on: a bare {@code @Async}, or one
     * naming another executor, runs on a pool that can reorder notices or lacks the drop-and-log bound.
     */
    @Test
    void GivenANoticeListener_WhenScanned_ThenItIsAnAsyncAfterCommitListener() {
        NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT.check(PRODUCTION_CLASSES);
    }

    @Test
    void GivenAnAsyncNoticeListenerOutsideTheTransaction_WhenScanned_ThenTheListenerRuleFails() {
        assertRuleFails(NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT, AsyncPlainNoticeListener.class);
    }

    @Test
    void GivenANoticeListenerWithoutAsync_WhenScanned_ThenTheListenerRuleFails() {
        assertRuleFails(NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT, NoticeListenerWithoutAsync.class);
    }

    @Test
    void GivenABeforeCommitNoticeListener_WhenScanned_ThenTheListenerRuleFails() {
        assertRuleFails(NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT, BeforeCommitNoticeListener.class);
    }

    @Test
    void GivenANoticeListenerWithoutFallbackExecution_WhenScanned_ThenTheListenerRuleFails() {
        assertRuleFails(NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT, NoticeListenerWithoutFallbackExecution.class);
    }

    @Test
    void GivenANoticeListenerOnTheDefaultExecutor_WhenScanned_ThenTheListenerRuleFails() {
        assertRuleFails(NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT, NoticeListenerOnTheDefaultExecutor.class);
    }

    @Test
    void GivenANoticeListenerOnAnotherExecutor_WhenScanned_ThenTheListenerRuleFails() {
        assertRuleFails(NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT, NoticeListenerOnAnotherExecutor.class);
    }

    @Test
    void GivenANoticeListenerWhoseMethodPicksAnotherExecutor_WhenScanned_ThenTheListenerRuleFails() {
        assertRuleFails(NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT, NoticeListenerWhoseMethodPicksAnotherExecutor.class);
    }

    @Test
    void GivenTheDocumentedNoticeListener_WhenScanned_ThenTheListenerRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(DocumentedNoticeListener.class);
        assertThatCode(() -> NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT.check(imported)).doesNotThrowAnyException();
    }

    private static final DescribedPredicate<JavaMethod> TAKE_A_SHARED_EVENT_NOTICE =
            DescribedPredicate.describe("take a shared.event notice", method -> method.getRawParameterTypes()
                    .stream().anyMatch(type -> type.getPackageName().equals("com.ticketing.system.shared.event")));

    private static final String ASYNC_ON_THE_NOTIFICATION_EXECUTOR =
            "@Async(\"" + NotificationAsyncConfig.NOTIFICATION_EXECUTOR + "\")";

    private static final ArchCondition<JavaMethod> BE_ASYNC_AFTER_COMMIT_LISTENERS =
            new ArchCondition<>("be " + ASYNC_ON_THE_NOTIFICATION_EXECUTOR
                    + " @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true) methods") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean afterCommitWithFallback = method.tryGetAnnotationOfType(TransactionalEventListener.class)
                            .filter(listener -> listener.phase() == TransactionPhase.AFTER_COMMIT)
                            .filter(TransactionalEventListener::fallbackExecution)
                            .isPresent();
                    // Spring takes the executor from the method's @Async first, then from its class's.
                    String executor = method.tryGetAnnotationOfType(Async.class)
                            .or(() -> method.getOwner().tryGetAnnotationOfType(Async.class))
                            .map(Async::value)
                            .orElse(null);
                    boolean onTheNotificationExecutor = NotificationAsyncConfig.NOTIFICATION_EXECUTOR.equals(executor);
                    boolean satisfied = afterCommitWithFallback && onTheNotificationExecutor;
                    events.add(new SimpleConditionEvent(method, satisfied, method.getFullName()
                            + (satisfied ? " is" : " is not") + " an " + ASYNC_ON_THE_NOTIFICATION_EXECUTOR
                            + " @TransactionalEventListener (phase = AFTER_COMMIT, fallbackExecution = true)"));
                }
            };

    private static final ArchRule NOTICE_LISTENERS_ARE_ASYNC_AFTER_COMMIT = methods()
            .that().areAnnotatedWith(EventListener.class)
            .or().areMetaAnnotatedWith(EventListener.class)
            .and(TAKE_A_SHARED_EVENT_NOTICE)
            .should(BE_ASYNC_AFTER_COMMIT_LISTENERS)
            .because("ADR-004: notices are delivered after commit, off the publisher's thread");

    /**
     * ADR-008: every "now" comes from the one injected UTC {@link Clock}, and no zone comes from the
     * JVM. Production code must not call or reference (e.g. {@code Instant::now}) anything that reads
     * the system clock or the default zone: {@code now()}/{@code now(ZoneId)} on a {@code java.time}
     * type, a {@code Clock.tick*(ZoneId)} or {@code InstantSource.system()} clock, {@code new Date()},
     * {@code Calendar.getInstance}, {@code System.currentTimeMillis()}, or the default-zone lookups.
     * {@code System.nanoTime()} stays allowed: it measures a duration and reads no wall clock.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenNothingReadsTheSystemClockOrDefaultZone() {
        NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE.check(PRODUCTION_CLASSES);
    }

    /** ADR-008: the Clock bean ({@code SecurityConfig.systemClock}) is the only system clock. */
    @Test
    void GivenProductionCode_WhenScanned_ThenOnlyTheClockBeanCreatesASystemClock() {
        ONLY_THE_CLOCK_BEAN_CREATES_A_SYSTEM_CLOCK.check(PRODUCTION_CLASSES);
    }

    // ADR-024: test code reads time from a clock too. In the 17 PRs after ADR-008, 4 added such reads, and
    // each cost a review finding and a fix commit. Both rules are proven by the self-tests below.
    @Test
    void GivenTestCode_WhenScanned_ThenNothingReadsTheSystemClockOrDefaultZone() {
        JavaClasses testClasses = testClassesOtherThanTheseFixtures();

        NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE.check(testClasses);
        ONLY_THE_CLOCK_BEAN_CREATES_A_SYSTEM_CLOCK.check(testClasses);
    }

    @Test
    void GivenTheTestClassesTheClockRulesScan_WhenInspected_ThenTheyHoldAbstractContractTestsButNotTheseFixtures() {
        JavaClasses testClasses = testClassesOtherThanTheseFixtures();

        assertThat(testClasses.contain(PRODUCTION_ROOT
                + ".unit.infrastructure.persistence.UserPersistence.IUserRepositoryContractTest")).isTrue();
        assertThat(testClasses.contain(CallsLocalDateTimeNow.class)).isFalse();
    }

    // Imported per call, not held in a static, so the classes aren't kept for the rest of the shared test JVM.
    private static JavaClasses testClassesOtherThanTheseFixtures() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("/architecture/HexagonalRulesTest$"))
                .importPackages(PRODUCTION_ROOT);
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

    @Test
    void GivenANewDate_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(CreatesADate.class);
    }

    @Test
    void GivenATickingSystemClock_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(CreatesATickingClock.class);
    }

    // ---- UI-B17: System.currentTimeMillis() is a system-clock read too (#187) ----

    @Test
    void GivenACallToSystemCurrentTimeMillis_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(CallsSystemCurrentTimeMillis.class);
    }

    @Test
    void GivenAReferenceToSystemCurrentTimeMillis_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(ReferencesSystemCurrentTimeMillis.class);
    }

    // A duration measure reads no wall clock, so the rule must leave it alone.
    @Test
    void GivenACallToSystemNanoTime_WhenScanned_ThenTheClockRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(MeasuresADuration.class);
        assertThatCode(() -> NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE.check(imported)).doesNotThrowAnyException();
    }

    @Test
    void GivenACallToNowInAZone_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(CallsLocalDateTimeNowInAZone.class);
    }

    @Test
    void GivenAReadOfTheDefaultTimeZone_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(ReadsTheDefaultTimeZone.class);
    }

    @Test
    void GivenADefaultZoneSystemClock_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(CreatesADefaultZoneClock.class);
    }

    @Test
    void GivenTheSystemInstantSource_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(ReadsTheSystemInstantSource.class);
    }

    @Test
    void GivenACalendarInstance_WhenScanned_ThenTheClockRuleFails() {
        assertClockRuleFails(CreatesACalendar.class);
    }

    // Clock.tick(Clock, Duration) only rounds the clock it is given, so it reads no system clock.
    @Test
    void GivenATickingClockDerivedFromAGivenClock_WhenScanned_ThenTheClockRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(DerivesATickingClock.class);
        assertThatCode(() -> NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE.check(imported)).doesNotThrowAnyException();
    }

    @Test
    void GivenAReferenceToClockSystemUtcOutsideTheClockBean_WhenScanned_ThenTheBeanRuleFails() {
        assertRuleFails(ONLY_THE_CLOCK_BEAN_CREATES_A_SYSTEM_CLOCK, ReferencesClockSystemUtc.class);
    }

    @Test
    void GivenAZonedSystemClockOutsideTheClockBean_WhenScanned_ThenTheBeanRuleFails() {
        assertRuleFails(ONLY_THE_CLOCK_BEAN_CREATES_A_SYSTEM_CLOCK, CreatesAZonedSystemClock.class);
    }

    private static void assertClockRuleFails(Class<?> offender) {
        assertRuleFails(NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE, offender);
    }

    private static void assertRuleFails(ArchRule rule, Class<?> offender) {
        JavaClasses imported = new ClassFileImporter().importClasses(offender);
        assertThatThrownBy(() -> rule.check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offender.getSimpleName());
    }

    // ArchUnit's name for a constructor access target.
    private static final String CONSTRUCTOR = "<init>";

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
                // Clock.tick(Clock, Duration) derives from a given clock and is allowed; the
                // tickSeconds/tickMillis/tickMinutes(ZoneId) factories tick on the system clock.
                boolean otherSystemClock = (owner.equals(Clock.class.getName()) && name.startsWith("tick")
                                && !name.equals("tick"))
                        || (owner.equals(InstantSource.class.getName()) && name.equals("system"))
                        || (owner.equals(Date.class.getName()) && name.equals(CONSTRUCTOR) && parameters.isEmpty())
                        || (owner.equals(Calendar.class.getName()) && name.equals("getInstance"))
                        || (owner.equals(System.class.getName()) && name.equals("currentTimeMillis"));
                return systemNow || defaultZone || otherSystemClock;
            });

    private static final DescribedPredicate<JavaAccess<?>> CREATE_A_SYSTEM_CLOCK =
            DescribedPredicate.describe("create a system clock", access ->
                    access.getTarget().getOwner().getName().equals(Clock.class.getName())
                            && (access.getTarget().getName().equals("systemUTC")
                                    || access.getTarget().getName().equals("system")));

    private static final ArchRule NO_SYSTEM_CLOCK_OR_DEFAULT_ZONE = noClasses()
            .should().accessTargetWhere(READ_THE_SYSTEM_CLOCK_OR_DEFAULT_ZONE)
            .because("ADR-008: read time from the injected Clock; zones come from the event or platform.time-zone");

    // accessTargetWhere, unlike callMethod, also matches method references such as Clock::systemUTC.
    private static final ArchRule ONLY_THE_CLOCK_BEAN_CREATES_A_SYSTEM_CLOCK = noClasses()
            .that().doNotHaveFullyQualifiedName(SecurityConfig.class.getName())
            .should().accessTargetWhere(CREATE_A_SYSTEM_CLOCK)
            .because("ADR-008: SecurityConfig.systemClock is the one clock everything else injects");

    /**
     * #189: the pages are English, so {@code ui} formats every date and number, and changes the case of text, in
     * a locale it names: {@code DisplayLocale.PAGE} for text a page shows, {@code Locale.ROOT} for machine text.
     * A call given no locale takes the server JVM's default instead, which on a German server wrote "Okt." and
     * "$1.234,50" on an English page, and on a Turkish one turns "i" into "İ". The rule refuses each such call
     * the {@code ui} could reach: {@code DateTimeFormatter.ofPattern(String)} and {@code ofLocalized…},
     * {@code DateTimeFormatterBuilder.toFormatter()}, {@code String.format(String, Object...)},
     * {@code String.formatted}, {@code NumberFormat}'s {@code get…Instance()}, {@code new DecimalFormat()} and
     * {@code new DecimalFormat(String)}, {@code new MessageFormat(String)} and the static
     * {@code MessageFormat.format}, {@code Locale.getDefault}, and {@code toLowerCase()}/{@code toUpperCase()},
     * method references included. {@code PageLocaleDatesTest}, {@code PageLocaleNumbersTest} and
     * {@code PageLocaleCaseTest} state that behaviour, but a view keeps its formatters in static fields, built
     * when an earlier test may already have loaded it, so this rule is what catches a regression in any order.
     *
     * <p>Kept to {@code ui}: outside it only the notifications and the dev seed report format text, with
     * {@code %s}, which no locale changes, and the seed report's ungrouped {@code %d}, which only a locale with
     * digits of its own (Arabic, Persian) changes, in a log. The backend's two case changes are left unchecked
     * too; neither reaches a page.
     */
    @Test
    void GivenProductionCode_WhenScanned_ThenTheUiFormatsOnlyInALocaleItNames() {
        uiFormatsOnlyInALocaleItNames(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    @Test
    void GivenAUiDateFormatterBuiltWithoutALocale_WhenScanned_ThenTheLocaleRuleFails() {
        assertRuleFails(uiFormatsOnlyInALocaleItNames(FIXTURE_ROOT), UiTypeFormattingADateInTheJvmLocale.class);
    }

    @Test
    void GivenAUiStringFormatWithoutALocale_WhenScanned_ThenTheLocaleRuleFails() {
        assertRuleFails(uiFormatsOnlyInALocaleItNames(FIXTURE_ROOT), UiTypeFormattingWithStringFormat.class);
    }

    @Test
    void GivenAUiStringFormatted_WhenScanned_ThenTheLocaleRuleFails() {
        assertRuleFails(uiFormatsOnlyInALocaleItNames(FIXTURE_ROOT), UiTypeFormattingWithFormatted.class);
    }

    @Test
    void GivenUiDatesInALocalizedStyle_WhenScanned_ThenTheLocaleRuleFailsOnEach() {
        assertLocaleRuleFailsOn(UiTypeFormattingALocalizedDate.class, "DateTimeFormatter.ofLocalizedDate(",
                "DateTimeFormatter.ofLocalizedTime(", "DateTimeFormatter.ofLocalizedDateTime(");
    }

    @Test
    void GivenAUiDateFormatterBuilderFinishedWithoutALocale_WhenScanned_ThenTheLocaleRuleFails() {
        assertLocaleRuleFailsOn(UiTypeBuildingADateFormatterWithoutALocale.class,
                "DateTimeFormatterBuilder.toFormatter()");
    }

    @Test
    void GivenUiNumberFormatsOfTheJvmLocale_WhenScanned_ThenTheLocaleRuleFailsOnEach() {
        assertLocaleRuleFailsOn(UiTypeFormattingWithNumberFormat.class, "NumberFormat.getInstance()",
                "NumberFormat.getNumberInstance()", "NumberFormat.getIntegerInstance()",
                "NumberFormat.getCurrencyInstance()", "NumberFormat.getPercentInstance()",
                "NumberFormat.getCompactNumberInstance()");
    }

    @Test
    void GivenUiDecimalFormatsGivenNoSymbols_WhenScanned_ThenTheLocaleRuleFailsOnEach() {
        assertLocaleRuleFailsOn(UiTypeFormattingWithDecimalFormat.class,
                "DecimalFormat." + CONSTRUCTOR + "(java.lang.String)", "DecimalFormat." + CONSTRUCTOR + "()");
    }

    @Test
    void GivenUiMessageFormatsGivenNoLocale_WhenScanned_ThenTheLocaleRuleFailsOnEach() {
        assertLocaleRuleFailsOn(UiTypeFormattingWithMessageFormat.class,
                "MessageFormat." + CONSTRUCTOR + "(java.lang.String)",
                "MessageFormat.format(java.lang.String, [Ljava.lang.Object;)");
    }

    @Test
    void GivenAUiClassReadingTheJvmLocale_WhenScanned_ThenTheLocaleRuleFailsOnEach() {
        assertLocaleRuleFailsOn(UiTypeReadingTheDefaultLocale.class, "Locale.getDefault()",
                "Locale.getDefault(java.util.Locale$Category)");
    }

    @Test
    void GivenUiCaseChangesWithoutALocale_WhenScanned_ThenTheLocaleRuleFailsOnEach() {
        assertLocaleRuleFailsOn(UiTypeChangingCaseInTheJvmLocale.class, "String.toLowerCase()",
                "String.toUpperCase()");
    }

    // A method reference is an access too, so passing the call around does not escape the rule.
    @Test
    void GivenUiMethodReferencesThatFormatOrChangeCaseInTheJvmLocale_WhenScanned_ThenTheLocaleRuleFailsOnEach() {
        assertLocaleRuleFailsOn(UiTypeFormattingThroughAMethodReference.class,
                "String.formatted([Ljava.lang.Object;)", "String.toUpperCase()");
    }

    @Test
    void GivenAUiClassFormattingInALocaleItNames_WhenScanned_ThenTheLocaleRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(UiTypeFormattingInAGivenLocale.class);
        assertThatCode(() -> uiFormatsOnlyInALocaleItNames(FIXTURE_ROOT).check(imported))
                .doesNotThrowAnyException();
    }

    // Names every call in the offender, so a fixture with one call per form proves each form is caught.
    private static void assertLocaleRuleFailsOn(Class<?> offender, String... calls) {
        JavaClasses imported = new ClassFileImporter().importClasses(offender);
        assertThatThrownBy(() -> uiFormatsOnlyInALocaleItNames(FIXTURE_ROOT).check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offender.getSimpleName())
                .hasMessageContainingAll(calls);
    }

    private static final DescribedPredicate<JavaAccess<?>> FORMAT_IN_THE_JVM_LOCALE =
            DescribedPredicate.describe("format or change case in the JVM's default locale", access -> {
                if (!(access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target)) {
                    return false;
                }
                String owner = target.getOwner().getName();
                String name = target.getName();
                List<JavaClass> parameters = target.getRawParameterTypes();
                boolean datePattern = owner.equals(DateTimeFormatter.class.getName()) && name.equals("ofPattern")
                        && parameters.size() == 1;
                // The localized styles have no form that takes a locale; ofPattern(String, Locale) does.
                boolean localizedDate = owner.equals(DateTimeFormatter.class.getName())
                        && name.startsWith("ofLocalized");
                boolean builtDateFormatter = owner.equals(DateTimeFormatterBuilder.class.getName())
                        && name.equals("toFormatter") && parameters.isEmpty();
                // String.format(Locale, String, Object...) names its locale first, so it is the allowed form.
                boolean stringFormat = owner.equals(String.class.getName()) && name.equals("format")
                        && !parameters.isEmpty() && parameters.get(0).isEquivalentTo(String.class);
                // formatted has no form that takes a locale.
                boolean formatted = owner.equals(String.class.getName()) && name.equals("formatted");
                // DecimalFormat inherits NumberFormat's factories, and a call through it names DecimalFormat.
                boolean numberFormat = (owner.equals(NumberFormat.class.getName())
                                || owner.equals(DecimalFormat.class.getName()))
                        && name.startsWith("get") && name.endsWith("Instance") && parameters.isEmpty();
                // new DecimalFormat(String, DecimalFormatSymbols) is given its symbols, so it is the allowed form.
                boolean decimalFormat = owner.equals(DecimalFormat.class.getName()) && name.equals(CONSTRUCTOR)
                        && parameters.size() < 2;
                // The static format(String, Object...) has no form that takes a locale; an instance made with
                // new MessageFormat(String, Locale) formats in that locale.
                boolean messageFormat = owner.equals(MessageFormat.class.getName())
                        && ((name.equals(CONSTRUCTOR) && parameters.size() == 1)
                                || (name.equals("format") && parameters.size() == 2
                                        && parameters.get(0).isEquivalentTo(String.class)));
                boolean defaultLocale = owner.equals(Locale.class.getName()) && name.equals("getDefault");
                boolean caseChange = owner.equals(String.class.getName())
                        && (name.equals("toLowerCase") || name.equals("toUpperCase")) && parameters.isEmpty();
                return datePattern || localizedDate || builtDateFormatter || stringFormat || formatted
                        || numberFormat || decimalFormat || messageFormat || defaultLocale || caseChange;
            });

    private static ArchRule uiFormatsOnlyInALocaleItNames(String root) {
        return noClasses().that(resideInModule(root, UI_MODULE))
                .should().accessTargetWhere(FORMAT_IN_THE_JVM_LOCALE)
                .because("#189: the pages are English; name DisplayLocale.PAGE for page text, Locale.ROOT for "
                        + "machine text");
    }

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

    static class CreatesADate {
        Date read() {
            return new Date();
        }
    }

    static class CreatesATickingClock {
        Clock read() {
            return Clock.tickSeconds(ZoneOffset.UTC);
        }
    }

    static class CallsSystemCurrentTimeMillis {
        long read() {
            return System.currentTimeMillis();
        }
    }

    static class ReferencesSystemCurrentTimeMillis {
        LongSupplier read() {
            return System::currentTimeMillis;
        }
    }

    static class MeasuresADuration {
        long read() {
            return System.nanoTime();
        }
    }

    static class ReferencesClockSystemUtc {
        Supplier<Clock> read() {
            return Clock::systemUTC;
        }
    }

    static class CallsLocalDateTimeNowInAZone {
        LocalDateTime read() {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    static class ReadsTheDefaultTimeZone {
        TimeZone read() {
            return TimeZone.getDefault();
        }
    }

    static class CreatesADefaultZoneClock {
        Clock read() {
            return Clock.systemDefaultZone();
        }
    }

    static class ReadsTheSystemInstantSource {
        InstantSource read() {
            return InstantSource.system();
        }
    }

    static class CreatesACalendar {
        Calendar read() {
            return Calendar.getInstance();
        }
    }

    static class DerivesATickingClock {
        Clock read(Clock clock) {
            return Clock.tick(clock, Duration.ofSeconds(1));
        }
    }

    static class CreatesAZonedSystemClock {
        Clock read() {
            return Clock.system(ZoneOffset.UTC);
        }
    }

    // Fixtures for the notice-listener self-tests: each offender breaks exactly one part of the ADR-004
    // pattern, and none is a bean, so no Spring context ever delivers a notice to one.
    static class AsyncPlainNoticeListener {
        @Async(NotificationAsyncConfig.NOTIFICATION_EXECUTOR)
        @EventListener
        void on(PurchaseCompletedNotice notice) {
        }
    }

    static class NoticeListenerWithoutAsync {
        @TransactionalEventListener(fallbackExecution = true)
        void on(PurchaseCompletedNotice notice) {
        }
    }

    @Async(NotificationAsyncConfig.NOTIFICATION_EXECUTOR)
    static class BeforeCommitNoticeListener {
        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
        void on(PurchaseCompletedNotice notice) {
        }
    }

    @Async(NotificationAsyncConfig.NOTIFICATION_EXECUTOR)
    static class NoticeListenerWithoutFallbackExecution {
        @TransactionalEventListener
        void on(PurchaseCompletedNotice notice) {
        }
    }

    @Async
    static class NoticeListenerOnTheDefaultExecutor {
        @TransactionalEventListener(fallbackExecution = true)
        void on(PurchaseCompletedNotice notice) {
        }
    }

    @Async("taskScheduler")
    static class NoticeListenerOnAnotherExecutor {
        @TransactionalEventListener(fallbackExecution = true)
        void on(PurchaseCompletedNotice notice) {
        }
    }

    // The method's @Async wins over its class's, so this listener runs on taskScheduler.
    @Async(NotificationAsyncConfig.NOTIFICATION_EXECUTOR)
    static class NoticeListenerWhoseMethodPicksAnotherExecutor {
        @Async("taskScheduler")
        @TransactionalEventListener(fallbackExecution = true)
        void on(PurchaseCompletedNotice notice) {
        }
    }

    // Mirrors NotificationEventListener: @Async on the class, the listener annotation on the method.
    @Async(NotificationAsyncConfig.NOTIFICATION_EXECUTOR)
    static class DocumentedNoticeListener {
        @TransactionalEventListener(fallbackExecution = true)
        void on(PurchaseCompletedNotice notice) {
        }
    }
}
