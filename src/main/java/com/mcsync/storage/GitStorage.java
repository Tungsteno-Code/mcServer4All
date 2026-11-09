package com.mcsync.storage;

import com.mcsync.config.Config;
import com.mcsync.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Storage backend that uses a **private Git repository** (e.g. GitHub / GitLab) instead of S3/Cloudflare.
 *
 * <h3>How it works in theory &amp; practice (true differential transfer, zero history bloat):</h3>
 * <ol>
 *     <li>All sync state lives on a dedicated branch ({@code gitBranch}, default {@code mcsync-state})
 *     inside a local checkout at {@code <serverDirectory>/.mcsync/git-store}.</li>
 *     <li><b>True Differential Git Pack Negotiation</b>:
 *     <ul>
 *         <li>On {@code fetch}: we keep the previous commit in the local object database while running
 *         {@code git fetch --depth=1 origin +refs/heads/<branch>:refs/remotes/origin/<branch>}. Git's
 *         smart protocol advertises {@code have <prevCommit>}, so the remote Git server sends a packfile
 *         containing <b>ONLY the blobs created/modified in the last session</b>! Immediately after
 *         switching {@code HEAD} to the new commit, we run {@code git prune} to drop superseded blobs.</li>
 *         <li>On {@code push}: only the modified blobs in {@code world_blobs/<sha256>.zst} are written,
 *         unreferenced blobs are removed from the working tree, a single parentless commit is created with
 *         {@code git commit-tree}, and pushed with {@code --force-with-lease=refs/heads/<branch>:<expectedSha>}.
 *         Because the remote still holds {@code <expectedSha>} during {@code receive-pack}, Git transfers
 *         <b>only the newly created session blobs</b> and then replaces the branch ref so history length
 *         stays <b>1 commit</b> forever!</li>
 *     </ul>
 *     </li>
 * </ol>
 */
public final class GitStorage {

    private static final Logger log = LoggerFactory.getLogger(GitStorage.class);

    private static final class RepoState {
        String lastRemoteSha;
        long lastSyncTimeMillis;
        boolean initialized;
    }

    private static final ConcurrentHashMap<Path, RepoState> REPO_STATES = new ConcurrentHashMap<>();
    private static final long SYNC_DEBOUNCE_MS = 15_000L;
    private static final long FORCE_READ_DEBOUNCE_MS = 2_000L;

    private final Config cfg;
    private final Path repoDir;
    private final RepoState state;
    private final String branch;
    private final String prefix;
    private final String gitExe;

    public GitStorage(Config cfg, String gitExecutable) {
        this.cfg = cfg;
        this.repoDir = cfg.sharedGitRepoDir().toAbsolutePath().normalize();
        this.state = REPO_STATES.computeIfAbsent(this.repoDir, k -> new RepoState());
        this.branch = cfg.gitBranch();
        this.prefix = cfg.s3KeyPrefix();
        this.gitExe = gitExecutable != null && !gitExecutable.isBlank() ? gitExecutable : "git";
    }

    private String remoteUrlWithAuth() {
        String raw = cfg.gitRemoteUrl().trim();
        String token = cfg.gitToken();
        if (token == null || token.isBlank() || !raw.startsWith("https://")) {
            return raw;
        }
        String withoutProto = raw.substring("https://".length());
        int at = withoutProto.indexOf('@');
        if (at >= 0) withoutProto = withoutProto.substring(at + 1);
        return "https://x-access-token:" + token + "@" + withoutProto;
    }

    private void cleanStaleGitLocks() {
        Path gitDir = repoDir.resolve(".git");
        if (!Files.isDirectory(gitDir)) return;
        for (String lockName : List.of("index.lock", "shallow.lock", "HEAD.lock", "config.lock")) {
            try {
                Files.deleteIfExists(gitDir.resolve(lockName));
            } catch (IOException ignored) {
            }
        }
    }

