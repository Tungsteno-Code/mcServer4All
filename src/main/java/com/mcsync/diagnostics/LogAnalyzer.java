package com.mcsync.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Heuristic log analyser: recognises known failure signatures in launcher / server output and
 * turns them into a human explanation plus an automatic fix ({@link FixAction}).
 */
public final class LogAnalyzer {

    /** Automatic remediation offered by the GUI (or printed as a hint by the CLI). */
    public enum FixAction {
        ALIGN_CLIENT_IP("Allinea IP Client a Tailscale"),
        FIX_RUN_SCRIPT("Correggi comando in run.bat / run.sh"),
        USE_JAVA21_FOR_SERVER("Usa Java 21 per il server"),
        FORCE_UNLOCK("Sblocca Sessione (Force Unlock)"),
        ACCEPT_EULA("Accetta EULA di Minecraft"),
        PULL_MODS("Sincronizza Mod"),
        FIX_CLIENT_INSTANCE("Rileva cartella istanza Prism"),
        FIX_PRISM_PATH("Correggi percorso prismlauncher.exe"),
        DETECT_SERVER_COMMAND("Rileva comando di avvio server"),
        OPEN_TAILSCALE_ADMIN("Apri console Tailscale"),
        OPEN_CONFIG("Apri Configurazione"),
        NONE("");

        public final String label;

        FixAction(String label) {
            this.label = label;
        }
    }

    /** A recognised problem. */
    public record Diagnosis(String id, String title, String explanation, String evidence, FixAction fix) {
    }

    private record Rule(String id, Pattern pattern, String title, String explanation, FixAction fix) {
    }

    private static final List<Rule> RULES = new ArrayList<>();

