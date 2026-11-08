package com.mcsync.gui;

import com.mcsync.MainOrchestrator;
import com.mcsync.OrchestratorState;
import com.mcsync.core.LockManager;
import com.mcsync.gui.controller.OrchestratorController;
import com.mcsync.model.LockFile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Set;

/**
 * Finestra principale di {@code mcServer4all} (Paradigma v3.0 Single-JVM &amp; Pack-Native):
 * <ol>
 *     <li><b>Mondi &amp; Avvio</b> (Vista unificata con Hero Button animato, barra progresso 60 FPS e Console a scomparsa)</li>
 *     <li><b>Impostazioni</b> (4 sole impostazioni essenziali: Nickname, RAM, Git, CurseForge API Key)</li>
 *     <li><b>Diagnostica</b> (Log Analyzer e auto-remediation a 1 clic)</li>
 * </ol>
 */
public final class MainFrame extends JFrame {

    private final OrchestratorController controller;
    private final JTabbedPane tabs = new JTabbedPane();
    private final WorldsPanel worldsPanel;
    private final ConfigEditorPanel configPanel;
    private final DiagnosticsPanel diagnosticsPanel;
    private final JLabel lblStatusBar = new JLabel("Pronto.");

    private final UiAnimations.PulsingBadge badgeTailscale =
            new UiAnimations.PulsingBadge("Tailscale P2P", "Rilevamento...");
    private final UiAnimations.PulsingBadge badgeLock =
            new UiAnimations.PulsingBadge("Stato Lock", "Verifica...");
    private final UiAnimations.PulsingBadge badgePlayer =
            new UiAnimations.PulsingBadge("Giocatore", "-");

