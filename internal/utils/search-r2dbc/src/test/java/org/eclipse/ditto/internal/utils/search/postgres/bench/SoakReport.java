/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nullable;

/** Markdown and JSON rendering of the write-soak evidence (pure). */
final class SoakReport {

    private static final int MAX_LISTED_WINDOWS = 40;

    private SoakReport() {
        throw new AssertionError("no instances");
    }

    static String windowsHeader(final WriteSoakRunner.Settings settings, final double gateRate) {
        return String.format(Locale.ROOT, "# Production write-path soak: %d s windows%n%n"
                        + "Offered %.1f updates/s by a rate limiter that drops missed slots, so a window below the "
                        + "target means the writers could not keep up; the gate is %.1f/s in every gated window. "
                        + "Dead tuples and sizes are pg_stat_all_tables / pg_class at the window's end; per-update "
                        + "values are this window's pg_stat deltas divided by its updates.%n%n",
                settings.window().toSeconds(), settings.targetRate(), gateRate)
                + "| # | end s | phase | updates | upd/s | err | p50 ms | p95 ms | max ms | WAL MiB | FPI "
                + "| flat del/upd | flat ins/upd | flat dead | flat GiB | things dead | things GiB | TOAST dead "
                + "| TOAST MiB | autovacuums flat/things/TOAST | vacuum running |\n"
                + "|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:"
                + "|---|---|\n";
    }

    static String windowRow(final SoakAnalysis.Window w) {
        final SoakAnalysis.RelationSample flat = w.relations().get(SoakAnalysis.SEARCH_FLAT);
        final SoakAnalysis.RelationSample things = w.relations().get(SoakAnalysis.SEARCH_THINGS);
        final SoakAnalysis.RelationSample toast = w.relations().get(SoakAnalysis.SEARCH_THINGS_TOAST);
        final SoakAnalysis.RelationDelta flatDelta =
                w.deltas().getOrDefault(SoakAnalysis.SEARCH_FLAT, SoakAnalysis.RelationDelta.ZERO);
        return String.format(Locale.ROOT, "| %d | %.0f | %s | %d | %.1f | %d | %.1f | %.1f | %.1f | %.1f | %d "
                        + "| %.1f | %.1f | %s | %s | %s | %s | %s | %s | %s | %s |%n",
                w.number(), w.endSeconds(), w.gated() ? "gated" : "warm-up", w.updates(), w.rate(), w.errors(),
                w.p50Micros() / 1000.0, w.p95Micros() / 1000.0, w.maxMicros() / 1000.0,
                w.walBytes() / 1_048_576.0, w.walFullPageImages(),
                perUpdate(flatDelta.deleted(), w.updates()), perUpdate(flatDelta.inserted(), w.updates()),
                dead(flat), size(flat, 1L << 30, "%.2f"), dead(things), size(things, 1L << 30, "%.2f"),
                dead(toast), size(toast, 1L << 20, "%.1f"),
                SoakAnalysis.RELATIONS.stream()
                        .map(r -> w.relations().containsKey(r)
                                ? Long.toString(w.relations().get(r).autovacuumCount()) : "-")
                        .collect(Collectors.joining("/")),
                w.vacuumsRunning().isEmpty() ? "-" : String.join("; ", w.vacuumsRunning()));
    }

