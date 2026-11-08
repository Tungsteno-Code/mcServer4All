package com.mcsync.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mcsync.config.Config;
import com.mcsync.core.ClientLauncher;
import com.mcsync.setup.PrismInstances;
import com.mcsync.setup.ServerProfileDetector;
import com.mcsync.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Edits {@code config.json} in place (preserving unknown keys and field order) and applies the
 * automatic fixes proposed by {@link LogAnalyzer}.
 */
public final class ConfigFixer {

    private final Path configFile;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ConfigFixer(Path configFile) {
        this.configFile = configFile;
    }

    public Path file() {
        return configFile;
    }

    public ObjectNode read() throws IOException {
        if (!Files.exists(configFile)) return mapper.createObjectNode();
        JsonNode n = mapper.readTree(Files.readString(configFile, StandardCharsets.UTF_8));
        return n instanceof ObjectNode o ? o : mapper.createObjectNode();
    }

    public void write(ObjectNode node) throws IOException {
        Path parent = configFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Files.writeString(tmp, mapper.writeValueAsString(node) + System.lineSeparator(), StandardCharsets.UTF_8);
        FileUtils.moveWithRetry(tmp, configFile);
    }

    /** Sets top-level properties (String, Boolean, Number or List of String / objects). */
    public void set(Map<String, ?> values) throws IOException {
        ObjectNode node = read();
        values.forEach((k, v) -> put(node, k, v));
        write(node);
    }

    void put(ObjectNode node, String key, Object v) {
        if (v == null) node.remove(key);
        else if (v instanceof Boolean b) node.put(key, b);
        else if (v instanceof Integer i) node.put(key, i);
        else if (v instanceof Long l) node.put(key, l);
        else if (v instanceof List<?> list) {
            ArrayNode arr = node.putArray(key);
            list.forEach(e -> {
                if (e instanceof CharSequence || e instanceof Number || e instanceof Boolean) arr.add(String.valueOf(e));
                else arr.add(mapper.valueToTree(e));
            });
        } else node.put(key, String.valueOf(v));
    }

    /**
     * Applies a fix.
     *
     * @return a message for the user describing what was changed
     */
    public String apply(LogAnalyzer.FixAction action, Config cfg) throws IOException {
        switch (action) {
            case ALIGN_CLIENT_IP -> {
                set(Map.of("localClientAddress", "auto"));
                return "localClientAddress = \"auto\": il client locale userà lo stesso IP a cui si lega il server ("
                        + (cfg != null ? cfg.localTailscaleIp() : "IP Tailscale") + ").";
            }
            case FIX_RUN_SCRIPT, DETECT_SERVER_COMMAND -> {
                if (cfg == null) throw new IOException("Configurazione non caricata");
                ServerProfileDetector.Detection d = ServerProfileDetector.detect(cfg.serverDir());
                List<String> cmd = action == LogAnalyzer.FixAction.FIX_RUN_SCRIPT
                        && Files.isRegularFile(cfg.serverDir().resolve(ServerProfileDetector.runScriptName()))
                        ? List.of(ServerProfileDetector.runScriptName(), "nogui") : d.suggestedCommand();
                if (cmd.isEmpty()) {
                    throw new IOException("Nessun server riconosciuto in " + cfg.serverDir() + " (" + d.profile().label
                            + "). Se è un Server Pack non installato usa 'Esegui Installazione Server Pack'.");
                }
                set(Map.of("serverStartCommand", cmd));
                return "serverStartCommand = " + cmd + " (" + d.profile().label + ")";
            }
            case USE_JAVA21_FOR_SERVER -> {
                Path base = configFile.toAbsolutePath().getParent();
                Path jdk = base.resolve(".tools").resolve("jdk-21");
                if (!Files.isDirectory(jdk.resolve("bin"))) {
                    String home = System.getProperty("java.home");
                    if (Runtime.version().feature() >= 17 && home != null) jdk = Path.of(home);
                    else throw new IOException("Java 21 non trovato: esegui 'launch setup'");
                }
                set(Map.of("serverJavaHome", jdk.toString()));
                return "serverJavaHome = " + jdk + ": run.bat userà questo Java (PATH e JAVA_HOME impostati per il server).";
            }
            case ACCEPT_EULA -> {
                set(Map.of("acceptEula", true));
                return "acceptEula = true (https://aka.ms/MinecraftEULA)";
            }
            case FIX_CLIENT_INSTANCE -> {
                Path inst = guessInstanceDir(cfg);
                if (inst == null) throw new IOException("Nessuna istanza Prism trovata: selezionala nella scheda Configurazione");
                set(Map.of("clientInstanceDirectory", FileUtils.toPortable(inst)));
                return "clientInstanceDirectory = " + inst;
            }
            case FIX_PRISM_PATH -> {
                if (cfg == null || cfg.clientLaunchCommand().isEmpty()) throw new IOException("clientLaunchCommand vuoto");
                Path first = Path.of(Config.expandHome(cfg.clientLaunchCommand().get(0)));
                Path exe = Files.isDirectory(first) ? ClientLauncher.findLauncherExecutable(first) : null;
                if (exe == null) {
                    for (Path dir : PrismInstances.launcherDirs(configFile.toAbsolutePath().getParent())) {
                        exe = ClientLauncher.findLauncherExecutable(dir);
                        if (exe != null) break;
                    }
                }
                if (exe == null) throw new IOException("prismlauncher non trovato: esegui 'launch setup'");
                List<String> cmd = new ArrayList<>(cfg.clientLaunchCommand());
                cmd.set(0, FileUtils.toPortable(exe));
                set(Map.of("clientLaunchCommand", cmd));
                return "clientLaunchCommand[0] = " + exe;
            }
            default -> {
                return "";
            }
        }
    }