    public MainFrame(OrchestratorController controller) {
        super("mcServer4all v" + MainOrchestrator.VERSION + " — Single-JVM Modded World Orchestrator");
        this.controller = controller;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(1080, 760);
        setMinimumSize(new Dimension(920, 640));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        this.worldsPanel = new WorldsPanel(controller, this);
        this.configPanel = new ConfigEditorPanel(controller);
        this.diagnosticsPanel = new DiagnosticsPanel(controller, this);

        add(buildHeaderBar(), BorderLayout.NORTH);

        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 13.5f));
        tabs.addTab("  🌍 Mondi & Avvio  ", worldsPanel);
        tabs.addTab("  ⚙ Impostazioni  ", configPanel);
        tabs.addTab("  🩺 Diagnostica  ", diagnosticsPanel);
        add(tabs, BorderLayout.CENTER);

        add(buildStatusBar(), BorderLayout.SOUTH);

        controller.addListener(new OrchestratorController.UiListener() {
            @Override
            public void onDashboardUpdated(OrchestratorController.DashboardData data) {
                updateHeaderBadges(data);
                worldsPanel.updateDashboardContext(data);
                worldsPanel.refreshWorlds();
            }

            @Override
            public void onStateChanged(OrchestratorState state) {
                worldsPanel.onOrchestratorState(state);
                lblStatusBar.setText("Stato Orchestrator: " + state);
                if (state == OrchestratorState.HOSTING || state == OrchestratorState.DAEMON_HOSTING) {
                    badgeLock.setBadge("SESSIONE HOST ATTIVA", new Color(0x3FB950));
                } else if (state == OrchestratorState.CLIENT_MODE) {
                    badgeLock.setBadge("CONNESSO COME CLIENT", new Color(0x58A6FF));
                }
            }

            @Override
            public void onProgressUpdated(int percent, String detail, boolean active) {
                worldsPanel.onProgressUpdated(percent, detail, active);
            }

            @Override
            public void onServerConsoleLine(String line) {
                worldsPanel.appendConsoleLine(line);
            }

            @Override
            public void onPlayersChanged(Set<String> players) {
                worldsPanel.updatePlayers(players);
            }

            @Override
            public void onSessionFinished(int exitCode) {
                worldsPanel.onSessionFinished(exitCode);
                if (exitCode != 0) {
                    diagnosticsPanel.refreshDiagnoses();
                }
            }

            @Override
            public void onStatusMessage(String message, boolean isError) {
                lblStatusBar.setForeground(isError ? new Color(0xF85149) : new Color(0x8B949E));
                lblStatusBar.setText(message);
                worldsPanel.onStatusToast(message, isError);
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClose();
            }
        });
    }

    private JPanel buildHeaderBar() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(new Color(0x161B22));
        header.setBorder(new EmptyBorder(10, 20, 10, 20));

        JLabel title = new JLabel("mcServer4all v" + MainOrchestrator.VERSION
                + "  —  Single-JVM Modded World Orchestrator");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setForeground(new Color(0xE6EDF3));

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightBox.setOpaque(false);
        rightBox.add(badgePlayer);
        rightBox.add(badgeTailscale);
        rightBox.add(badgeLock);

        JButton btnTheme = new JButton("Tema");
        btnTheme.setToolTipText("Cambia tema Chiaro / Scuro");
        btnTheme.addActionListener(e -> GuiLauncher.toggleTheme(this));
        rightBox.add(btnTheme);

        header.add(title, BorderLayout.WEST);
        header.add(rightBox, BorderLayout.EAST);
        return header;
    }

    private void updateHeaderBadges(OrchestratorController.DashboardData data) {
        if (data.config() != null) {
            badgePlayer.setBadge(data.config().playerId(), new Color(0xA371F7));
        }
        String ts = data.tailscaleIp();
        if (ts != null && !ts.isBlank()) {
            badgeTailscale.setBadge(ts, new Color(0x3FB950));
        } else {
            badgeTailscale.setBadge("Offline", new Color(0xF85149));
        }
        if (data.lock() != null) {
            LockManager.Inspection insp = data.lock();
            LockFile lock = insp.lock();
            switch (insp.state()) {
                case FREE -> badgeLock.setBadge("LIBERO", new Color(0x3FB950));
                case HELD -> badgeLock.setBadge("ONLINE: " + (lock != null ? lock.ownerId() : "Host"), new Color(0x58A6FF));
                case STALE, OWN_LEFTOVER -> badgeLock.setBadge("RECUPERABILE", new Color(0xD29922));
            }
        } else if (data.remoteError() != null || data.configError() != null) {
            badgeLock.setBadge("Da configurare", new Color(0xD29922));
        }
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(0x161B22));
        bar.setBorder(new EmptyBorder(6, 18, 6, 18));
        lblStatusBar.setForeground(new Color(0x8B949E));
        bar.add(lblStatusBar, BorderLayout.CENTER);
        return bar;
    }

    public void switchToTab(int idx) {
        if (idx >= 0 && idx < tabs.getTabCount()) {
            tabs.setSelectedIndex(idx);
        }
    }

    public void selectMonitorTabOnHost() {
        worldsPanel.appendConsoleLine("=== Avvio nuova sessione mcServer4all (Single-JVM / P2P) ===");
        worldsPanel.expandConsoleDrawer();
    }

    public void reloadConfigTab() {
        configPanel.loadFromDisk();
    }

    public void highlightDiagnosticsTab() {
        int idx = tabs.indexOfComponent(diagnosticsPanel);
        if (idx >= 0) {
            tabs.setTitleAt(idx, "  ⚠ Diagnostica  ");
        }
    }

    public void switchToWorldsTab() {
        int idx = tabs.indexOfComponent(worldsPanel);
        if (idx >= 0) tabs.setSelectedIndex(idx);
        worldsPanel.refreshWorlds();
    }

    private void handleWindowClose() {
        if (controller.isSessionRunning()) {
            int ans = JOptionPane.showConfirmDialog(this,
                    "Una sessione è attualmente attiva.\nVuoi fermare il mondo, sincronizzare i salvataggi sul cloud e uscire?",
                    "Sessione in corso", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ans == JOptionPane.YES_OPTION) {
                controller.requestStopSession();
            }
            return;
        }
        dispose();
        System.exit(0);
    }
}
