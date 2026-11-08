package com.mcsync.gui.controller;

import com.mcsync.util.FileUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Samples RAM (resident set size) and CPU usage of the Minecraft server JVM. When the server is
 * started through {@code run.bat}/{@code run.sh} the real JVM is a child of the wrapper process.
 */
public final class ResourceMonitor {

    public record Sample(long ramUsedMb, double cpuPercent) {
    }

    private final ProcessHandle root;
    private long lastCpuNanos = -1;
    private Instant lastWall;
    private long peakMb;

    public ResourceMonitor(ProcessHandle root) {
        this.root = root;
    }

    public long peakMb() {
        return peakMb;
    }

    public Optional<Sample> sample() {
        ProcessHandle jvm = findJvm();
        if (jvm == null || !jvm.isAlive()) return Optional.empty();
        long rss = rssMb(jvm.pid());
        if (rss > peakMb) peakMb = rss;
        double cpu = 0;
        Optional<Duration> total = jvm.info().totalCpuDuration();
        Instant now = Instant.now();
        if (total.isPresent()) {
            long nanos = total.get().toNanos();
            if (lastCpuNanos >= 0 && lastWall != null) {
                long wall = Duration.between(lastWall, now).toNanos();
                if (wall > 0) {
                    cpu = 100.0 * (nanos - lastCpuNanos) / wall / Runtime.getRuntime().availableProcessors();
                }
            }
            lastCpuNanos = nanos;
            lastWall = now;
        }
        return Optional.of(new Sample(Math.max(rss, 0), Math.max(0, Math.min(100, cpu))));
    }

    private ProcessHandle findJvm() {
        if (root == null) return null;
        if (isJava(root)) return root;
        List<ProcessHandle> javas = root.descendants().filter(ResourceMonitor::isJava).toList();
        if (javas.isEmpty()) return root.isAlive() ? root : null;
        return javas.stream().max(Comparator.comparing(
                p -> p.info().totalCpuDuration().orElse(Duration.ZERO))).orElse(null);
    }

    private static boolean isJava(ProcessHandle p) {
        String c = p.info().command().orElse("").toLowerCase(Locale.ROOT);
        return c.endsWith("java") || c.endsWith("java.exe") || c.endsWith("javaw.exe");
    }

    /** Resident memory in MB, -1 if unknown. */
    static long rssMb(long pid) {
        try {
            Path status = Path.of("/proc", Long.toString(pid), "status");
            if (Files.isRegularFile(status)) {
                for (String l : Files.readAllLines(status)) {
                    if (l.startsWith("VmRSS:")) {
                        return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
                    }
                }
            }
            List<String> cmd = FileUtils.IS_WINDOWS
                    ? List.of("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
                    : List.of("ps", "-o", "rss=", "-p", Long.toString(pid));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().reduce("", (a, b) -> a + b);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            if (FileUtils.IS_WINDOWS) {
                // "java.exe","1234","Console","1","1.234.567 K"
                String[] parts = out.split("\",\"");
                if (parts.length < 5) return -1;
                return Long.parseLong(parts[4].replaceAll("[^0-9]", "")) / 1024;
            }
            return Long.parseLong(out.trim()) / 1024;
        } catch (Exception e) {
            return -1;
        }
    }
}
