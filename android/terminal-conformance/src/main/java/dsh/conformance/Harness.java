package dsh.conformance;

import com.termux.terminal.TermuxOracle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Command-line driver for the terminal conformance harness.
 *
 * <pre>
 *   Harness list     &lt;corpusDir&gt;          list corpus cases
 *   Harness dump     &lt;corpusDir&gt; &lt;case&gt;   print one case's final screen
 *   Harness coverage &lt;corpusDir&gt;          report which capabilities the corpus exercises
 *   Harness selftest &lt;corpusDir&gt;          prove the harness can fail
 *   Harness check    &lt;corpusDir&gt;          compare every implementation, report agreement
 * </pre>
 *
 * <p>With one implementation registered, {@code check} verifies determinism, which is
 * the harness's own self-test. Once the in-house emulator registers, the same command
 * becomes the real conformance gate — no other change is needed.</p>
 */
public final class Harness {

    /** Screen geometry used for every case; a change here changes the corpus's meaning. */
    private static final int ROWS = 24;
    private static final int COLUMNS = 80;
    /** Scrollback beyond the visible screen, matching a default Termux session. */
    private static final Integer TRANSCRIPT_ROWS = 200;

    /**
     * Mid-stream window size for the {@code *resize*} corpus cases (audit L12).
     *
     * <p>Rows and columns both change so that <em>reflow</em> is exercised, not just a
     * plain clear: {@code TerminalUnderTest.resize} used to have zero callers, which
     * meant the one regression a phone hits most (rotation / soft keyboard changing
     * the pty size) was not covered by the gate at all.</p>
     */
    private static final int[] RESIZE_TO = {ROWS + 2, COLUMNS + 10};

