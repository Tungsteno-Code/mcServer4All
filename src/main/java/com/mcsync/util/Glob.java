package com.mcsync.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal, OS-independent glob matcher on forward-slash relative paths
 * ({@code *} = any chars except '/', {@code **} = anything, {@code ?} = one char). Case-insensitive,
 * because Windows file systems are.
 */
public final class Glob {

    private final List<Pattern> patterns;

    public Glob(List<String> globs) {
        this.patterns = globs.stream().map(Glob::compile).toList();
    }

    public boolean matches(String portablePath) {
        for (Pattern p : patterns) {
            if (p.matcher(portablePath).matches()) return true;
        }
        return false;
    }

    static Pattern compile(String glob) {
        String g = glob.trim().replace('\\', '/');
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                    if (i + 1 < g.length() && g.charAt(i + 1) == '/') i++; // "**/" also matches zero dirs
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
