package com.mcsync.util;

import java.io.IOException;
import java.io.OutputStream;

/** Duplicates console output into a log file (the file side never breaks the console side). */
public final class TeeOutputStream extends OutputStream {

    private final OutputStream primary;
    private final OutputStream secondary;
    private volatile boolean secondaryOk = true;

    public TeeOutputStream(OutputStream primary, OutputStream secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        primary.write(b);
        if (secondaryOk) {
            try {
                secondary.write(b);
            } catch (IOException e) {
                secondaryOk = false;
            }
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        primary.write(b, off, len);
        if (secondaryOk) {
            try {
                secondary.write(b, off, len);
            } catch (IOException e) {
                secondaryOk = false;
            }
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        primary.flush();
        if (secondaryOk) {
            try {
                secondary.flush();
            } catch (IOException e) {
                secondaryOk = false;
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        flush();
        try {
            secondary.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
