package com.mcsync.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Network helpers: TCP liveness probe and Tailscale IP discovery. */
public final class NetUtils {

    private static final Logger log = LoggerFactory.getLogger(NetUtils.class);

    private NetUtils() {
    }

    /** Raw TCP probe: true if something accepts connections on host:port within the timeout. */
    public static boolean tcpProbe(String host, int port, int timeoutMillis) {
        if (host == null || host.isBlank()) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True if the address is in the Tailscale CGNAT range 100.64.0.0/10. */
    public static boolean isTailscaleAddress(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        byte[] b = a.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    /**
     * Detects the local Tailscale IPv4: first through the network interfaces, then through the
     * {@code tailscale ip -4} CLI (also looking in the default Windows install folder).
     */
    public static String detectTailscaleIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (isTailscaleAddress(a)) {
                        log.debug("Tailscale address {} found on interface {}", a.getHostAddress(), ni.getDisplayName());
                        return a.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("Interface enumeration failed: {}", e.getMessage());
        }

        List<String> candidates = new ArrayList<>();
        candidates.add("tailscale");
        if (FileUtils.IS_WINDOWS) {
            String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            candidates.add(Path.of(pf, "Tailscale", "tailscale.exe").toString());
        } else {
            candidates.add("/Applications/Tailscale.app/Contents/MacOS/Tailscale");
        }
        for (String exe : candidates) {
            if (!exe.equals("tailscale") && !Files.isRegularFile(Path.of(exe))) continue;
            try {
                Process p = new ProcessBuilder(exe, "ip", "-4").redirectErrorStream(true).start();
                String out;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    out = r.readLine();
                }
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && out != null
                        && out.trim().matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    return out.trim();
                }
            } catch (IOException e) {
                log.debug("'{}' not usable: {}", exe, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }
}