    private Harness() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Harness {list|dump|coverage|selftest|check} <corpusDir> [case]");
            System.exit(2);
        }
        String command = args[0];
        Path corpus = Paths.get(args[1]);
        switch (command) {
            case "list":
                for (Path file : cases(corpus)) {
                    System.out.printf("  %-34s %9d bytes%n", file.getFileName(), Files.size(file));
                }
                break;
            case "dump":
                dump(corpus, args[2]);
                break;
            case "coverage":
                System.exit(coverage(corpus));
                break;
            case "selftest":
                System.exit(selftest(corpus));
                break;
            case "check":
                System.exit(check(corpus));
                break;
            default:
                System.err.println("unknown command: " + command);
                System.exit(2);
        }
    }

    private static List<Path> cases(Path corpus) throws IOException {
        if (!Files.isDirectory(corpus)) {
            throw new IOException("not a corpus directory: " + corpus);
        }
        try (Stream<Path> stream = Files.list(corpus)) {
            List<Path> out = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .sorted()
                    .forEach(out::add);
            return out;
        }
    }

    /**
     * The implementations compared by {@code check}.
     *
     * <p>One entry today. The in-house emulator is added here, and nothing else in
     * the harness changes.</p>
     */
    private static List<TerminalUnderTest> implementations() {
        List<TerminalUnderTest> out = new ArrayList<>();
        out.add(new TermuxOracle(ROWS, COLUMNS, TRANSCRIPT_ROWS));
        return out;
    }

    private static Trace run(TerminalUnderTest terminal, Path file) throws IOException {
        String name = file.getFileName().toString();
        // 用例名里带 resize 的走「中途改窗口尺寸」那条路径（审计 L12）。
        int[] resizeTo = name.contains("resize") ? RESIZE_TO : null;
        return Trace.of(terminal, Files.readAllBytes(file), resizeTo);
    }

    private static void dump(Path corpus, String name) throws IOException {
        Path file = corpus.resolve(name.endsWith(".bin") ? name : name + ".bin");
        TerminalUnderTest terminal = implementations().get(0);
        Trace trace = run(terminal, file);
        System.out.println("=== " + terminal.name() + " :: " + file.getFileName()
                + " (" + Files.size(file) + " bytes, " + trace.steps.size()
                + " slices of " + trace.sliceSize + ") ===");
        System.out.println(trace.finalScreen.render());
        System.out.println();
        System.out.println(trace.finalScreen.dump());
    }

    private static int check(Path corpus) throws IOException {
        List<Path> files = cases(corpus);
        if (files.isEmpty()) {
            System.err.println("no .bin cases in " + corpus);
            return 1;
        }
        List<TerminalUnderTest> impls = implementations();
        int agreed = 0;
        int disagreed = 0;
        int unstable = 0;

        System.out.printf("geometry %dx%d, transcript %s, %d implementation(s), %d case(s)%n",
                ROWS, COLUMNS, TRANSCRIPT_ROWS, impls.size(), files.size());
        System.out.println();

        for (Path file : files) {
            String name = file.getFileName().toString();

            Trace baseline = run(impls.get(0), file);
            Trace repeat = run(impls.get(0), file);
            if (!baseline.sameAs(repeat)) {
                unstable++;
                System.out.printf("  UNSTABLE  %-34s %s did not reproduce its own trace at slice %d%n",
                        name, impls.get(0).name(), baseline.firstDifference(repeat));
                continue;
            }

            boolean allAgree = true;
            for (int i = 1; i < impls.size(); i++) {
                Trace other = run(impls.get(i), file);
                int at = baseline.firstDifference(other);
                if (at >= 0) {
                    allAgree = false;
                    System.out.printf("  DIFFER    %-34s %s vs %s at slice %d of %d%n", name,
                            impls.get(0).name(), impls.get(i).name(), at, baseline.steps.size());
                    System.out.print(indent(baseline.finalScreen.diff(other.finalScreen)));
                }
            }
            if (allAgree) {
                agreed++;
                System.out.printf("  ok        %-34s %7d bytes, %4d slices%n",
                        name, Files.size(file), baseline.steps.size());
            } else {
                disagreed++;
            }
        }

        System.out.println();
        int total = agreed + disagreed + unstable;
        System.out.printf("agree %d/%d", agreed, total);
        if (disagreed > 0) {
            System.out.printf(", differ %d", disagreed);
        }
        if (unstable > 0) {
            System.out.printf(", unstable %d", unstable);
        }
        System.out.println(disagreed == 0 && unstable == 0 ? "  \u2713" : "  \u2717");
        return disagreed == 0 && unstable == 0 ? 0 : 1;
    }

    /**
     * Report which capabilities the corpus exercises, and fail on any that none do.
     *
     * <p>This is the number that makes the rest of the plan meaningful: "52 cases
     * agree" says nothing if the cases only exercise plain text.</p>
     */
    private static int coverage(Path corpus) throws IOException {
        List<Path> files = cases(corpus);
        TerminalUnderTest terminal = implementations().get(0);
        Map<String, Integer> asInput = new TreeMap<>();
        Map<String, Integer> asScreen = new TreeMap<>();
        Set<String> distinctScreens = new HashSet<>();
        for (Path file : files) {
            byte[] input = Files.readAllBytes(file);
            Screen screen = run(terminal, file).finalScreen;
            distinctScreens.add(screen.dump());
            Set<String> tags = Coverage.of(input, screen);
            for (String tag : tags) {
                String capability = tag.substring(tag.indexOf(':') + 1);
                if (tag.startsWith("screen:")) {
                    asScreen.merge(capability, 1, Integer::sum);
                } else {
                    asInput.merge(capability, 1, Integer::sum);
                }
            }
        }
        System.out.printf("%d case(s); %d produce a distinct final screen%n%n",
                files.size(), distinctScreens.size());
        System.out.printf("  %-20s %10s %10s%n", "capability", "in input", "on screen");
        List<String> uncovered = new ArrayList<>();
        for (String capability : Coverage.REQUIRED) {
            int input = asInput.getOrDefault(capability, 0);
            int screen = asScreen.getOrDefault(capability, 0);
            System.out.printf("  %-20s %10d %10d%s%n", capability, input, screen,
                    input == 0 ? "   <-- UNCOVERED" : "");
            if (input == 0) {
                uncovered.add(capability);
            }
        }
        System.out.println();
        if (uncovered.isEmpty()) {
            System.out.printf("all %d required capabilities are exercised  \u2713%n", Coverage.REQUIRED.size());
            return 0;
        }
        System.out.printf("%d capability/capabilities have no corpus coverage: %s  \u2717%n",
                uncovered.size(), String.join(", ", uncovered));
        return 1;
    }

    /**
     * Prove the harness can fail.
     *
     * <p>Three independent properties, because each has a different failure mode:
     * the reference must be deterministic (else nothing can be compared), the corpus
     * must actually distinguish cases (else a blanket "agree" is meaningless), and a
     * known-wrong implementation must be reported as wrong (else the comparison is
     * not wired up).</p>
     */
    private static int selftest(Path corpus) throws IOException {
        List<Path> files = cases(corpus);
        int failures = 0;

        System.out.println("[1] reference is deterministic under sliced feeding");
        TerminalUnderTest reference = implementations().get(0);
        int unstable = 0;
        for (Path file : files) {
            if (!run(reference, file).sameAs(run(reference, file))) {
                System.out.println("      unstable: " + file.getFileName());
                unstable++;
            }
        }
        if (unstable == 0) {
            System.out.printf("      ok: %d/%d cases reproduce identical traces%n", files.size(), files.size());
        } else {
            failures++;
        }

        System.out.println("[2] corpus cases are distinguishable");
        Set<String> distinct = new HashSet<>();
        for (Path file : files) {
            distinct.add(String.join("", run(reference, file).steps));
        }
        int required = Math.max(1, files.size() * 3 / 4);
        if (distinct.size() >= required) {
            System.out.printf("      ok: %d distinct traces from %d cases (>= %d)%n",
                    distinct.size(), files.size(), required);
        } else {
            System.out.printf("      FAIL: only %d distinct traces from %d cases (< %d) — "
                    + "the corpus cannot discriminate%n", distinct.size(), files.size(), required);
            failures++;
        }

        System.out.println("[3] a known-wrong implementation is detected");
        int detected = 0;
        for (Path file : files) {
            Trace expected = run(reference, file);
            Trace actual = run(new BrokenTerminal(ROWS, COLUMNS), file);
            if (!expected.sameAs(actual)) {
                detected++;
            }
        }
        if (detected == files.size()) {
            System.out.printf("      ok: all %d cases detect a terminal that draws nothing%n", files.size());
        } else {
            System.out.printf("      FAIL: %d/%d detected — comparison is not wired up%n",
                    detected, files.size());
            failures++;
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("selftest passed  \u2713");
            return 0;
        }
        System.out.printf("selftest FAILED (%d problem(s))  \u2717%n", failures);
        return 1;
    }

    private static String indent(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                out.append("              ").append(line).append('\n');
            }
        }
        return out.toString();
    }

    static {
        // Keep the report stable regardless of the host's locale.
        Locale.setDefault(Locale.ROOT);
    }
}
