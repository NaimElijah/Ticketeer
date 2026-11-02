package com.ticketing.system.bootstrap.dev.seed;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Wraps every seeding action so the demo seed doubles as an end-to-end smoke
 * test. Each call is classified into the shared {@link SeedReport}:
 * <ul>
 *   <li><b>PASS</b> — the real service call succeeded.</li>
 *   <li><b>SKIPPED</b> — the call threw {@link UnsupportedOperationException},
 *       i.e. the feature is a known, not-yet-built stub.</li>
 *   <li><b>FAIL</b> — any other exception: a real bug worth surfacing.</li>
 *   <li><b>BLOCKED</b> — a dependent step we couldn't even attempt because a
 *       prerequisite failed (recorded explicitly to avoid cascade noise).</li>
 * </ul>
 *
 * <p>When {@code failFast} is true, the first FAIL (or a failed negative
 * assertion) is rethrown as a {@link SeedAbortException} so the whole seed
 * stops with a full stack trace — for debugging one bug cleanly. When false
 * ({@code dev}'s setting), every stage runs and failures are collected into the
 * end-of-run summary.
 */
@Slf4j
public final class SeedHarness {

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }

    @FunctionalInterface
    public interface ThrowingSupplier<T> { T get() throws Exception; }

    /** Thrown to abort the seed in fail-fast mode. */
    public static final class SeedAbortException extends RuntimeException {
        public SeedAbortException(String message, Throwable cause) { super(message, cause); }
    }

    private final SeedReport report;
    private final boolean failFast;

    public SeedHarness(SeedReport report, boolean failFast) {
        this.report = report;
        this.failFast = failFast;
    }

    public SeedReport report() {
        return report;
    }

    /** Run an action expected to succeed. */
    public void step(String stage, String name, ThrowingRunnable action) {
        stepReturning(stage, name, () -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    /** Run an action whose value later steps depend on. Empty on SKIP or FAIL. */
    public <T> Optional<T> stepReturning(String stage, String name, ThrowingSupplier<T> action) {
        try {
            T value = action.get();
            report.record(stage, name, SeedReport.Status.PASS, "ok");
            return Optional.ofNullable(value);
        } catch (UnsupportedOperationException notImplemented) {
            report.record(stage, name, SeedReport.Status.SKIPPED, msg(notImplemented));
            log.warn("seed: SKIPPED [{}] {} — feature not implemented ({})", stage, name, msg(notImplemented));
            return Optional.empty();
        } catch (Throwable t) {
            if (t instanceof Error err) {
                throw err;
            }
            report.record(stage, name, SeedReport.Status.FAIL, t.getClass().getSimpleName() + ": " + msg(t));
            log.error("seed: FAIL [{}] {} — {}", stage, name, msg(t), t);
            if (failFast) {
                throw new SeedAbortException("seed aborted at [" + stage + "] " + name, t);
            }
            return Optional.empty();
        }
    }

    /**
     * Negative assertion: PASS only if the action throws an exception whose simple class name is exactly
     * {@code expectedSimpleName}. The type must match exactly, so a different type fails: a subclass, a
     * superclass or a cause does not count. FAIL if nothing throws ("guard not enforced") or another type
     * is thrown; that failure names both types.
     *
     * @param expectedSimpleName the expected exception's {@link Class#getSimpleName()}, e.g.
     *                           {@code IllegalStateException}
     */
    public void expectFailure(String stage, String name, String expectedSimpleName, ThrowingRunnable action) {
        try {
            action.run();
            String detail = "guard NOT enforced — expected " + expectedSimpleName + " but nothing was thrown";
            report.record(stage, name, SeedReport.Status.FAIL, detail);
            log.error("seed: FAIL [{}] {} — {}", stage, name, detail);
            if (failFast) {
                throw new SeedAbortException("seed aborted at [" + stage + "] " + name, null);
            }
        } catch (SeedAbortException abort) {
            throw abort;
        } catch (Throwable t) {
            if (t instanceof Error err) {
                throw err;
            }
            if (t.getClass().getSimpleName().equals(expectedSimpleName)) {
                report.record(stage, name, SeedReport.Status.PASS, "correctly rejected with " + expectedSimpleName);
            } else {
                String detail = "expected " + expectedSimpleName + " but got "
                    + t.getClass().getSimpleName() + ": " + msg(t);
                report.record(stage, name, SeedReport.Status.FAIL, detail);
                log.error("seed: FAIL [{}] {} — {}", stage, name, detail);
                if (failFast) {
                    throw new SeedAbortException("seed aborted at [" + stage + "] " + name, t);
                }
            }
        }
    }

    /** Record a step we could not attempt because a prerequisite failed. */
    public void blocked(String stage, String name, String reason) {
        report.record(stage, name, SeedReport.Status.BLOCKED, reason);
        log.warn("seed: BLOCKED [{}] {} — {}", stage, name, reason);
    }

    private static String msg(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