    static String environment(final WriteSoakRunner.Settings settings, final double gateRate, final int poolMaxSize,
            final Map<String, String> serverSettings, final Map<String, List<String>> reloptions,
            final Map<String, SoakAnalysis.VacuumTrigger> triggers, final ProductionConfigSchema.CatalogCheck catalog,
            final SoakAnalysis.Snapshot start, final Instant at) {
        final StringBuilder sb = new StringBuilder("# Production write-path soak: environment\n\n");
        sb.append("- recorded: ").append(at).append('\n');
        sb.append(String.format(Locale.ROOT, "- harness JVM: Java %s, %d processors, max heap %,d MiB, %s %s%n",
                Runtime.version(), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() >> 20, System.getProperty("os.name"), System.getProperty("os.arch")));
        sb.append(String.format(Locale.ROOT, "- write path: PostgresSearchUpdaterFlow.of(client) on the shipped "
                + "ditto-postgres-search profile (pool max-size %d); documents from EnforcedThingMapper"
                + ".toWriteModel; one write in flight per writer%n", poolMaxSize));
        sb.append(String.format(Locale.ROOT, "- corpus: %,d soak-twin things, seed %d%n", settings.thingCount(),
                settings.seed()));
        sb.append(String.format(Locale.ROOT, "- writers %d, target %.1f/s, gate %.1f/s, warm-up %d s, gated %d s, "
                        + "plateau window %d s, windows %d s, vacuum poll %d s%n", settings.workers(),
                settings.targetRate(), gateRate, settings.warmup().toSeconds(), settings.gated().toSeconds(),
                settings.plateau().toSeconds(), settings.window().toSeconds(), settings.vacuumPoll().toSeconds()));
        sb.append(String.format(Locale.ROOT, "- catalog check: %s (%d items compared)%n",
                catalog.matchesProduction() ? "matches the production DDL" : "DIVERGES: " + catalog.differences(),
                catalog.comparedItems()));
        sb.append("\n## Server settings\n\n| setting | value |\n|---|---|\n");
        serverSettings.forEach((name, value) -> sb.append("| ").append(name).append(" | ").append(value)
                .append(" |\n"));
        sb.append("\n## Reloptions and autovacuum triggers at the start\n\n"
                + "| relation | reloptions | trigger = threshold + scale factor x reltuples |\n|---|---|---|\n");
        for (final String relation : SoakAnalysis.RELATIONS) {
            final SoakAnalysis.VacuumTrigger trigger = triggers.get(relation);
            final SoakAnalysis.RelationSample sample = start.relations().get(relation);
            final String options = String.join(", ", reloptions.getOrDefault(relation, List.of()));
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s |%n", relation,
                    options.isEmpty() ? "(none)" : options,
                    trigger == null || sample == null ? "-" : String.format(Locale.ROOT, "%.0f + %s x %,d = %,d",
                            trigger.baseThreshold(), trigger.scaleFactor(), sample.reltuples(),
                            trigger.threshold(sample.reltuples()))));
        }
        sb.append("\n## Sizes at the start\n\n| relation | live tuples | heap | indexes | total |\n"
                + "|---|---:|---:|---:|---:|\n");
        start.relations().values().forEach(r -> sb.append(String.format(Locale.ROOT, "| %s | %,d | %s | %s | %s |%n",
                r.relation(), r.liveTuples(), human(r.heapBytes()), human(r.indexBytes()), human(r.totalBytes()))));
        sb.append("\n| index | size |\n|---|---:|\n");
        start.indexBytes().forEach((index, bytes) -> sb.append("| ").append(index).append(" | ")
                .append(human(bytes)).append(" |\n"));
        return sb.toString();
    }

    static String vacuum(final WriteSoakRunner.Settings settings, final WriteSoakRunner.Result result) {
        final StringBuilder sb = new StringBuilder("# Production write-path soak: vacuum cycles\n\n");
        sb.append(String.format(Locale.ROOT, "Polled every %d s from pg_stat_progress_vacuum joined with "
                + "pg_stat_activity; a cycle runs from its first to its last poll, so durations are accurate to one "
                + "poll interval and cycles shorter than that can be missed (pg_stat autovacuum_count in "
                + "windows.md counts every completed one).%n%n", settings.vacuumPoll().toSeconds()));
        sb.append("| relation | pid | autovacuum | first seen s | last seen s | duration s | finished "
                + "| max index passes | heap blocks | wait events (polls) |\n"
                + "|---|---:|---|---:|---:|---:|---|---:|---:|---|\n");
        for (final SoakAnalysis.VacuumCycle c : result.vacuumCycles()) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %s | %.0f | %.0f | %.0f | %s | %d | %,d | %s |%n",
                    c.relation(), c.pid(), yesNo(c.autovacuum()), c.firstSeenSeconds(), c.lastSeenSeconds(),
                    c.seconds(), yesNo(c.finished()), c.maxIndexVacuumCount(), c.heapBlocksTotal(),
                    new TreeMap<>(c.waitEvents())));
        }
        if (!result.pollerErrors().isEmpty()) {
            sb.append("\nPoller errors:\n\n");
            result.pollerErrors().forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        return sb.toString();
    }

    static String summary(final WriteSoakRunner.Settings settings, final double gateRate,
            final WriteSoakRunner.Result result, final SoakAnalysis.GateVerdict gate,
            final List<SoakAnalysis.PlateauVerdict> plateaus) {
        final List<SoakAnalysis.Window> gated = result.windows().stream().filter(SoakAnalysis.Window::gated).toList();
        final long gatedUpdates = gated.stream().mapToLong(SoakAnalysis.Window::updates).sum();
        final StringBuilder sb = new StringBuilder("# Production write-path soak: summary\n\n");
        sb.append(String.format(Locale.ROOT, "- corpus: %,d things (seed %d); %d writers through the shipped write "
                        + "flow; target %.1f/s, gate %.1f/s%n", settings.thingCount(), settings.seed(),
                settings.workers(), settings.targetRate(), gateRate));
        sb.append(String.format(Locale.ROOT, "- warm-up %d s, gated %.0f s (the gate needs %d s), plateau window "
                        + "%d s, %d s windows%n", settings.warmup().toSeconds(), gate.gatedSeconds(),
                SoakAnalysis.MINIMUM_GATED.toSeconds(), settings.plateau().toSeconds(), settings.window().toSeconds()));
        sb.append(String.format(Locale.ROOT, "- updates: %,d (gated %,d = %.1f/s); failed updates: %d%n",
                result.updates(), gatedUpdates, gate.gatedSeconds() > 0 ? gatedUpdates / gate.gatedSeconds() : 0.0,
                result.errors()));
        if (result.lateErrors() > 0 || result.writersAlive() > 0) {
            sb.append(String.format(Locale.ROOT, "- after the last window: %d failed update(s) that were still in "
                    + "flight, %d writer(s) still running%n", result.lateErrors(), result.writersAlive()));
        }
        sb.append(String.format(Locale.ROOT, "- latency over the gated windows: p50 %.1f ms, p95 %.1f ms, "
                        + "max %.1f ms%n", result.gatedLatencyPercentileMicros(50) / 1000.0,
                result.gatedLatencyPercentileMicros(95) / 1000.0,
                gated.stream().mapToLong(SoakAnalysis.Window::maxMicros).max().orElse(0) / 1000.0));
        result.errorSamples().forEach(e -> sb.append("- failed update: ").append(e).append('\n'));

        sb.append("\n## Throughput gate: ").append(gate.met() ? "MET" : "NOT MET").append("\n\n");
        sb.append("- ").append(gate.reason()).append('\n');
        sb.append(String.format(Locale.ROOT, "- lowest gated window: %.1f/s; longest run of passing gated windows: "
                + "%d (%.0f s)%n", gate.minRate(), gate.longestPassingRun(), gate.longestPassingSeconds()));
        if (!gate.failingWindows().isEmpty()) {
            sb.append("- failing windows: ").append(gate.failingWindows().stream().limit(MAX_LISTED_WINDOWS)
                    .map(String::valueOf).collect(Collectors.joining(", ")))
                    .append(gate.failingWindows().size() > MAX_LISTED_WINDOWS ? ", ..." : "").append('\n');
        }

        sb.append("\n## Bloat plateau (last ").append(settings.plateau().toSeconds())
                .append(" s of the gated period)\n\n| relation | dead tuples bounded | max dead tuples "
                        + "| vacuum trigger | autovacuums in window | size flat | size at half -> end | verdict |\n"
                        + "|---|---|---:|---:|---:|---|---|---|\n");
        for (final SoakAnalysis.PlateauVerdict p : plateaus) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %,d | %,d | %d | %s | %s -> %s (%+.2f %%) | %s |%n",
                    p.relation(), yesNo(p.deadTuplesBounded()), p.maxDeadTuples(), p.vacuumThreshold(),
                    p.autovacuumsInWindow(), yesNo(p.sizeFlat()), human(p.sizeAtHalf()), human(p.sizeAtEnd()),
                    100.0 * (p.sizeAtEnd() - p.sizeAtHalf()) / Math.max(1, p.sizeAtHalf()),
                    p.plateau() ? "PLATEAU" : "NO PLATEAU: " + p.reason()));
        }
        sb.append(String.format(Locale.ROOT, "%nRule: dead tuples are bounded when at least one autovacuum of the "
                        + "relation completed inside the window and the window's peak n_dead_tup stayed at or below "
                        + "%.1f x the autovacuum trigger (threshold + scale factor x reltuples); the size is flat "
                        + "when pg_total_relation_size grew by at most %.1f %% over the window's second half.%n",
                SoakAnalysis.DEAD_TUPLE_THRESHOLD_FACTOR, SoakAnalysis.SIZE_GROWTH_LIMIT * 100));

        sb.append("\n## Write cost per update (gated windows, pg_stat deltas)\n\n");
        final SoakAnalysis.RelationDelta flat = sum(gated, SoakAnalysis.SEARCH_FLAT);
        final SoakAnalysis.RelationDelta things = sum(gated, SoakAnalysis.SEARCH_THINGS);
        final long wal = gated.stream().mapToLong(SoakAnalysis.Window::walBytes).sum();
        final long fpi = gated.stream().mapToLong(SoakAnalysis.Window::walFullPageImages).sum();
        sb.append(String.format(Locale.ROOT, "- search_flat rows deleted / inserted per update: %.2f / %.2f%n",
                perUpdate(flat.deleted(), gatedUpdates), perUpdate(flat.inserted(), gatedUpdates)));
        sb.append(String.format(Locale.ROOT, "- search_things row updates per update: %.2f (HOT: %.1f %%)%n",
                perUpdate(things.updated(), gatedUpdates),
                100.0 * things.hotUpdated() / Math.max(1, things.updated())));
        sb.append(String.format(Locale.ROOT, "- WAL per update: %.1f KiB; full-page images per update: %.2f%n",
                perUpdate(wal, gatedUpdates) / 1024.0, perUpdate(fpi, gatedUpdates)));

        sb.append("\n## Autovacuum (inputs for the reloption decision)\n\n| relation | autovacuums completed "
                + "(pg_stat, whole run) | cycles seen (finished / running at the end) | duration min / median / max s "
                + "| median interval between starts s | max index passes | VacuumDelay share | IO share |\n"
                + "|---|---:|---|---|---:|---:|---:|---:|\n");
        final SoakAnalysis.Window last = result.windows().isEmpty() ? null
                : result.windows().get(result.windows().size() - 1);
        for (final String relation : SoakAnalysis.RELATIONS) {
            sb.append(autovacuumRow(relation, result, last));
        }
        sb.append("\nIndex passes > 1 mean the dead row ids overflowed the vacuum's memory (autovacuum_work_mem, "
                + "else maintenance_work_mem). A worker mostly in Timeout:VacuumDelay is held back by the cost limit; "
                + "one mostly in IO:* waits on the storage, where a higher cost limit does not help.\n");
        return sb.toString();
    }

    static String json(final WriteSoakRunner.Settings settings, final double gateRate,
            final WriteSoakRunner.Result result) {
        final Map<String, Object> root = new LinkedHashMap<>();
        final Map<String, Object> s = new LinkedHashMap<>();
        s.put("thingCount", settings.thingCount());
        s.put("seed", settings.seed());
        s.put("workers", settings.workers());
        s.put("targetRate", settings.targetRate());
        s.put("gateRate", gateRate);
        s.put("warmupSeconds", settings.warmup().toSeconds());
        s.put("gatedSeconds", settings.gated().toSeconds());
        s.put("plateauSeconds", settings.plateau().toSeconds());
        s.put("windowSeconds", settings.window().toSeconds());
        root.put("settings", s);
        final List<Object> windows = new ArrayList<>();
        for (final SoakAnalysis.Window w : result.windows()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", w.number());
            m.put("endSeconds", w.endSeconds());
            m.put("seconds", w.seconds());
            m.put("gated", w.gated());
            m.put("updates", w.updates());
            m.put("errors", w.errors());
            m.put("p50Micros", w.p50Micros());
            m.put("p95Micros", w.p95Micros());
            m.put("maxMicros", w.maxMicros());
            m.put("walBytes", w.walBytes());
            m.put("walFullPageImages", w.walFullPageImages());
            final Map<String, Object> relations = new LinkedHashMap<>();
            w.relations().forEach((name, r) -> relations.put(name, relationJson(r, w.deltas().get(name))));
            m.put("relations", relations);
            m.put("indexBytes", new LinkedHashMap<String, Object>(w.indexBytes()));
            final Map<String, Object> waits = new LinkedHashMap<>();
            w.vacuumWaitEvents().forEach((name, events) -> waits.put(name, new LinkedHashMap<String, Object>(events)));
            m.put("vacuumWaitEvents", waits);
            m.put("vacuumsRunning", new ArrayList<Object>(w.vacuumsRunning()));
            windows.add(m);
        }
        root.put("windows", windows);
        final List<Object> cycles = new ArrayList<>();
        for (final SoakAnalysis.VacuumCycle c : result.vacuumCycles()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("relation", c.relation());
            m.put("pid", c.pid());
            m.put("autovacuum", c.autovacuum());
            m.put("firstSeenSeconds", c.firstSeenSeconds());
            m.put("lastSeenSeconds", c.lastSeenSeconds());
            m.put("finished", c.finished());
            m.put("maxIndexVacuumCount", c.maxIndexVacuumCount());
            m.put("heapBlocksTotal", c.heapBlocksTotal());
            m.put("waitEvents", new LinkedHashMap<String, Object>(c.waitEvents()));
            cycles.add(m);
        }
        root.put("vacuumCycles", cycles);
        return JsonWriter.write(root);
    }

    private static Map<String, Object> relationJson(final SoakAnalysis.RelationSample r,
            @Nullable final SoakAnalysis.RelationDelta d) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("liveTuples", r.liveTuples());
        m.put("deadTuples", r.deadTuples());
        m.put("reltuples", r.reltuples());
        m.put("autovacuumCount", r.autovacuumCount());
        m.put("lastAutovacuum", r.lastAutovacuum());
        m.put("heapBytes", r.heapBytes());
        m.put("indexBytes", r.indexBytes());
        m.put("totalBytes", r.totalBytes());
        if (d != null) {
            m.put("inserted", d.inserted());
            m.put("updated", d.updated());
            m.put("hotUpdated", d.hotUpdated());
            m.put("deleted", d.deleted());
        }
        return m;
    }

    private static String autovacuumRow(final String relation, final WriteSoakRunner.Result result,
            @Nullable final SoakAnalysis.Window last) {
        final SoakAnalysis.RelationSample begin = result.start().relations().get(relation);
        final SoakAnalysis.RelationSample end = last == null ? null : last.relations().get(relation);
        final String completed = begin == null || end == null ? "-"
                : Long.toString(end.autovacuumCount() - begin.autovacuumCount());
        final List<SoakAnalysis.VacuumCycle> cycles = result.vacuumCycles().stream()
                .filter(c -> c.relation().equals(relation) && c.autovacuum()).toList();
        final List<Double> durations = cycles.stream().filter(SoakAnalysis.VacuumCycle::finished)
                .map(SoakAnalysis.VacuumCycle::seconds).sorted().toList();
        final List<Double> starts = cycles.stream().map(SoakAnalysis.VacuumCycle::firstSeenSeconds).sorted().toList();
        final List<Double> intervals = IntStream.range(1, starts.size())
                .mapToObj(i -> starts.get(i) - starts.get(i - 1)).sorted().toList();
        final Map<String, Long> waits = new TreeMap<>();
        cycles.forEach(c -> c.waitEvents().forEach((event, n) -> waits.merge(event, n, Long::sum)));
        final long polls = waits.values().stream().mapToLong(Long::longValue).sum();
        final long delay = waits.getOrDefault("Timeout:VacuumDelay", 0L);
        final long io = waits.entrySet().stream().filter(e -> e.getKey().startsWith("IO:"))
                .mapToLong(Map.Entry::getValue).sum();
        return String.format(Locale.ROOT, "| %s | %s | %d / %d | %s | %s | %d | %s | %s |%n", relation, completed,
                durations.size(), cycles.size() - durations.size(),
                durations.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f / %.0f / %.0f", durations.get(0),
                        durations.get(durations.size() / 2), durations.get(durations.size() - 1)),
                intervals.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f", intervals.get(intervals.size() / 2)),
                cycles.stream().mapToLong(SoakAnalysis.VacuumCycle::maxIndexVacuumCount).max().orElse(0),
                share(delay, polls), share(io, polls));
    }

    private static SoakAnalysis.RelationDelta sum(final List<SoakAnalysis.Window> windows, final String relation) {
        long ins = 0;
        long upd = 0;
        long hot = 0;
        long del = 0;
        long av = 0;
        for (final SoakAnalysis.Window w : windows) {
            final SoakAnalysis.RelationDelta d = w.deltas().getOrDefault(relation, SoakAnalysis.RelationDelta.ZERO);
            ins += d.inserted();
            upd += d.updated();
            hot += d.hotUpdated();
            del += d.deleted();
            av += d.autovacuums();
        }
        return new SoakAnalysis.RelationDelta(ins, upd, hot, del, av);
    }

    private static double perUpdate(final long n, final long updates) {
        return updates == 0 ? 0.0 : (double) n / updates;
    }

    private static String dead(@Nullable final SoakAnalysis.RelationSample sample) {
        return sample == null ? "-" : String.format(Locale.ROOT, "%,d", sample.deadTuples());
    }

    private static String size(@Nullable final SoakAnalysis.RelationSample sample, final long unit,
            final String format) {
        return sample == null ? "-" : String.format(Locale.ROOT, format, (double) sample.totalBytes() / unit);
    }

    private static String share(final long part, final long total) {
        return total == 0 ? "-" : String.format(Locale.ROOT, "%.0f %%", 100.0 * part / total);
    }

    private static String yesNo(final boolean value) {
        return value ? "yes" : "no";
    }

    static String human(final long bytes) {
        if (bytes >= 1L << 40) {
            return String.format(Locale.ROOT, "%.2f TiB", (double) bytes / (1L << 40));
        } else if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.2f GiB", (double) bytes / (1L << 30));
        }
        return String.format(Locale.ROOT, "%.1f MiB", (double) bytes / (1L << 20));
    }

}
