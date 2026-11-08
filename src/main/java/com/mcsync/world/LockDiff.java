package com.mcsync.world;

import com.mcsync.model.ModsLock;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human readable difference between two locks: added, removed, version changes, platform changes. */
public record LockDiff(List<String> added, List<String> removed, List<String> updated, List<String> other) {

    private static final Pattern NAME = Pattern.compile("^([A-Za-z][A-Za-z_+]*(?:-[A-Za-z][A-Za-z_+]*)*)");

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && updated.isEmpty() && other.isEmpty();
    }

    public static LockDiff of(ModsLock before, ModsLock after) {
        Map<String, ModsLock.Entry> a = before.files();
        Map<String, ModsLock.Entry> b = after.files();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> other = new ArrayList<>();

        Map<String, String> removedByName = new TreeMap<>();
        for (String path : a.keySet()) {
            if (!b.containsKey(path)) removedByName.put(modName(path), path);
        }
        for (Map.Entry<String, ModsLock.Entry> e : b.entrySet()) {
            ModsLock.Entry old = a.get(e.getKey());
            if (old == null) {
                String gone = removedByName.remove(modName(e.getKey()));
                if (gone != null) updated.add(file(gone) + "  ->  " + file(e.getKey()));
                else added.add(file(e.getKey()) + " [" + e.getValue().side() + "]");
            } else if (!old.sha256().equalsIgnoreCase(e.getValue().sha256())) {
                updated.add(file(e.getKey()) + " (contenuto cambiato)");
            } else if (!old.side().equals(e.getValue().side())) {
                other.add(file(e.getKey()) + ": lato " + old.side() + " -> " + e.getValue().side());
            }
        }
        removedByName.values().forEach(p -> removed.add(file(p)));
        if (!Objects.equals(before.minecraft(), after.minecraft())) {
            other.add("Minecraft " + before.minecraft() + " -> " + after.minecraft());
        }
        if (!Objects.equals(before.loader(), after.loader())) {
            other.add("Loader " + label(before.loader()) + " -> " + label(after.loader()));
        }
        if (!before.pinnedConfigs().equals(after.pinnedConfigs())) other.add("config bloccate modificate");
        if (!before.serverFiles().equals(after.serverFiles())) other.add("file server modificati");
        if (!before.overridesBundles().equals(after.overridesBundles())) other.add("bundle overrides modificati");
        Collections.sort(added);
        Collections.sort(removed);
        Collections.sort(updated);
        return new LockDiff(added, removed, updated, other);
    }

    private static String label(ModsLock.Loader l) {
        return l == null ? "-" : l.type() + " " + l.version();
    }

    private static String file(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** {@code create-1.20.1-0.5.1.jar} -> {@code create}: same mod, other version. */
    static String modName(String path) {
        Matcher m = NAME.matcher(file(path).toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) : file(path).toLowerCase(Locale.ROOT);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        added.forEach(s -> sb.append("  [+] ").append(s).append(nl));
        removed.forEach(s -> sb.append("  [-] ").append(s).append(nl));
        updated.forEach(s -> sb.append("  [~] ").append(s).append(nl));
        other.forEach(s -> sb.append("  [*] ").append(s).append(nl));
        if (isEmpty()) sb.append("  (nessuna differenza)").append(nl);
        return sb.toString();
    }
}
