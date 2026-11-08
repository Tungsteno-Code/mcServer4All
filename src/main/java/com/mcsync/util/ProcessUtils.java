package com.mcsync.util;

import com.mcsync.config.Config;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Process helpers: Windows-friendly command preparation and process-tree termination. */
public final class ProcessUtils {

    private ProcessUtils() {
    }

    /**
     * Prepares a configured command:
     * <ul>
     *     <li>replaces {@code {placeholders}} with the given values,</li>
     *     <li>expands {@code ~} and {@code %ENV%} variables,</li>
     *     <li>on Windows wraps {@code .bat}/{@code .cmd} scripts with {@code cmd.exe /c}, which
     *     {@link ProcessBuilder} cannot execute directly.</li>
     * </ul>
     */
    public static List<String> prepareCommand(List<String> command, Map<String, String> placeholders) {
        List<String> out = new ArrayList<>(command.size() + 2);
        for (String part : command) {
            String s = part;
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                s = s.replace("{" + e.getKey() + "}", e.getValue());
            }
            out.add(Config.expandHome(s));
        }
        if (FileUtils.IS_WINDOWS && !out.isEmpty()) {
            String first = out.get(0).toLowerCase(Locale.ROOT);
            if (first.endsWith(".bat") || first.endsWith(".cmd")) {
                out.add(0, "/c");
                out.add(0, "cmd.exe");
            }
        } else if (!out.isEmpty() && out.get(0).toLowerCase(Locale.ROOT).endsWith(".sh")) {
            // run.sh is usually not executable after unzipping and the cwd is not on the PATH.
            out.add(0, "bash");
        }
        return out;
    }

    /**
     * Makes a child process use the given Java installation: sets {@code JAVA_HOME} and prepends
     * its {@code bin} folder to {@code PATH}, so scripts calling a bare {@code java} (Forge
     * {@code run.bat}) do not pick up an old Java 8 from the system PATH.
     */
    public static void applyJavaHome(ProcessBuilder pb, java.nio.file.Path javaHome) {
        if (javaHome == null) return;
        java.nio.file.Path bin = javaHome.resolve("bin");
        if (!java.nio.file.Files.isDirectory(bin)) return;
        Map<String, String> env = pb.environment();
        String pathKey = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String old = env.getOrDefault(pathKey, "");
        env.put(pathKey, bin + java.io.File.pathSeparator + old);
        env.put("JAVA_HOME", javaHome.toString());
    }

    /**
     * Charset used by child processes for their console output. Java 18+ defaults
     * {@code file.encoding} to UTF-8, but child processes on Windows still write with the native
     * code page, which is exposed as {@code native.encoding}.
     */
    public static Charset childCharset() {
        String enc = System.getProperty("native.encoding");
        try {
            return enc != null ? Charset.forName(enc) : Charset.defaultCharset();
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }

    /**
     * Forcibly kills a process and all of its descendants. Needed on Windows where killing a
     * {@code cmd.exe} wrapper does not terminate the {@code java.exe} child.
     */
    public static void killTree(Process process) {
        if (process == null) return;
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
        try {
            process.waitFor(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