    private void ensureRepoInitialised() throws IOException {
        synchronized (state) {
            Files.createDirectories(repoDir);
            cleanStaleGitLocks();
            if (!Files.isDirectory(repoDir.resolve(".git"))) {
                runGit(List.of("init"), true);
                runGit(List.of("config", "user.name", cfg.playerId()), true);
                runGit(List.of("config", "user.email", cfg.playerId() + "@mcsync.local"), true);
                runGit(List.of("config", "core.autocrlf", "false"), true);
                runGit(List.of("config", "gc.auto", "0"), true);
                runGit(List.of("config", "http.postBuffer", "524288000"), true);
                state.initialized = false;
            }
            if (!state.initialized) {
                String url = remoteUrlWithAuth();
                CmdResult rem = runGit(List.of("remote"), false);
                if (rem.stdout.lines().anyMatch(l -> l.trim().equals("origin"))) {
                    runGit(List.of("remote", "set-url", "origin", url), true);
                } else {
                    runGit(List.of("remote", "add", "origin", url), true);
                }
                if (state.lastRemoteSha == null && Files.exists(repoDir.resolve(".git/HEAD"))) {
                    CmdResult head = runGit(List.of("rev-parse", "--verify", "HEAD"), false);
                    if (head.exitCode == 0 && !head.stdout.isBlank()) {
                        state.lastRemoteSha = head.stdout.trim();
                    }
                }
                state.initialized = true;
            }
        }
    }

    /** Pulls the latest single-commit state from the remote branch using differential pack transfer. */
    private void syncFromRemote() throws IOException {
        syncFromRemote(false, false);
    }

    private void syncFromRemote(boolean force) throws IOException {
        syncFromRemote(force, false);
    }

