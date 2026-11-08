package com.mcsync.gui;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Captures everything written to {@code System.out}/{@code System.err} (slf4j-simple logs, server
 * echo, installer output), keeps the last lines in memory and fans them out to GUI listeners.
 */
public final class LogBus {

    private static final int MAX_LINES = 8000;
    private static final Deque<String> BUFFER = new ArrayDeque<>();
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean installed;

    private LogBus() {
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Charset cs = StandardCharsets.UTF_8;
        System.setOut(new PrintStream(new LineSplitter(System.out, cs), true, cs));
        System.setErr(new PrintStream(new LineSplitter(System.err, cs), true, cs));
    }

    public static void addListener(Consumer<String> l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Consumer<String> l) {
        LISTENERS.remove(l);
    }

    public static List<String> snapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }

    static void publish(String line) {
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > MAX_LINES) BUFFER.removeFirst();
        }
        for (Consumer<String> l : LISTENERS) {
            try {
                l.accept(line);
            } catch (RuntimeException ignored) {
                // a broken listener must never break logging
            }
        }
    }

    /** Forwards bytes to the original stream and publishes complete lines. */
    private static final class LineSplitter extends OutputStream {
        private final OutputStream delegate;
        private final Charset cs;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);

        LineSplitter(OutputStream delegate, Charset cs) {
            this.delegate = delegate;
            this.cs = cs;
        }

        @Override
        public synchronized void write(int b) {
            try {
                delegate.write(b);
            } catch (Exception ignored) {
                // console may be gone (javaw)
            }
            if (b == '\n') flushLine();
            else if (b != '\r') line.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            try {
                delegate.write(b, off, len);
            } catch (Exception ignored) {
                // console may be gone (javaw)
            }
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') flushLine();
                else if (b[i] != '\r') line.write(b[i]);
            }
        }

        @Override
        public void flush() {
            try {
                delegate.flush();
            } catch (Exception ignored) {
                // ignore
            }
        }

        private void flushLine() {
            String s = line.toString(cs);
            line.reset();
            publish(s);
        }
    }
}
