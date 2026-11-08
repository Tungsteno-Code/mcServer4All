package com.mcsync.gui;

import com.mcsync.config.Config;
import com.mcsync.core.ServerProcessController;
import com.mcsync.diagnostics.ConfigFixer;
import com.mcsync.gui.controller.OrchestratorController;
import com.mcsync.gui.controller.ResourceMonitor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Schermata 2: Pannello di Monitoraggio Server & Risorse (Live Dashboard).
 *
 * <ul>
 *     <li>Barre e contatori RAM (Usata / Allocata / Picco) e CPU del processo server</li>
 *     <li>Lista giocatori online e conto alla rovescia autospegnimento in DAEMON_HOSTING</li>
 *     <li>Console interattiva con evidenziazione sintattica (INFO/WARN/ERROR/JOIN) e barra comandi</li>
 * </ul>
 */
public final class ServerMonitorPanel extends JPanel {

    private final OrchestratorController controller;

    // Resource widgets
    private final JProgressBar barRam = new JProgressBar(0, 100);
    private final JLabel lblRamDetail = new JLabel("RAM Server: non attivo");
    private final JProgressBar barCpu = new JProgressBar(0, 100);
    private final JLabel lblCpuDetail = new JLabel("CPU Server: 0%");
    private final JLabel lblUptime = new JLabel("Uptime: -");
    private final JLabel lblIdleCountdown = new JLabel("Autospegnimento: -");

    // Players list
    private final DefaultListModel<String> playersModel = new DefaultListModel<>();
    private final JList<String> playersList = new JList<>(playersModel);
    private final JLabel lblPlayersHeader = new JLabel("GIOCATORI ONLINE (0)");

    // Console
    private final JTextPane consolePane = new JTextPane();
    private final JTextField txtCommand = new JTextField();
    private final JButton btnSend = new JButton("Invia Comando");
    private final JButton btnSaveAll = new JButton("/save-all");
    private final JButton btnList = new JButton("/list");
    private final JButton btnStopServer = new JButton("Stop & Sync");

    private ResourceMonitor resourceMonitor;
    private ProcessHandle trackedHandle;
    private Instant lastPlayerSeenAt;

    public ServerMonitorPanel(OrchestratorController controller) {
        super(new BorderLayout(14, 14));
        this.controller = controller;
        setBorder(new EmptyBorder(16, 20, 16, 20));
        setOpaque(false);

        add(buildTopMetricsRow(), BorderLayout.NORTH);
        add(buildCenterSplit(), BorderLayout.CENTER);
        add(buildCommandBar(), BorderLayout.SOUTH);

        setupConsoleStyles();
        startPeriodicSampler();
    }

    private JPanel buildTopMetricsRow() {
        JPanel row = new JPanel(new GridLayout(1, 2, 14, 0));
        row.setOpaque(false);

        JPanel resCard = DashboardPanel.createCard("RISORSE PROCESSO SERVER (TEMPO REALE)");
        JPanel resGrid = new JPanel(new GridLayout(2, 2, 12, 8));
        resGrid.setOpaque(false);
        barRam.setStringPainted(true);
        barCpu.setStringPainted(true);
        resGrid.add(lblRamDetail);
        resGrid.add(barRam);
        resGrid.add(lblCpuDetail);
        resGrid.add(barCpu);
        resCard.add(resGrid, BorderLayout.CENTER);

        JPanel stateCard = DashboardPanel.createCard("STATO SESSIONE & AUTOSPEGNIMENTO");
        JPanel stateGrid = new JPanel(new GridLayout(2, 1, 0, 6));
        stateGrid.setOpaque(false);
        lblUptime.setFont(lblUptime.getFont().deriveFont(Font.BOLD, 13f));
        lblIdleCountdown.setFont(lblIdleCountdown.getFont().deriveFont(13f));
        stateGrid.add(lblUptime);
        stateGrid.add(lblIdleCountdown);
        stateCard.add(stateGrid, BorderLayout.CENTER);

        row.add(resCard);
        row.add(stateCard);
        return row;
    }