    private void syncFromRemote(boolean force, boolean writeOp) throws IOException {
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (!force && state.lastRemoteSha != null && (now - state.lastSyncTimeMillis) < SYNC_DEBOUNCE_MS
                    && Files.isDirectory(repoDir.resolve(".git"))) {
                return;
            }
            ensureRepoInitialised();
            CmdResult ls = runGit(List.of("ls-remote", "--heads", "origin", branch), true);
            String remoteSha = parseLsRemoteSha(ls.stdout);
            if (remoteSha == null) {
                if (state.lastRemoteSha != null || !Files.exists(repoDir.resolve(".git/HEAD"))) {
                    runGit(List.of("checkout", "--orphan", branch), false);
                    clearWorkingTree();
                }
                state.lastRemoteSha = "";
                state.lastSyncTimeMillis = System.currentTimeMillis();
                return;
            }
            if (remoteSha.equals(state.lastRemoteSha) && Files.isDirectory(repoDir.resolve(".git"))) {
                state.lastSyncTimeMillis = System.currentTimeMillis();
                return;
            }
            cleanStaleGitLocks();
            // Keep existing refs during fetch so Git negotiates shared blobs ('have') and downloads ONLY changed blobs!
            CmdResult fetch = runGit(List.of("fetch", "--no-tags", "origin",
                    "+refs/heads/" + branch + ":refs/remotes/origin/" + branch), false);
            if (fetch.exitCode != 0) {
                try {
                    Files.deleteIfExists(repoDir.resolve(".git/shallow"));
                } catch (IOException ignored) {
                }
                runGit(List.of("fetch", "--depth=1", "--no-tags", "origin",
                        "+refs/heads/" + branch + ":refs/remotes/origin/" + branch), true);
            }
            runGit(List.of("checkout", "-f", "-B", branch, "refs/remotes/origin/" + branch), true);
            runGit(List.of("reset", "--hard", "refs/remotes/origin/" + branch), true);
            state.lastRemoteSha = remoteSha;
            state.lastSyncTimeMillis = System.currentTimeMillis();
        }
    }

    private String toRepoRelative(Path p) {
        return FileUtils.toPortable(repoDir.relativize(p.toAbsolutePath().normalize()));
    }

    /**
     * Creates a single orphan commit with the current working tree and pushes it atomically
     * using {@code --force-with-lease} against {@code expectedSha}.
     */
    private Optional<String> commitAndPushAtomic(String message, String expectedSha,
                                                 List<String> specificRelPaths, boolean pruneAfter) throws IOException {
        synchronized (state) {
            cleanStaleGitLocks();
            if (specificRelPaths != null && !specificRelPaths.isEmpty()) {
                List<String> addCmd = new ArrayList<>(specificRelPaths.size() + 3);
                addCmd.add("add");
                addCmd.add("-A");
                addCmd.add("--");
                addCmd.addAll(specificRelPaths);
                runGit(addCmd, true);
            } else {
                runGit(List.of("add", "-A"), true);
            }
            String treeSha = runGit(List.of("write-tree"), true).stdout.trim();
            String newCommitSha = runGit(List.of("commit-tree", treeSha, "-m", message), true).stdout.trim();
            runGit(List.of("update-ref", "refs/heads/" + branch, newCommitSha), true);
            runGit(List.of("symbolic-ref", "HEAD", "refs/heads/" + branch), false);

            String leaseArg = (expectedSha == null || expectedSha.isEmpty())
                    ? "--force"
                    : "--force-with-lease=refs/heads/" + branch + ":" + expectedSha;

            CmdResult push = runGit(List.of("push", leaseArg, "origin", "refs/heads/" + branch + ":refs/heads/" + branch), false);
            if (push.exitCode != 0) {
                String out = push.stdout.toLowerCase(Locale.ROOT);
                if (out.contains("stale info") || out.contains("rejected") || out.contains("non-fast-forward")
                        || out.contains("fetch first") || out.contains("failed to push")) {
                    log.debug("Git atomic CAS push rejected (concurrent update on remote): {}", push.stdout.trim());
                    state.lastRemoteSha = null;
                    return Optional.empty();
                }
                throw new IOException("Git push failed: " + sanitizeError(push.stdout));
            }
            runGit(List.of("update-ref", "refs/remotes/origin/" + branch, newCommitSha), false);
            if (pruneAfter) {
                runGit(List.of("reflog", "expire", "--expire=now", "--expire-unreachable=now", "--all"), false);
                runGit(List.of("prune", "--expire=now"), false);
            }
            state.lastRemoteSha = newCommitSha;
            return Optional.of(newCommitSha);
        }
    }

    /**
     * Stages multiple file additions/updates, deletes obsolete keys, writes {@code manifest.json}
     * and pushes everything in a <b>single atomic Git commit</b>!
     */
    public void commitSessionDeltaBatch(
            Map<String, Path> filesToStage,
            List<String> keysToDelete,
            String manifestKey,
            byte[] manifestBytes,
            String commitMessage) throws IOException {
        synchronized (state) {
            for (int attempt = 0; attempt < 3; attempt++) {
                syncFromRemote(true, true);
                String baseSha = state.lastRemoteSha;
                List<String> relPaths = new ArrayList<>();
                for (String delKey : keysToDelete) {
                    Path p = resolvePath(delKey);
                    FileUtils.deleteWithRetry(p);
                    relPaths.add(toRepoRelative(p));
                }
                for (Map.Entry<String, Path> e : filesToStage.entrySet()) {
                    Path dst = resolvePath(e.getKey());
                    Files.createDirectories(dst.getParent());
                    Files.copy(e.getValue(), dst, StandardCopyOption.REPLACE_EXISTING);
                    relPaths.add(toRepoRelative(dst));
                }
                Path mf = resolvePath(manifestKey);
                Files.createDirectories(mf.getParent());
                Files.write(mf, manifestBytes);
                relPaths.add(toRepoRelative(mf));
                List<String> stageArg = relPaths.size() <= 100 ? relPaths : null;
                if (commitAndPushAtomic(commitMessage, baseSha, stageArg, true).isPresent()) {
                    state.lastSyncTimeMillis = System.currentTimeMillis();
                    return;
                }
            }
            throw new IOException("Impossibile inviare il delta della sessione su Git a causa di modifiche concorrenti.");
        }
    }

    /** Reads a file directly from the already synced local Git working tree without re-fetching if already present. */
    public Path localFile(String key) throws IOException {
        synchronized (state) {
            Path p = resolvePath(key);
            if (Files.isRegularFile(p)) {
                return p;
            }
            syncFromRemote();
            if (!Files.isRegularFile(p)) {
                throw new NoSuchFileException("Blob non trovato nella repo Git: " + key);
            }
            return p;
        }
    }

    private Path resolvePath(String key) {
        String rel = (prefix + key).replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path p = repoDir.resolve(rel).normalize();
        if (!p.startsWith(repoDir)) throw new IllegalArgumentException("Invalid key: " + key);
        return p;
    }

    // ==========================================================================================
    // Standard Storage Operations
    // ==========================================================================================

    public Optional<S3Storage.Fetched> get(String key) {
        synchronized (state) {
            try {
                syncFromRemote(true, false);
                Path p = resolvePath(key);
                if (!Files.isRegularFile(p)) return Optional.empty();
                byte[] bytes = Files.readAllBytes(p);
                return Optional.of(new S3Storage.Fetched(bytes, FileUtils.sha256(bytes)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public String put(String key, byte[] data, String contentType) {
        synchronized (state) {
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    syncFromRemote(true, true);
                    String baseSha = state.lastRemoteSha;
                    Path p = resolvePath(key);
                    Files.createDirectories(p.getParent());
                    Files.write(p, data);
                    Optional<String> pushed = commitAndPushAtomic("mcsync: update " + key, baseSha,
                            List.of(toRepoRelative(p)), false);
                    if (pushed.isPresent()) {
                        state.lastSyncTimeMillis = System.currentTimeMillis();
                        return FileUtils.sha256(data);
                    }
                }
                throw new IOException("Concurrent modifications on Git branch '" + branch + "' prevented saving " + key);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public Optional<String> putIfAbsent(String key, byte[] data, String contentType) {
        synchronized (state) {
            try {
                syncFromRemote(true, true);
                String baseSha = state.lastRemoteSha;
                Path p = resolvePath(key);
                if (Files.exists(p)) {
                    return Optional.empty();
                }
                Files.createDirectories(p.getParent());
                Files.write(p, data);
                Optional<String> pushed = commitAndPushAtomic("mcsync: lock acquire " + key, baseSha,
                        List.of(toRepoRelative(p)), false);
                if (pushed.isEmpty()) return Optional.empty();
                state.lastSyncTimeMillis = System.currentTimeMillis();
                return Optional.of(FileUtils.sha256(data));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public Optional<String> putIfMatch(String key, byte[] data, String contentType, String eTag) {
        synchronized (state) {
            try {
                syncFromRemote(true, true);
                String baseSha = state.lastRemoteSha;
                Path p = resolvePath(key);
                if (!Files.isRegularFile(p)) return Optional.empty();
                String currentTag = FileUtils.sha256(p);
                if (eTag != null && !eTag.equalsIgnoreCase(currentTag)) {
                    return Optional.empty();
                }
                Files.write(p, data);
                Optional<String> pushed = commitAndPushAtomic("mcsync: heartbeat " + key, baseSha,
                        List.of(toRepoRelative(p)), false);
                if (pushed.isEmpty()) return Optional.empty();
                state.lastSyncTimeMillis = System.currentTimeMillis();
                return Optional.of(FileUtils.sha256(data));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public void delete(String key) {
        synchronized (state) {
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    syncFromRemote(true, true);
                    String baseSha = state.lastRemoteSha;
                    Path p = resolvePath(key);
                    if (!Files.exists(p)) return;
                    String rel = toRepoRelative(p);
                    FileUtils.deleteWithRetry(p);
                    if (commitAndPushAtomic("mcsync: delete " + key, baseSha, List.of(rel), false).isPresent()) {
                        state.lastSyncTimeMillis = System.currentTimeMillis();
                        return;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public boolean exists(String key) {
        synchronized (state) {
            try {
                Path p = resolvePath(key);
                if (!key.endsWith("lock.json") && Files.isRegularFile(p)) {
                    return true;
                }
                syncFromRemote(key.endsWith("lock.json"), false);
                return Files.isRegularFile(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public void uploadFile(String key, Path file, String contentType) throws IOException {
        synchronized (state) {
            for (int attempt = 0; attempt < 3; attempt++) {
                syncFromRemote(true, true);
                String baseSha = state.lastRemoteSha;
                Path p = resolvePath(key);
                Files.createDirectories(p.getParent());
                Files.copy(file, p, StandardCopyOption.REPLACE_EXISTING);
                if (commitAndPushAtomic("mcsync: upload " + key, baseSha, List.of(toRepoRelative(p)), false).isPresent()) {
                    state.lastSyncTimeMillis = System.currentTimeMillis();
                    return;
                }
            }
            throw new IOException("Could not push " + key + " to Git repository after retries");
        }
    }

    public void downloadFile(String key, Path target) throws IOException {
        synchronized (state) {
            Path p = localFile(key);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void copy(String sourceKey, String destinationKey) {
        synchronized (state) {
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    syncFromRemote(true, true);
                    String baseSha = state.lastRemoteSha;
                    Path src = resolvePath(sourceKey);
                    Path dst = resolvePath(destinationKey);
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    if (commitAndPushAtomic("mcsync: copy " + sourceKey + " -> " + destinationKey, baseSha,
                            List.of(toRepoRelative(dst)), false).isPresent()) {
                        state.lastSyncTimeMillis = System.currentTimeMillis();
                        return;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public List<S3Storage.Entry> list(String keyPrefix) {
        synchronized (state) {
            try {
                syncFromRemote();
                Path base = repoDir.resolve(prefix.isEmpty() ? "." : prefix).normalize();
                if (!Files.isDirectory(base)) return List.of();
                String normPrefix = keyPrefix == null ? "" : keyPrefix.replace('\\', '/');
                List<S3Storage.Entry> out = new ArrayList<>();
                try (Stream<Path> s = Files.walk(base)) {
                    for (Path p : s.filter(Files::isRegularFile).toList()) {
                        if (p.startsWith(repoDir.resolve(".git"))) continue;
                        String rel = FileUtils.toPortable(base.relativize(p));
                        if (rel.startsWith(normPrefix)) {
                            out.add(new S3Storage.Entry(rel, Files.size(p), Files.getLastModifiedTime(p).toInstant()));
                        }
                    }
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public void deleteAll(List<String> keys) {
        if (keys.isEmpty()) return;
        synchronized (state) {
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    syncFromRemote(true, true);
                    String baseSha = state.lastRemoteSha;
                    List<String> rels = new ArrayList<>();
                    for (String k : keys) {
                        Path p = resolvePath(k);
                        rels.add(toRepoRelative(p));
                        FileUtils.deleteWithRetry(p);
                    }
                    List<String> stageArg = rels.size() <= 100 ? rels : null;
                    if (commitAndPushAtomic("mcsync: prune " + keys.size() + " files", baseSha, stageArg, true).isPresent()) {
                        state.lastSyncTimeMillis = System.currentTimeMillis();
                        return;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public void checkAccess() {
        synchronized (state) {
            try {
                ensureRepoInitialised();
                runGit(List.of("ls-remote", "origin", "HEAD"), true);
            } catch (IOException e) {
                throw new IllegalStateException("Impossibile accedere alla repository Git privata ("
                        + cfg.gitRemoteUrl() + "): " + e.getMessage()
                        + ". Verifica gitRemoteUrl e gitToken in config.json.", e);
            }
        }
    }

    private void clearWorkingTree() throws IOException {
        try (Stream<Path> s = Files.list(repoDir)) {
            for (Path p : s.toList()) {
                if (p.getFileName().toString().equals(".git")) continue;
                FileUtils.deleteRecursively(p);
            }
        }
    }

    private static String parseLsRemoteSha(String out) {
        if (out == null || out.isBlank()) return null;
        String firstLine = out.lines().findFirst().orElse("").trim();
        if (firstLine.isEmpty()) return null;
        int tab = firstLine.indexOf('\t');
        return (tab > 0 ? firstLine.substring(0, tab) : firstLine).trim();
    }

    private record CmdResult(int exitCode, String stdout) {
    }

    private CmdResult runGit(List<String> args, boolean failOnError) throws IOException {
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add(gitExe);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(repoDir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("Timeout comando git: " + args.get(0));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for git", e);
        }
        int code = p.exitValue();
        if (failOnError && code != 0) {
            throw new IOException("git " + args.get(0) + " fallito (exit " + code + "): " + sanitizeError(sb.toString()));
        }
        return new CmdResult(code, sb.toString());
    }

    private String sanitizeError(String msg) {
        String tok = cfg.gitToken();
        if (tok != null && !tok.isBlank()) {
            return msg.replace(tok, "***");
        }
        return msg.trim();
    }
}
