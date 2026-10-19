package com.ticketing.system.architecture;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.mock.env.MockEnvironment;

import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * Which repository adapter each profile set selects (ADR-005, SLR.7).
 *
 * <p>The scanner applies each adapter's real {@code @Profile} condition, exactly as the application
 * context does, so a new adapter with the wrong profile fails here rather than in a deployment.
 * {@link MockEnvironment} carries no system properties or environment variables, so an ambient
 * {@code SPRING_PROFILES_ACTIVE} cannot change the outcome.
 */
class RepositoryAdapterProfileTest {

    private static final String BASE_PACKAGE = "com.ticketing.system";
    private static final Pattern ADAPTER =
            Pattern.compile("\\.adapter\\.out\\.persistence\\.(Memory|Jpa)(\\w+Repository)$");
    private static final int REPOSITORY_PORTS = 11;
    private static final String JPA = "Jpa";
    private static final String MEMORY = "Memory";

    static Stream<Arguments> profileSetsOutsideTheInMemorySuite() {
        return Stream.of(
                Arguments.of(List.of()),
                Arguments.of(List.of("dev")),
                Arguments.of(List.of("supabase")),
                Arguments.of(List.of("jpa")),
                Arguments.of(List.of(PersistenceProfiles.MEMORY)),
                Arguments.of(List.of("test")),
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
        Map<String, List<String>> active = activeAdapterKindsByPort(List.of("test", PersistenceProfiles.MEMORY));

        assertThat(active).hasSize(REPOSITORY_PORTS);
        assertThat(active).allSatisfy((port, kinds) -> assertThat(kinds).as(port).containsExactly(MEMORY));
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