    private JSplitPane buildCenterSplit() {
        // Left: Online players
        JPanel playersCard = new JPanel(new BorderLayout(0, 8));
        playersCard.setBackground(new Color(0x21252B));
        playersCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x30363D), 1, true),
                new EmptyBorder(12, 12, 12, 12)));
        lblPlayersHeader.setFont(lblPlayersHeader.getFont().deriveFont(Font.BOLD, 12f));
        lblPlayersHeader.setForeground(new Color(0x8B949E));
        playersCard.add(lblPlayersHeader, BorderLayout.NORTH);

        playersList.setBackground(new Color(0x181A1F));
        playersList.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        JScrollPane pScroll = new JScrollPane(playersList);
        pScroll.setBorder(BorderFactory.createEmptyBorder());
        playersCard.add(pScroll, BorderLayout.CENTER);
        playersCard.setPreferredSize(new Dimension(210, 260));

        // Right: Live Console
        JPanel consoleCard = DashboardPanel.createCard("CONSOLE INTERATTIVA DEL SERVER");
        consolePane.setEditable(false);
        consolePane.setBackground(new Color(0x121417));
        consolePane.setForeground(new Color(0xE6EDF3));
        consolePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane cScroll = new JScrollPane(consolePane);
        cScroll.setBorder(BorderFactory.createLineBorder(new Color(0x30363D)));
        consoleCard.add(cScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, playersCard, consoleCard);
        split.setDividerLocation(220);
        split.setOpaque(false);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JPanel buildCommandBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);

        txtCommand.putClientProperty("JTextField.placeholderText",
                "Digita un comando Minecraft (es. /op <player>, /whitelist add <player>, /time set day, stop)...");
        txtCommand.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        txtCommand.addActionListener(e -> sendTypedCommand());
        btnSend.addActionListener(e -> sendTypedCommand());

        btnSaveAll.addActionListener(e -> controller.sendServerCommand("save-all"));
        btnList.addActionListener(e -> controller.sendServerCommand("list"));
        btnStopServer.setBackground(new Color(0xDA3633));
        btnStopServer.setForeground(Color.WHITE);
        btnStopServer.addActionListener(e -> controller.requestStopSession());

        JPanel quick = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        quick.setOpaque(false);
        quick.add(btnSend);
        quick.add(btnList);
        quick.add(btnSaveAll);
        quick.add(btnStopServer);

        bar.add(txtCommand, BorderLayout.CENTER);
        bar.add(quick, BorderLayout.EAST);
        return bar;
    }

    private void sendTypedCommand() {
        String cmd = txtCommand.getText().trim();
        if (cmd.isEmpty()) return;
        appendConsoleLine("> " + cmd);
        if (!controller.sendServerCommand(cmd)) {
            appendConsoleLine("[WARN] Nessun server locale in esecuzione a cui inviare il comando.");
        }
        txtCommand.setText("");
    }

    private void setupConsoleStyles() {
        StyledDocument doc = consolePane.getStyledDocument();
        addStyle(doc, "INFO", new Color(0xD0D7DE), false);
        addStyle(doc, "WARN", new Color(0xE3B341), false);
        addStyle(doc, "ERROR", new Color(0xFF7B72), true);
        addStyle(doc, "JOIN", new Color(0x56D364), true);
        addStyle(doc, "CMD", new Color(0x79C0FF), true);
    }

    private static void addStyle(StyledDocument doc, String name, Color fg, boolean bold) {
        Style s = doc.addStyle(name, null);
        StyleConstants.setForeground(s, fg);
        StyleConstants.setBold(s, bold);
    }

    public void appendConsoleLine(String line) {
        StyledDocument doc = consolePane.getStyledDocument();
        String style = classifyLine(line);
        try {
            if (doc.getLength() > 250_000) {
                doc.remove(0, 60_000);
            }
            doc.insertString(doc.getLength(), line + "\n", doc.getStyle(style));
            consolePane.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
        }
    }

    private static String classifyLine(String line) {
        if (line.startsWith(">")) return "CMD";
        String u = line.toUpperCase();
        if (u.contains("ERROR") || u.contains("FATAL") || u.contains("EXCEPTION") || u.contains("STDERR")) {
            return "ERROR";
        }
        if (u.contains("WARN")) return "WARN";
        if (line.contains("joined the game") || line.contains("left the game") || line.contains("Done (")) {
            return "JOIN";
        }
        return "INFO";
    }

    public void updatePlayers(Set<String> players) {
        playersModel.clear();
        players.stream().sorted().forEach(playersModel::addElement);
        lblPlayersHeader.setText("GIOCATORI ONLINE (" + players.size() + ")");
        if (!players.isEmpty()) {
            lastPlayerSeenAt = Instant.now();
        } else if (lastPlayerSeenAt == null) {
            lastPlayerSeenAt = Instant.now();
        }
    }

    private void startPeriodicSampler() {
        Timer t = new Timer(1500, e -> sampleResources());
        t.start();
    }

    private void sampleResources() {
        ServerProcessController srv = controller.activeServerController();
        if (srv == null || !srv.isRunning()) {
            barRam.setValue(0);
            barRam.setString("Offline");
            lblRamDetail.setText("RAM Server: non attivo");
            barCpu.setValue(0);
            barCpu.setString("0%");
            lblCpuDetail.setText("CPU Server: 0%");
            lblUptime.setText(controller.isSessionRunning() ? "Sessione attiva (Client / Sync)" : "Server spento");
            lblIdleCountdown.setText("Autospegnimento: -");
            return;
        }

        Optional<ProcessHandle> ph = srv.processHandle();
        if (ph.isPresent() && !ph.get().equals(trackedHandle)) {
            trackedHandle = ph.get();
            resourceMonitor = new ResourceMonitor(trackedHandle);
            lastPlayerSeenAt = Instant.now();
        }

        Config cfg = controller.currentConfig();
        int maxGb = cfg != null ? ConfigFixer.readRam(cfg)[1] : 6;
        long allocatedMb = maxGb * 1024L;

        if (resourceMonitor != null) {
            resourceMonitor.sample().ifPresent(s -> {
                int pct = allocatedMb > 0 ? (int) Math.min(100, (s.ramUsedMb() * 100) / allocatedMb) : 0;
                barRam.setValue(pct);
                barRam.setString(s.ramUsedMb() + " / " + allocatedMb + " MB");
                lblRamDetail.setText("RAM: " + s.ramUsedMb() + " MB usati (Picco: " + resourceMonitor.peakMb() + " MB)");

                int cpuPct = (int) Math.round(s.cpuPercent());
                barCpu.setValue(cpuPct);
                barCpu.setString(String.format("%.1f%%", s.cpuPercent()));
                lblCpuDetail.setText(String.format("CPU Processo: %.1f%%", s.cpuPercent()));
            });
        }

        updatePlayers(srv.onlinePlayers());

        if (srv.readyAt() != null) {
            Duration up = Duration.between(srv.readyAt(), Instant.now());
            lblUptime.setText(String.format("Server ONLINE (%s) • Uptime: %02d:%02d:%02d",
                    srv.mode(), up.toHours(), up.toMinutesPart(), up.toSecondsPart()));
        } else {
            lblUptime.setText("Avvio del server Minecraft in corso...");
        }

        if (srv.mode() == ServerProcessController.HostMode.DAEMON_HOSTING && srv.onlinePlayers().isEmpty()
                && lastPlayerSeenAt != null && cfg != null) {
            long elapsed = Duration.between(lastPlayerSeenAt, Instant.now()).getSeconds();
            long remaining = Math.max(0, cfg.idleShutdownSeconds() - elapsed);
            lblIdleCountdown.setText("Nessun giocatore online: autospegnimento e sync tra " + remaining + "s");
        } else {
            lblIdleCountdown.setText("Autospegnimento: in attesa (giocatori attivi o client host aperto)");
        }
    }
}
