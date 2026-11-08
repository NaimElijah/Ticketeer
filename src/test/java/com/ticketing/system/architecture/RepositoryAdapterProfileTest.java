package com.ticketing.system.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;

import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * Which repository adapter each profile set selects (ADR-005, SLR.7).
 *
 * <p>The scanner applies each adapter's real {@code @Profile} condition, exactly as the application
 * context does, so a new adapter with the wrong profile fails here rather than in a deployment.
 * {@link MockEnvironment} carries no system properties or environment variables, so an ambient
 * {@code SPRING_PROFILES_ACTIVE} cannot change the outcome.
 *
 * <p>It also skips Boot's config-data processing, which expands {@code spring.profiles.group},
 * {@code include} and {@code active} from the config files. So a test reads every shipped config file
 * and fails if one of them could switch a run onto the Memory adapters. No shipped file sets any of those
 * keys today, so that test proves nothing alone: a fixture file that does shows the reader finds them.
 */
class RepositoryAdapterProfileTest {

    private static final String BASE_PACKAGE = "com.ticketing.system";
    private static final Pattern ADAPTER =
            Pattern.compile("\\.adapter\\.out\\.persistence\\.(Memory|Jpa)(\\w+Repository)$");
    private static final int REPOSITORY_PORTS = 11;
    private static final String JPA = "Jpa";
    private static final String MEMORY = "Memory";

    private static final String TEST_PROFILE = "test";
    private static final List<String> CONFIG_FILE_PATTERNS = List.of(
            "classpath*:application*.yml", "classpath*:application*.yaml", "classpath*:application*.properties");
    private static final Pattern PROFILE_ACTIVATION_KEY =
            Pattern.compile("^spring\\.profiles\\.(group\\.[^\\[]+|include|active)(\\[\\d+])?$");
    // ${NAME:default}: the profiles a file activates when the variable is unset, as the file ships.
    private static final Pattern PLACEHOLDER_WITH_DEFAULT = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");
    private static final String ACTIVATING_FIXTURE = "architecture/activates-the-suite-profiles.yml";

    static Stream<Arguments> profileSetsOutsideTheInMemorySuite() {
        return Stream.of(
                Arguments.of(List.of()),
                Arguments.of(List.of("dev")),
                Arguments.of(List.of("supabase")),
                Arguments.of(List.of("jpa")),
                Arguments.of(List.of(PersistenceProfiles.MEMORY)),
                Arguments.of(List.of(TEST_PROFILE)),
                Arguments.of(List.of("dev", PersistenceProfiles.MEMORY)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileSetsOutsideTheInMemorySuite")
    void GivenProfilesOtherThanTestWithMemory_WhenScanningRepositoryAdapters_ThenOnlyTheJpaAdaptersAreActive(
            List<String> profiles) {
        Map<String, List<String>> active = activeAdapterKindsByPort(profiles);

        assertThat(active).hasSize(REPOSITORY_PORTS);
        assertThat(active).allSatisfy((port, kinds) -> assertThat(kinds).as(port).containsExactly(JPA));
    }

    @Test
    void GivenTestAndMemoryProfiles_WhenScanningRepositoryAdapters_ThenOnlyTheMemoryAdaptersAreActive() {
        Map<String, List<String>> active = activeAdapterKindsByPort(List.of(TEST_PROFILE, PersistenceProfiles.MEMORY));

        assertThat(active).hasSize(REPOSITORY_PORTS);
        assertThat(active).allSatisfy((port, kinds) -> assertThat(kinds).as(port).containsExactly(MEMORY));
    }

    @Test
    void GivenShippedConfigFiles_WhenReadingProfileActivation_ThenNoneActivatesTestOrMemory() throws IOException {
        assertThat(suiteProfileActivations(shippedConfigFiles()))
                .as("no config file may activate the suite profiles").isEmpty();
    }

    @Test
    void GivenAConfigFileActivatingTheSuiteProfiles_WhenReadingProfileActivation_ThenEachActivationIsReported()
            throws IOException {
        Resource fixture = new ClassPathResource(ACTIVATING_FIXTURE);
        String file = fixture.getFilename();

        assertThat(suiteProfileActivations(List.of(fixture))).containsExactlyInAnyOrder(
                file + " spring.profiles.active=" + TEST_PROFILE,
                file + " spring.profiles.include[1]=" + PersistenceProfiles.MEMORY,
                file + " spring.profiles.group.local=" + TEST_PROFILE);
    }

    private static List<Resource> shippedConfigFiles() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> files = new ArrayList<>();
        for (String pattern : CONFIG_FILE_PATTERNS) {
            files.addAll(List.of(resolver.getResources(pattern)));
        }
        assertThat(files).as("the shipped application.yml must be on the classpath").isNotEmpty();
        return files;
    }

    /** The activations, as {@link #profileActivations} lists them, that name the test or memory profile. */
    private static List<String> suiteProfileActivations(List<Resource> files) throws IOException {
        return profileActivations(files).stream()
                .filter(activation -> activation.endsWith("=" + TEST_PROFILE)
                        || activation.endsWith("=" + PersistenceProfiles.MEMORY))
                .toList();
    }

    /**
     * Every profile named by a group, include or active key, as {@code file key=profile}. A placeholder
     * with a default counts as its default.
     */
    private static List<String> profileActivations(List<Resource> files) throws IOException {
        List<String> activations = new ArrayList<>();
        for (Resource file : files) {
            PropertySourceLoader loader = file.getFilename().endsWith(".properties")
                    ? new PropertiesPropertySourceLoader()
                    : new YamlPropertySourceLoader();
            for (PropertySource<?> document : loader.load(file.getFilename(), file)) {
                EnumerablePropertySource<?> properties = (EnumerablePropertySource<?>) document;
                for (String key : properties.getPropertyNames()) {
                    if (PROFILE_ACTIVATION_KEY.matcher(key).matches()) {
                        String value = withoutPlaceholder(String.valueOf(properties.getProperty(key)));
                        for (String profile : value.split(",")) {
                            activations.add(file.getFilename() + " " + key + "=" + profile.trim());
                        }
                    }
                }
            }
        }
        return activations;
    }

    private static String withoutPlaceholder(String value) {
        Matcher placeholder = PLACEHOLDER_WITH_DEFAULT.matcher(value.trim());
        return placeholder.matches() ? placeholder.group(1) : value;
    }

    /** Maps each repository port (e.g. {@code UserRepository}) to the adapter kinds active for it. */
    private static Map<String, List<String>> activeAdapterKindsByPort(List<String> profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles.toArray(String[]::new));
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true, environment);

        Map<String, List<String>> kindsByPort = new TreeMap<>();
        scanner.findCandidateComponents(BASE_PACKAGE).forEach(candidate -> {
            Matcher adapter = ADAPTER.matcher(candidate.getBeanClassName());
            if (adapter.find()) {
                kindsByPort.computeIfAbsent(adapter.group(2), port -> new ArrayList<>())
                        .add(adapter.group(1));
            }
        });
        return kindsByPort;
    }
}