    private static void rule(String id, String regex, String title, String explanation, FixAction fix) {
        RULES.add(new Rule(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), title, explanation, fix));
    }

    static {
        rule("getsockopt", "Connection timed out: getsockopt|Connection refused: getsockopt|io\\.netty.*getsockopt",
                "Il client non raggiunge il server (getsockopt timeout)",
                "Il server Minecraft è in ascolto solo sull'IP Tailscale (server-ip in server.properties), ma il "
                        + "client prova a collegarsi a un altro indirizzo (es. 127.0.0.1). Su quella porta non "
                        + "risponde nessuno e la connessione scade.",
                FixAction.ALIGN_CLIENT_IP);
        rule("jar-bat", "Invalid or corrupt jarfile .*\\.(bat|sh|cmd)|Impossibile accedere al file jar .*\\.(bat|sh)",
                "Script passato a java come se fosse un .jar",
                "Nel comando di avvio c'è 'java -jar run.bat': run.bat è uno script, non un archivio Java. "
                        + "Gli script vanno eseguiti direttamente, senza 'java -jar'.",
                FixAction.FIX_RUN_SCRIPT);
        rule("jar-missing", "Unable to access jarfile|Error: Invalid or corrupt jarfile",
                "File .jar del server mancante o corrotto",
                "Il file .jar indicato in serverStartCommand non esiste nella cartella del server o è danneggiato "
                        + "(spesso succede quando il template Fabric non corrisponde a un modpack Forge).",
                FixAction.DETECT_SERVER_COMMAND);
        rule("java8", "(Could not find or load main class|impossibile trovare o caricare la classe principale) @"
                        + "|UnsupportedClassVersionError|class file version 6[1-9]|Unrecognized option: --add-opens",
                "Versione di Java troppo vecchia per il server",
                "Il server è stato avviato con Java 8 (quello trovato nel PATH di Windows). La sintassi "
                        + "@user_jvm_args.txt esiste solo da Java 9 e Forge 1.18+ richiede Java 17 o superiore.",
                FixAction.USE_JAVA21_FOR_SERVER);
        rule("port", "FAILED TO BIND TO PORT|Address already in use|already in use: is another Minecraft|BindException",
                "Porta 25565 già in uso",
                "Un altro server Minecraft (o un processo rimasto appeso da una sessione precedente) occupa già la "
                        + "porta. Chiudi l'altro server o riavvia il PC; il launcher termina da solo i server orfani "
                        + "che aveva avviato lui.",
                FixAction.NONE);
        rule("token", "Authentication failed|could not read Username|Repository not found|HTTP 401|HTTP 403"
                        + "|Bad credentials|Invalid username or token|Impossibile accedere alla repository Git",
                "Token GitHub non valido o senza permessi",
                "GitHub ha rifiutato l'accesso alla repository privata: il token è scaduto, sbagliato o non ha il "
                        + "permesso 'Contents: Read and write' (o 'repo').",
                FixAction.OPEN_CONFIG);
        rule("stale", "STALE LOCK|Gave up waiting for host|Somebody is hosting right now|Found a lock left by",
                "Sessione bloccata (lock rimasto attivo)",
                "Il lock del mondo condiviso risulta ancora preso, di solito dopo un crash o uno spegnimento "
                        + "improvviso dell'host. Se sei sicuro che nessuno stia giocando puoi sbloccarlo.",
                FixAction.FORCE_UNLOCK);
        rule("tailnet", "no matching peer|reports ONLINE but .* is not reachable",
                "L'host è online ma il suo IP Tailscale non è raggiungibile",
                "L'host aggiorna il lock su GitHub ma i due PC non sono nella stessa Tailnet (account Tailscale "
                        + "diversi senza 'Node Sharing'), oppure il firewall di Windows blocca la porta 25565.",
                FixAction.OPEN_TAILSCALE_ADMIN);
        rule("eula", "You need to agree to the EULA|EULA is not accepted",
                "EULA di Minecraft non accettata",
                "Il server si rifiuta di partire finché eula.txt non contiene eula=true.",
                FixAction.ACCEPT_EULA);
        rule("oom", "java\\.lang\\.OutOfMemoryError",
                "Memoria RAM del server esaurita",
                "Il server ha finito la RAM assegnata (-Xmx). Aumenta la RAM massima nella scheda Configurazione.",
                FixAction.OPEN_CONFIG);
        rule("instance", "clientInstanceDirectory does not exist",
                "Cartella dell'istanza client inesistente",
                "Il percorso clientInstanceDirectory non esiste. Prism Launcher portable salva le istanze in "
                        + ".tools/PrismLauncher/instances/<nome>/minecraft (senza il punto iniziale).",
                FixAction.FIX_CLIENT_INSTANCE);
        rule("prism", "Cannot start the Minecraft client|points to the folder .* instead of the launcher executable",
                "Percorso di Prism Launcher errato",
                "clientLaunchCommand deve puntare all'eseguibile prismlauncher.exe, non alla sua cartella.",
                FixAction.FIX_PRISM_PATH);
        rule("mods", "Mod/config mismatch|Registry Mismatch|Mismatched mod list|mods rejected by server",
                "Mod diverse da quelle dell'host",
                "Le mod installate sul tuo client non coincidono con quelle del server.",
                FixAction.PULL_MODS);
        rule("tailscale-ip", "Cannot detect the Tailscale IP|is not assigned to any network interface",
                "Tailscale non connesso",
                "Il PC non ha un indirizzo Tailscale (100.x.y.z): apri l'app Tailscale ed effettua l'accesso.",
                FixAction.OPEN_TAILSCALE_ADMIN);
        rule("not-ready", "The server did not become ready",
                "Il server non è riuscito ad avviarsi",
                "Il processo del server si è chiuso o non ha completato l'avvio. Controlla le righe [server] "
                        + "precedenti: di solito la causa è Java sbagliato, il comando di avvio o una mod incompatibile.",
                FixAction.DETECT_SERVER_COMMAND);
        rule("curseforge-key", "serve una API key CurseForge|MCSYNC_CURSEFORGE_API_KEY",
                "API key CurseForge mancante",
                "I modpack CurseForge con file non inclusi in overrides/ richiedono una API key CurseForge in "
                        + "config.json (\"curseforgeApiKey\") oppure l'importazione tramite istanza Prism / .mrpack.",
                FixAction.OPEN_CONFIG);
        rule("lock-conflict", "modpack è stato modificato da qualcun altro|lockSha256 diverso",
                "Conflitto o disallineamento sul lockfile del modpack",
                "Il file mods.lock.json nel repository è cambiato rispetto alla versione attesa. Aggiorna lo stato "
                        + "del mondo e riprova.",
                FixAction.PULL_MODS);
        rule("world-busy", "sta ospitando il mondo: riprova a fine sessione",
                "Aggiornamento modpack rifiutato: mondo in uso",
                "Non è possibile aggiornare il modpack mentre un altro giocatore sta ospitando il mondo. Attendi la "
                        + "fine della sessione.",
                FixAction.NONE);
    }

    private LogAnalyzer() {
    }

    /** Analyses the given lines and returns one diagnosis per recognised problem (most recent evidence). */
    public static List<Diagnosis> analyze(List<String> lines) {
        return analyze(lines, null);
    }

    /** World-aware log analysis: prefixes titles with the world name when provided. */
    public static List<Diagnosis> analyze(List<String> lines, String worldName) {
        String prefix = (worldName != null && !worldName.isBlank()) ? "[" + worldName + "] " : "";
        Map<String, Diagnosis> found = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            for (Rule r : RULES) {
                if (r.pattern().matcher(line).find()) {
                    found.remove(r.id());
                    found.put(r.id(), new Diagnosis(r.id(), prefix + r.title(), r.explanation(), line.strip(), r.fix()));
                }
            }
        }
        // "jar-bat" is more specific than "jar-missing"
        if (found.containsKey("jar-bat")) found.remove("jar-missing");
        return new ArrayList<>(found.values());
    }

    public static List<Diagnosis> analyze(String text) {
        return analyze(text == null ? List.of() : text.lines().toList(), null);
    }

    public static List<Diagnosis> analyze(String text, String worldName) {
        return analyze(text == null ? List.of() : text.lines().toList(), worldName);
    }

    /** Single-line check used by the live log stream. */
    public static Diagnosis match(String line) {
        List<Diagnosis> d = analyze(List.of(line));
        return d.isEmpty() ? null : d.get(0);
    }

    /** Plain text rendering for the CLI. */
    public static String render(Diagnosis d) {
        String nl = System.lineSeparator();
        return "[DIAGNOSI] " + d.title() + nl + "  " + d.explanation()
                + (d.fix() != FixAction.NONE ? nl + "  Soluzione: " + d.fix().label.toLowerCase(Locale.ROOT)
                + " (pulsante nella scheda 'Log & AI' della GUI)" : "");
    }
}
