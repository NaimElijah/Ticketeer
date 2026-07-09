package com.ticketing.system.bootstrap.dev.seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates the outcome of every seeding step so {@code ScenarioRunner}
 * can print one classified summary at the end of a run. That summary is the
 * whole point of the smoke-seed: it tells you, in a single place, which
 * real-service paths passed, which were skipped because the feature is a
 * known not-yet-built stub, and which FAILED — i.e. surfaced a real bug.
 *
 * <p>Not a Spring bean — created fresh per seed pass by the orchestrator so
 * a wipe-and-reseed starts with an empty report.
 */
public final class SeedReport {

    public enum Status { PASS, SKIPPED, FAIL, BLOCKED }

    public record Entry(String stage, String name, Status status, String detail) {}

    private final List<Entry> entries = new ArrayList<>();

    public void record(String stage, String name, Status status, String detail) {
        entries.add(new Entry(stage, name, status, detail));
    }

    public long count(Status status) {
        return entries.stream().filter(e -> e.status() == status).count();
    }

    public boolean hasFailures() {
        return count(Status.FAIL) > 0;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Render a human-readable summary block for the boot log. */
    public String render() {
        // per-stage tallies, indexed by Status.ordinal(): [PASS, SKIPPED, FAIL, BLOCKED]
        Map<String, int[]> byStage = new LinkedHashMap<>();
        for (Entry e : entries) {
            byStage.computeIfAbsent(e.stage(), k -> new int[4])[e.status().ordinal()]++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== SEED SMOKE REPORT ==========\n");
        sb.append(String.format("%-15s %5s %5s %5s %8s%n", "stage", "PASS", "SKIP", "FAIL", "BLOCKED"));
        for (var en : byStage.entrySet()) {
            int[] t = en.getValue();
            sb.append(String.format("%-15s %5d %5d %5d %8d%n", en.getKey(), t[0], t[1], t[2], t[3]));
        }
        sb.append("---------------------------------------------\n");
        sb.append(String.format("%-15s %5d %5d %5d %8d%n", "TOTAL",
            count(Status.PASS), count(Status.SKIPPED), count(Status.FAIL), count(Status.BLOCKED)));

        if (hasFailures()) {
            sb.append("\nFAILURES (real bugs to triage):\n");
            int i = 1;
            for (Entry e : entries) {
                if (e.status() == Status.FAIL) {
                    sb.append(String.format("  %d. [%s] %s — %s%n", i++, e.stage(), e.name(), e.detail()));
                }
            }
        }
        if (count(Status.BLOCKED) > 0) {
            sb.append("\nBLOCKED (prerequisite failed — not attempted):\n");
            for (Entry e : entries) {
                if (e.status() == Status.BLOCKED) {
                    sb.append(String.format("  - [%s] %s — %s%n", e.stage(), e.name(), e.detail()));
                }
            }
        }
        if (count(Status.SKIPPED) > 0) {
            sb.append("\nSKIPPED (known stubs / deferred features):\n");
            for (Entry e : entries) {
                if (e.status() == Status.SKIPPED) {
                    sb.append(String.format("  - [%s] %s — %s%n", e.stage(), e.name(), e.detail()));
                }
            }
        }
        sb.append("=============================================");
        return sb.toString();
    }
}
