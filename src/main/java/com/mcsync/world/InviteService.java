package com.mcsync.world;

import com.mcsync.config.Config;
import com.mcsync.model.Invite;
import com.mcsync.model.ModsLock;
import com.mcsync.model.WorldProfile;
import com.mcsync.model.WorldRecipe;
import com.mcsync.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Builds, writes, reads and validates {@code .mcsync} invite files. */
public final class InviteService {

    private InviteService() {
    }

    /** The storage section describing where this world lives (global config or per-world override). */
    public static Invite.Storage storageOf(Config base, WorldProfile w) {
        if (w.storage() != null) return w.storage();
        if (base.isGitBackend()) {
            return new Invite.Storage("GIT", base.gitRemoteUrl(), base.gitBranch(), "worlds/" + w.id(),
                    base.gitToken(), base.s3KeyPrefix(), null, null, null, null, null, null);
        }
        return new Invite.Storage("S3", null, null, "worlds/" + w.id(), null, base.s3KeyPrefix(),
                base.s3Endpoint(), base.s3Region(), base.s3Bucket(), base.s3AccessKey(), base.s3SecretKey(),
                base.s3PathStyle());
    }

    public static Invite build(Config base, WorldProfile w, WorldRecipe recipe, WorldStore.LockRef lock, String launcherVersion) {
        ModsLock l = lock.lock();
        String cfKey = base.curseforgeApiKey() == null || base.curseforgeApiKey().isBlank() ? null : base.curseforgeApiKey();
        return new Invite(Invite.SCHEMA_VERSION, w.id(), w.displayName(), recipe.minecraft(), recipe.loader(),
                storageOf(base, w), l.modpackVersion(), lock.sha256(), base.playerId(), launcherVersion, cfKey);
    }

    /** Writes the invite; on POSIX systems the file is readable by the owner only (it holds a token). */
    public static void write(Invite invite, Path file) throws IOException {
        Path abs = file.toAbsolutePath();
        if (abs.getParent() != null) Files.createDirectories(abs.getParent());
        Files.write(abs, Json.toBytes(invite));
        try {
            Files.setPosixFilePermissions(abs, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: ACLs of the user profile apply
        }
    }

    public static Invite read(Path file, String launcherVersion) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("File d'invito non trovato: " + file);
        Invite inv;
        try {
            inv = Json.fromBytes(Files.readAllBytes(file), Invite.class);
        } catch (IOException e) {
            throw new IOException("File d'invito non valido (JSON malformato): " + e.getMessage(), e);
        }
        validate(inv, launcherVersion);
        return inv;
    }

    public static void validate(Invite inv, String launcherVersion) throws IOException {
        if (inv.schemaVersion() != Invite.SCHEMA_VERSION) {
            throw new IOException("Versione dell'invito non supportata (schemaVersion=" + inv.schemaVersion()
                    + "): aggiorna il launcher");
        }
        if (!WorldProfile.isValidId(inv.worldId())) throw new IOException("worldId non valido nell'invito: " + inv.worldId());
        if (inv.storage() == null || inv.storage().backend() == null) throw new IOException("Invito senza sezione storage");
        Invite.Storage st = inv.storage();
        switch (st.backend().toUpperCase(java.util.Locale.ROOT)) {
            case "GIT" -> {
                if (st.remoteUrl() == null || st.remoteUrl().isBlank() || st.remoteUrl().startsWith("-")
                        || !(st.remoteUrl().startsWith("https://") || st.remoteUrl().startsWith("file:"))) {
                    throw new IOException("L'invito deve indicare un remoteUrl https:// valido");
                }
            }
            case "S3" -> {
                if (st.s3Endpoint() == null || st.s3Bucket() == null) throw new IOException("Invito S3 incompleto");
            }
            default -> throw new IOException("Backend non supportato nell'invito: " + st.backend());
        }
        if (inv.minLauncherVersion() != null && newer(inv.minLauncherVersion(), launcherVersion)) {
            throw new IOException("Questo invito richiede il launcher " + inv.minLauncherVersion() + " o successivo (hai "
                    + launcherVersion + ")");
        }
    }

    /** True if version {@code a} is strictly newer than {@code b}; non numeric versions never block. */
    static boolean newer(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null) return false;
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? x[i] : 0;
            int yi = i < y.length ? y[i] : 0;
            if (xi != yi) return xi > yi;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return null;
        String[] parts = v.trim().split("[.-]");
        int[] out = new int[Math.min(parts.length, 3)];
        try {
            for (int i = 0; i < out.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** True if the invite points at the same repo/bucket as the global config. */
    public static boolean sameRepo(Config base, Invite.Storage st) {
        if (!st.backend().equalsIgnoreCase(base.storageBackend())) return false;
        if (st.backend().equalsIgnoreCase("GIT")) {
            return norm(st.remoteUrl()).equals(norm(base.gitRemoteUrl()))
                    && java.util.Objects.equals(blank(st.branch(), "mcsync-state"), blank(base.gitBranch(), "mcsync-state"))
                    && norm(st.prefix()).equals(norm(base.s3KeyPrefix()));
        }
        return norm(st.s3Endpoint()).equals(norm(base.s3Endpoint())) && norm(st.s3Bucket()).equals(norm(base.s3Bucket()))
                && norm(st.prefix()).equals(norm(base.s3KeyPrefix()));
    }

    private static String blank(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static String norm(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".git")) t = t.substring(0, t.length() - 4);
        return t.toLowerCase(java.util.Locale.ROOT);
    }
}
