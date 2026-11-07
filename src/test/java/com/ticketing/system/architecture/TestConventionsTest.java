package com.ticketing.system.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.annotation.Testable;

import com.ticketing.system.support.RunsOnPostgres;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Holds the test suite to its own conventions. The repo has no linter, and about 4% of the tests added
 * since July 2026 still broke the naming convention (ADR-023, #189). The self-tests prove the
 * rule fails on each kind of offender, because a rule that matches nothing checks nothing.
 */
class TestConventionsTest {

    private static final String ROOT = "com.ticketing.system";
    private static final String ABSTRACT_CONTRACT_TEST =
            ROOT + ".unit.infrastructure.persistence.UserPersistence.IUserRepositoryContractTest";
    private static final String SECTION = "[A-Z0-9][A-Za-z0-9]*";

    // @Test carries @Testable directly, and @ParameterizedTest and @RepeatedTest carry it through
    // @TestTemplate. Filtering on @Test alone would miss the parameterized tests.
    private static final ArchRule TEST_NAMES_READ_GIVEN_WHEN_THEN = methods()
            .that().areMetaAnnotatedWith(Testable.class)
            .should().haveNameMatching("Given" + SECTION + "_When" + SECTION + "_Then" + SECTION)
            .because("a test name states its scenario, its action and its outcome (ADR-023)");

    // The pom sets no failsafe includes, so failsafe runs its defaults: **/IT*.java, **/*IT.java and
    // **/*ITCase.java.
    private static final DescribedPredicate<JavaClass> NAMED_AS_FAILSAFE_RUNS = simpleNameStartingWith("IT")
            .or(simpleNameEndingWith("IT"))
            .or(simpleNameEndingWith("ITCase"))
            .as("are named IT*, *IT or *ITCase");

    // Failsafe runs those classes, and each needs PostgreSQL. Without the check, one whose container no
    // longer reaches its context boots on application-test.yml's H2 and passes (#189). JUnit runs no
    // abstract class, and abstract contract tests such as ITicketRepositoryContractTest start with IT.
    private static final ArchRule INTEGRATION_TESTS_CHECK_THEIR_DATABASE = classes()
            .that(NAMED_AS_FAILSAFE_RUNS)
            .and().doNotHaveModifier(JavaModifier.ABSTRACT)
            .and().areNotInterfaces()
            .should().beAnnotatedWith(RunsOnPostgres.class)
            .because("an IT that boots on H2 must fail, not pass under a PostgreSQL name (#189)");

    @Test
    void GivenTheTestSuite_WhenScanned_ThenEveryTestNameReadsGivenWhenThen() {
        TEST_NAMES_READ_GIVEN_WHEN_THEN.check(testClasses());
    }

    @Test
    void GivenTheTestSuite_WhenScanned_ThenEveryIntegrationTestChecksItRunsOnPostgres() {
        INTEGRATION_TESTS_CHECK_THEIR_DATABASE.check(testClasses());
    }

    @Test
    void GivenAnIntegrationTestWithoutThePostgresCheck_WhenScanned_ThenTheDatabaseRuleFails() {
        assertDatabaseRuleFailsOn(UncheckedIT.class);
    }

    @Test
    void GivenAnITPrefixedIntegrationTestWithoutThePostgresCheck_WhenScanned_ThenTheDatabaseRuleFails() {
        assertDatabaseRuleFailsOn(ITUnchecked.class);
    }

    @Test
    void GivenAnITCaseIntegrationTestWithoutThePostgresCheck_WhenScanned_ThenTheDatabaseRuleFails() {
        assertDatabaseRuleFailsOn(UncheckedITCase.class);
    }

    @Test
    void GivenAnIntegrationTestWithThePostgresCheck_WhenScanned_ThenTheDatabaseRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(CheckedIT.class);

        assertThatCode(() -> INTEGRATION_TESTS_CHECK_THEIR_DATABASE.check(imported)).doesNotThrowAnyException();
    }

    // CheckedIT is imported too: a rule that matches no class fails for that reason alone.
    @Test
    void GivenAnAbstractClassAndAnInterfaceNamedLikeITsWithoutThePostgresCheck_WhenScanned_ThenTheDatabaseRulePasses() {
        JavaClasses imported = new ClassFileImporter()
                .importClasses(ITAbstractContract.class, ITContract.class, CheckedIT.class);

        assertThatCode(() -> INTEGRATION_TESTS_CHECK_THEIR_DATABASE.check(imported)).doesNotThrowAnyException();
    }

    @Test
    void GivenTheImportedTestClasses_WhenInspected_ThenTheyHoldAbstractContractTestsButNotTheOffendersBelow() {
        JavaClasses imported = testClasses();

        assertThat(imported.contain(ABSTRACT_CONTRACT_TEST)).isTrue();
        assertThat(imported.contain(LowercaseGivenCase.class)).isFalse();
    }

    @Test
    void GivenALowercaseTestName_WhenScanned_ThenTheNamingRuleFails() {
        assertNamingRuleFailsOn(LowercaseGivenCase.class, "givenAState_whenActed_thenAnOutcome");
    }

    @Test
    void GivenALowercaseLetterAfterAKeyword_WhenScanned_ThenTheNamingRuleFails() {
        assertNamingRuleFailsOn(LowercaseAfterAKeywordCase.class, "Givenastate_WhenActed_ThenAnOutcome");
    }

    @Test
    void GivenATwoPartParameterizedTestName_WhenScanned_ThenTheNamingRuleFails() {
        assertNamingRuleFailsOn(TwoPartParameterizedCase.class, "findById_returnsNull");
    }

    @Test
    void GivenATestNameWithoutAWhen_WhenScanned_ThenTheNamingRuleFails() {
        assertNamingRuleFailsOn(MissingWhenCase.class, "GivenAState_ThenAnOutcome");
    }

    @Test
    void GivenATestNameWithoutAThen_WhenScanned_ThenTheNamingRuleFails() {
        assertNamingRuleFailsOn(MissingThenCase.class, "GivenAState_WhenActed");
    }

    @Test
    void GivenATestNameWithAnExtraSection_WhenScanned_ThenTheNamingRuleFails() {
        assertNamingRuleFailsOn(ExtraSectionCase.class, "GivenAState_WhenActed_ThenAnOutcome_AndMore");
    }

    @Test
    void GivenACompliantTestBesideABadlyNamedHelper_WhenScanned_ThenTheNamingRulePasses() {
        JavaClasses imported = new ClassFileImporter().importClasses(CompliantCaseWithAHelper.class);

        assertThatCode(() -> TEST_NAMES_READ_GIVEN_WHEN_THEN.check(imported)).doesNotThrowAnyException();
    }

    private static void assertDatabaseRuleFailsOn(Class<?> offender) {
        JavaClasses imported = new ClassFileImporter().importClasses(offender);

        assertThatThrownBy(() -> INTEGRATION_TESTS_CHECK_THEIR_DATABASE.check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offender.getName());
    }

    private static void assertNamingRuleFailsOn(Class<?> offender, String offendingName) {
        JavaClasses imported = new ClassFileImporter().importClasses(offender);

        // The name in the message tells this failure from the one a rule gives when it matches nothing.
        assertThatThrownBy(() -> TEST_NAMES_READ_GIVEN_WHEN_THEN.check(imported))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offendingName);
    }

    // Imported per call, not held in a static, so the classes aren't kept for the rest of the shared test
    // JVM. Only the offenders below are left out: abstract contract tests hold many of the names.
    private static JavaClasses testClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("/architecture/TestConventionsTest$"))
                .importPackages(ROOT);
    }

    // The offenders are abstract, and their names don't end in Test: JUnit skips an abstract class, and
    // surefire skips a nested one, so neither ever runs them.

    abstract static class LowercaseGivenCase {
        @Test
        void givenAState_whenActed_thenAnOutcome() {
        }
    }

    abstract static class LowercaseAfterAKeywordCase {
        @Test
        void Givenastate_WhenActed_ThenAnOutcome() {
        }
    }

    abstract static class TwoPartParameterizedCase {
        @ParameterizedTest
        @ValueSource(ints = 1)
        void findById_returnsNull(int ignored) {
        }
    }

    abstract static class MissingWhenCase {
        @Test
        void GivenAState_ThenAnOutcome() {
        }
    }

    abstract static class MissingThenCase {
        @Test
        void GivenAState_WhenActed() {
        }
    }

    abstract static class ExtraSectionCase {
        @Test
        void GivenAState_WhenActed_ThenAnOutcome_AndMore() {
        }
    }

    abstract static class CompliantCaseWithAHelper {
        @Test
        void GivenAState_WhenActed_ThenAnOutcome() {
        }

        void aHelperThatIsNoTest() {
        }
    }

    // Failsafe, like surefire, skips a nested class, so no IT below ever runs, and none holds a test. The
    // offenders are concrete because the rule leaves abstract classes out.

    static class UncheckedIT {
    }

    static class ITUnchecked {
    }

    static class UncheckedITCase {
    }

    @RunsOnPostgres
    static class CheckedIT {
    }

    abstract static class ITAbstractContract {
    }

    interface ITContract {
    }
}
