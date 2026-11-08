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

    private final Config cfg;
    private final Path repoDir;
    private final String branch;
    private final String prefix;
    private final String gitExe;
    private String lastRemoteSha;
    private long lastSyncTimeMillis = 0;
    private static final long SYNC_DEBOUNCE_MS = 15_000L;

    public GitStorage(Config cfg, String gitExecutable) {
        this.cfg = cfg;
        this.repoDir = cfg.sharedGitRepoDir();
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

    private synchronized void ensureRepoInitialised() throws IOException {
        Files.createDirectories(repoDir);
        if (!Files.isDirectory(repoDir.resolve(".git"))) {
            runGit(List.of("init"), true);
            runGit(List.of("config", "user.name", cfg.playerId()), true);
            runGit(List.of("config", "user.email", cfg.playerId() + "@mcsync.local"), true);
            runGit(List.of("config", "core.autocrlf", "false"), true);
            runGit(List.of("config", "gc.auto", "0"), true);
            runGit(List.of("config", "http.postBuffer", "524288000"), true);
        }
        String url = remoteUrlWithAuth();
        CmdResult rem = runGit(List.of("remote"), false);
        if (rem.stdout.lines().anyMatch(l -> l.trim().equals("origin"))) {
            runGit(List.of("remote", "set-url", "origin", url), true);
        } else {
            runGit(List.of("remote", "add", "origin", url), true);
        }
    }

    /** Pulls the latest single-commit state from the remote branch using differential pack transfer. */
    private synchronized void syncFromRemote() throws IOException {
        syncFromRemote(false);
    }

    private synchronized void syncFromRemote(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && lastRemoteSha != null && (now - lastSyncTimeMillis) < SYNC_DEBOUNCE_MS
                && Files.isDirectory(repoDir.resolve(".git"))) {
            return;
        }
        ensureRepoInitialised();
        CmdResult ls = runGit(List.of("ls-remote", "--heads", "origin", branch), true);
        String remoteSha = parseLsRemoteSha(ls.stdout);
        if (remoteSha == null) {
            if (lastRemoteSha != null || !Files.exists(repoDir.resolve(".git/HEAD"))) {
                runGit(List.of("checkout", "--orphan", branch), false);
                clearWorkingTree();
            }
            lastRemoteSha = "";
            lastSyncTimeMillis = System.currentTimeMillis();
            return;
        }
        if (remoteSha.equals(lastRemoteSha) && Files.isDirectory(repoDir.resolve(".git"))) {
            lastSyncTimeMillis = System.currentTimeMillis();
            return;
        }
        // Keep existing refs during fetch so Git negotiates shared blobs ('have') and downloads ONLY changed blobs!
        runGit(List.of("fetch", "--depth=1", "--no-tags", "origin",
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch), true);
        runGit(List.of("checkout", "-f", "-B", branch, "refs/remotes/origin/" + branch), true);
        runGit(List.of("reset", "--hard", "refs/remotes/origin/" + branch), true);
        runGit(List.of("clean", "-fd"), true);
        // Drop reflogs & prune superseded blobs so local .git never accumulates old session data
        runGit(List.of("reflog", "expire", "--expire=now", "--expire-unreachable=now", "--all"), false);
        runGit(List.of("prune", "--expire=now"), false);
        lastRemoteSha = remoteSha;
        lastSyncTimeMillis = System.currentTimeMillis();
    }

    /**
     * Creates a single orphan commit with the current working tree and pushes it atomically
     * using {@code --force-with-lease} against {@code expectedSha}.
     */
    private synchronized Optional<String> commitAndPushAtomic(String message, String expectedSha) throws IOException {
        runGit(List.of("add", "-A"), true);
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
                lastRemoteSha = null;
                return Optional.empty();
            }
            throw new IOException("Git push failed: " + sanitizeError(push.stdout));
        }
        runGit(List.of("update-ref", "refs/remotes/origin/" + branch, newCommitSha), false);
        runGit(List.of("reflog", "expire", "--expire=now", "--expire-unreachable=now", "--all"), false);
        runGit(List.of("prune", "--expire=now"), false);
        lastRemoteSha = newCommitSha;
        return Optional.of(newCommitSha);
    }

    /**
     * Stages multiple file additions/updates, deletes obsolete keys, writes {@code manifest.json}
     * and pushes everything in a <b>single atomic Git commit</b>!
     */
    public synchronized void commitSessionDeltaBatch(
            Map<String, Path> filesToStage,
            List<String> keysToDelete,
            String manifestKey,
            byte[] manifestBytes,
            String commitMessage) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            syncFromRemote(true);
            String baseSha = lastRemoteSha;
            for (String delKey : keysToDelete) {
                FileUtils.deleteWithRetry(resolvePath(delKey));
            }
            for (Map.Entry<String, Path> e : filesToStage.entrySet()) {
                Path dst = resolvePath(e.getKey());
                Files.createDirectories(dst.getParent());
                Files.copy(e.getValue(), dst, StandardCopyOption.REPLACE_EXISTING);
            }
            Path mf = resolvePath(manifestKey);
            Files.createDirectories(mf.getParent());
            Files.write(mf, manifestBytes);
            if (commitAndPushAtomic(commitMessage, baseSha).isPresent()) {
                lastSyncTimeMillis = System.currentTimeMillis();
                return;
            }
        }
        throw new IOException("Impossibile inviare il delta della sessione su Git a causa di modifiche concorrenti.");
    }

    /** Reads a file directly from the already synced local Git working tree without re-fetching if already present. */
    public synchronized Path localFile(String key) throws IOException {
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

    public synchronized Optional<S3Storage.Fetched> get(String key) {
        try {
            syncFromRemote(true);
            Path p = resolvePath(key);
            if (!Files.isRegularFile(p)) return Optional.empty();
            String eTag = FileUtils.sha256(p);
            return Optional.of(new S3Storage.Fetched(Files.readAllBytes(p), eTag));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized String put(String key, byte[] data, String contentType) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                syncFromRemote(true);
                String baseSha = lastRemoteSha;
                Path p = resolvePath(key);
                Files.createDirectories(p.getParent());
                Files.write(p, data);
                Optional<String> pushed = commitAndPushAtomic("mcsync: update " + key, baseSha);
                if (pushed.isPresent()) {
                    lastSyncTimeMillis = System.currentTimeMillis();
                    return FileUtils.sha256(p);
                }
            }
            throw new IOException("Concurrent modifications on Git branch '" + branch + "' prevented saving " + key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<String> putIfAbsent(String key, byte[] data, String contentType) {
        try {
            syncFromRemote(true);
            String baseSha = lastRemoteSha;
            Path p = resolvePath(key);
            if (Files.exists(p)) {
                return Optional.empty();
            }
            Files.createDirectories(p.getParent());
            Files.write(p, data);
            Optional<String> pushed = commitAndPushAtomic("mcsync: lock acquire " + key, baseSha);
            if (pushed.isEmpty()) return Optional.empty();
            lastSyncTimeMillis = System.currentTimeMillis();
            return Optional.of(FileUtils.sha256(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<String> putIfMatch(String key, byte[] data, String contentType, String eTag) {
        try {
            syncFromRemote(true);
            String baseSha = lastRemoteSha;
            Path p = resolvePath(key);
            if (!Files.isRegularFile(p)) return Optional.empty();
            String currentTag = FileUtils.sha256(p);
            if (eTag != null && !eTag.equalsIgnoreCase(currentTag)) {
                return Optional.empty();
            }
            Files.write(p, data);
            Optional<String> pushed = commitAndPushAtomic("mcsync: heartbeat " + key, baseSha);
            if (pushed.isEmpty()) return Optional.empty();
            lastSyncTimeMillis = System.currentTimeMillis();
            return Optional.of(FileUtils.sha256(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void delete(String key) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                syncFromRemote(true);
                String baseSha = lastRemoteSha;
                Path p = resolvePath(key);
                if (!Files.exists(p)) return;
                FileUtils.deleteWithRetry(p);
                if (commitAndPushAtomic("mcsync: delete " + key, baseSha).isPresent()) {
                    lastSyncTimeMillis = System.currentTimeMillis();
                    return;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized boolean exists(String key) {
        try {
            Path p = resolvePath(key);
            if (!key.endsWith("lock.json") && Files.isRegularFile(p)) {
                return true;
            }
            syncFromRemote(key.endsWith("lock.json"));
            return Files.isRegularFile(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void uploadFile(String key, Path file, String contentType) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            syncFromRemote(true);
            String baseSha = lastRemoteSha;
            Path p = resolvePath(key);
            Files.createDirectories(p.getParent());
            Files.copy(file, p, StandardCopyOption.REPLACE_EXISTING);
            if (commitAndPushAtomic("mcsync: upload " + key, baseSha).isPresent()) {
                lastSyncTimeMillis = System.currentTimeMillis();
                return;
            }
        }
        throw new IOException("Could not push " + key + " to Git repository after retries");
    }

    public synchronized void downloadFile(String key, Path target) throws IOException {
        Path p = localFile(key);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void copy(String sourceKey, String destinationKey) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                syncFromRemote(true);
                String baseSha = lastRemoteSha;
                Path src = resolvePath(sourceKey);
                Path dst = resolvePath(destinationKey);
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                if (commitAndPushAtomic("mcsync: copy " + sourceKey + " -> " + destinationKey, baseSha).isPresent()) {
                    lastSyncTimeMillis = System.currentTimeMillis();
                    return;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<S3Storage.Entry> list(String keyPrefix) {
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

    public synchronized void deleteAll(List<String> keys) {
        if (keys.isEmpty()) return;
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                syncFromRemote(true);
                String baseSha = lastRemoteSha;
                for (String k : keys) {
                    FileUtils.deleteWithRetry(resolvePath(k));
                }
                if (commitAndPushAtomic("mcsync: prune " + keys.size() + " files", baseSha).isPresent()) {
                    lastSyncTimeMillis = System.currentTimeMillis();
                    return;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void checkAccess() {
        try {
            ensureRepoInitialised();
            runGit(List.of("ls-remote", "origin", "HEAD"), true);
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile accedere alla repository Git privata ("
                    + cfg.gitRemoteUrl() + "): " + e.getMessage()
                    + ". Verifica gitRemoteUrl e gitToken in config.json.", e);
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
