package com.ticketing.system.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.AttributeConverter;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ticketing.system.architecture.fixture.catalog.domain.ConverterUsingJackson;
import com.ticketing.system.architecture.fixture.catalog.domain.ConverterWithTypeReference;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingHibernateAnnotation;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingHibernateSession;
import com.ticketing.system.architecture.fixture.catalog.domain.DomainTypeUsingJackson;
import com.ticketing.system.architecture.fixture.identity.IdentityTypeUsingNotificationDispatch;
import com.ticketing.system.architecture.fixture.identity.application.service.AuthenticationService;
import com.ticketing.system.architecture.fixture.notifications.NotificationsTypeUsingOrganization;
import com.ticketing.system.architecture.fixture.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.architecture.fixture.sales.domain.SalesDomainTypeUsingCatalogDomain;
import com.ticketing.system.architecture.fixture.ui.UiTypeUsingADrivenPort;
import com.ticketing.system.architecture.fixture.ui.UiTypeUsingAnAdapter;
import com.ticketing.system.architecture.fixture.ui.UiTypeUsingAnApplicationService;
import com.ticketing.system.architecture.fixture.undocumented.UndocumentedModuleType;
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

    // Production classes only (tests excluded), imported once and reused by every rule below.
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(PRODUCTION_ROOT);

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
     * session. {@code reporting} is gated too: the UI reads it, so it must not read the UI back.
     */
    @Test
    void no_backend_context_depends_on_the_ui() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.ticketing.system.identity..", "com.ticketing.system.organization..",
                        "com.ticketing.system.catalog..", "com.ticketing.system.sales..",
                        "com.ticketing.system.messaging..", "com.ticketing.system.notifications..",
                        "com.ticketing.system.governance..", "com.ticketing.system.reporting..",
                        "com.ticketing.system.shared..")
                .should().dependOnClassesThat().resideInAPackage("com.ticketing.system.ui..")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * {@code .claude/CLAUDE.md} (Architecture): each module depends only along the documented graph,
     * {@code shared ← identity ← organization ← catalog ← sales} and {@code organization ← messaging},
     * with {@code notifications} on {@code shared} alone, {@code governance} and {@code reporting}
     * reading the backend contexts, {@code ui} above them and {@code bootstrap} above everything. An
     * edge that closes no cycle would pass {@link #bounded_contexts_are_acyclic()}, so only this rule
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
    void GivenProductionCode_WhenScanned_ThenTheUiUsesOnlyTheBackendsApplicationApi() {
        uiUsesOnlyTheBackendsApplicationApi(PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    // The class comment records a rule engine that silently checked nothing; these prove each graph
    // rule fails on an offender and that its one exception stays as narrow as documented.
    @Test
    void GivenAUiClassUsingADrivenPort_WhenScanned_ThenTheUiApplicationApiRuleFails() {
        assertRuleFails(uiUsesOnlyTheBackendsApplicationApi(FIXTURE_ROOT), UiTypeUsingADrivenPort.class);
    }

    @Test
    void GivenAUiClassUsingAnAdapter_WhenScanned_ThenTheUiApplicationApiRuleFails() {
        assertRuleFails(uiUsesOnlyTheBackendsApplicationApi(FIXTURE_ROOT), UiTypeUsingAnAdapter.class);
    }

    @Test
    void GivenAUiClassUsingAnApplicationService_WhenScanned_ThenTheUiApplicationApiRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(UiTypeUsingAnApplicationService.class);
        assertThatCode(() -> uiUsesOnlyTheBackendsApplicationApi(FIXTURE_ROOT).check(imported))
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
    void GivenAnAttributeConverterUsingJackson_WhenScanned_ThenTheJacksonRulePasses() {
        assertExemptFromTheJacksonRule(ConverterUsingJackson.class);
    }

    @Test
    void GivenAnAnonymousTypeReferenceInsideAConverter_WhenScanned_ThenTheJacksonRulePasses() {
        assertExemptFromTheJacksonRule(ConverterWithTypeReference.class, ConverterWithTypeReference.MAP_TYPE.getClass());
    }

    // Exempt classes are filtered out of the rule's subjects, which ArchUnit would otherwise reject as
    // a rule that checked nothing; production always leaves the aggregates as subjects.
    private static void assertExemptFromTheJacksonRule(Class<?>... classes) {
        JavaClasses imported = new ClassFileImporter().importClasses(classes);
        assertThatCode(() -> DOMAIN_USES_JACKSON_ONLY_IN_ATTRIBUTE_CONVERTERS.allowEmptyShould(true).check(imported))
                .doesNotThrowAnyException();
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
            Map.entry("governance", Set.of("shared",
                    "identity", "organization", "catalog", "sales", "messaging", "notifications")),
            Map.entry("reporting", Set.of("shared",
                    "identity", "organization", "catalog", "sales", "messaging", "notifications")),
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

    private static ArchRule uiUsesOnlyTheBackendsApplicationApi(String root) {
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
     * JVM. Production code must not call or reference (e.g. {@code Instant::now}) anything that reads
     * the system clock or the default zone: {@code now()}/{@code now(ZoneId)} on a {@code java.time}
     * type, a {@code Clock.tick*(ZoneId)} or {@code InstantSource.system()} clock, {@code new Date()},
     * {@code Calendar.getInstance}, or the default-zone lookups. {@code System.currentTimeMillis()} is
     * left out on purpose: its uses are zone-free browser countdowns and a cache TTL.
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

    @Test
    void GivenAReferenceToClockSystemUtcOutsideTheClockBean_WhenScanned_ThenTheBeanRuleFails() {
        assertRuleFails(ONLY_THE_CLOCK_BEAN_CREATES_A_SYSTEM_CLOCK, ReferencesClockSystemUtc.class);
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
                        || (owner.equals(Calendar.class.getName()) && name.equals("getInstance"));
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

    static class ReferencesClockSystemUtc {
        Supplier<Clock> read() {
            return Clock::systemUTC;
        }
    }
}