    private Path guessInstanceDir(Config cfg) {
        if (cfg != null && cfg.clientInstanceDir() != null && Files.isDirectory(cfg.clientInstanceDir())) {
            return cfg.clientInstanceDir();
        }
        String wanted = null;
        if (cfg != null) {
            int i = cfg.clientLaunchCommand().indexOf("--launch");
            if (i >= 0 && i + 1 < cfg.clientLaunchCommand().size()) wanted = cfg.clientLaunchCommand().get(i + 1);
        }
        List<PrismInstances.Instance> all = PrismInstances.scan(configFile.toAbsolutePath().getParent());
        for (PrismInstances.Instance in : all) {
            if (in.id().equals(wanted)) return in.gameDir();
        }
        return all.size() == 1 ? all.get(0).gameDir() : null;
    }

    /** Updates {@code -Xms/-Xmx} in user_jvm_args.txt (Forge) or in the start command. */
    public static String applyRam(Path configFile, Config cfg, int minGb, int maxGb) throws IOException {
        Path jvmArgs = cfg.serverDir().resolve("user_jvm_args.txt");
        if (Files.isRegularFile(jvmArgs)) {
            List<String> lines = new ArrayList<>(Files.readAllLines(jvmArgs, StandardCharsets.ISO_8859_1));
            lines.removeIf(l -> l.trim().startsWith("-Xms") || l.trim().startsWith("-Xmx"));
            lines.add("-Xms" + minGb + "G");
            lines.add("-Xmx" + maxGb + "G");
            Files.write(jvmArgs, lines, StandardCharsets.ISO_8859_1);
            return "user_jvm_args.txt: -Xms" + minGb + "G -Xmx" + maxGb + "G";
        }
        List<String> cmd = new ArrayList<>(cfg.serverStartCommand());
        if (cmd.isEmpty()) return "";
        cmd.removeIf(a -> a.startsWith("-Xms") || a.startsWith("-Xmx"));
        String first = Path.of(cmd.get(0)).getFileName().toString().toLowerCase();
        if (!first.startsWith("java")) return "Comando server senza java: imposta la RAM nello script di avvio";
        cmd.add(1, "-Xmx" + maxGb + "G");
        cmd.add(1, "-Xms" + minGb + "G");
        new ConfigFixer(configFile).set(Map.of("serverStartCommand", cmd));
        return "serverStartCommand: -Xms" + minGb + "G -Xmx" + maxGb + "G";
    }

    /** Reads the current -Xms/-Xmx (GB) from user_jvm_args.txt or the start command; {min,max}. */
    public static int[] readRam(Config cfg) {
        int[] r = {4, 6};
        List<String> tokens = new ArrayList<>(cfg.serverStartCommand());
        Path jvmArgs = cfg.serverDir().resolve("user_jvm_args.txt");
        if (Files.isRegularFile(jvmArgs)) {
            try (Stream<String> s = Files.lines(jvmArgs, StandardCharsets.ISO_8859_1)) {
                s.map(String::trim).filter(l -> !l.startsWith("#")).forEach(tokens::add);
            } catch (Exception ignored) {
                // keep defaults
            }
        }
        for (String t : tokens) {
            if (t.startsWith("-Xms")) r[0] = parseGb(t.substring(4), r[0]);
            if (t.startsWith("-Xmx")) r[1] = parseGb(t.substring(4), r[1]);
        }
        return r;
    }

    public static int parseGb(String v, int def) {
        try {
            String l = v.trim().toLowerCase();
            if (l.endsWith("g")) return Integer.parseInt(l.substring(0, l.length() - 1));
            if (l.endsWith("m")) return Math.max(1, Integer.parseInt(l.substring(0, l.length() - 1)) / 1024);
            return def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
