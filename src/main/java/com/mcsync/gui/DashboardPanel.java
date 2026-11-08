package com.mcsync.gui;

import com.mcsync.OrchestratorState;
import com.mcsync.config.Config;
import com.mcsync.core.LockManager;
import com.mcsync.gui.controller.OrchestratorController;
import com.mcsync.model.LockFile;
import com.mcsync.model.WorldManifest;
import com.mcsync.setup.ServerProfileDetector;
import com.mcsync.util.FileUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Schermata 1: Dashboard Principale (Connessione & Stato).
 *
 * <ul>
 *     <li>Semafori visivi per Rete Tailscale, Lock Git/S3 e Modpack & File</li>
 *     <li>Riepilogo stato mondo condiviso e ruolo</li>
 *     <li>Pulsante unico intelligente ("Avvia come Host" / "Entra nel Server" / "Ferma Server")</li>
 *     <li>Banner per installazione Server Pack pendente o importazione `.zip` CurseForge</li>
 * </ul>
 */
public final class DashboardPanel extends JPanel {

    private static final Color GREEN = new Color(0x3FB950);
    private static final Color BLUE = new Color(0x58A6FF);
    private static final Color ORANGE = new Color(0xF0883E);
    private static final Color YELLOW = new Color(0xD29922);
    private static final Color RED = new Color(0xF85149);
    private static final Color GRAY = new Color(0x8B949E);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    private final OrchestratorController controller;
    private final MainFrame mainFrame;

    // Traffic light cards
    private final StatusLightCard tailscaleCard = new StatusLightCard("Rete Tailscale");
    private final StatusLightCard lockCard = new StatusLightCard("Lock Mondo Condiviso");
    private final StatusLightCard modsCard = new StatusLightCard("Modpack & File");

    // World & role summary labels
    private final JLabel lblLockState = valueLabel("Verifica in corso...");
    private final JLabel lblWorldVer = valueLabel("-");
    private final JLabel lblPlayer = valueLabel("-");
    private final JLabel lblRole = valueLabel("In attesa...");
    private final JLabel lblTailscaleIp = valueLabel("-");
    private final JLabel lblModpack = valueLabel("-");
    private final JLabel lblServerProfile = valueLabel("-");
    private final JLabel lblOrchestratorState = valueLabel("IDLE");

    // Action buttons
    private final JButton btnStart = new JButton("AVVIA SESSIONE (HOST / JOIN AUTOMATICO)");
    private final JCheckBox chkLaunchClient = new JCheckBox("Avvia anche il client Minecraft", true);
    private final JButton btnSyncMods = new JButton("Sincronizza Mod");
    private final JButton btnPushMods = new JButton("Pubblica Mod");
    private final JButton btnWorlds = new JButton("Mondi & Inviti (.mcsync)");
    private final JButton btnImportZip = new JButton("Importa .zip CurseForge");
    private final JButton btnRefresh = new JButton("Aggiorna Stato");
    private final JButton btnInstallPack = new JButton("Esegui Installazione Server Pack");
    private final JPanel bannerPanel = new JPanel(new BorderLayout(12, 0));
    private final JLabel lblBanner = new JLabel();

    private volatile Path pendingInstaller;

    public DashboardPanel(OrchestratorController controller, MainFrame mainFrame) {
        super(new BorderLayout(16, 16));
        this.controller = controller;
        this.mainFrame = mainFrame;
        setBorder(new EmptyBorder(18, 22, 18, 22));
        setOpaque(false);

        // Top: 3 traffic light cards
        JPanel lightsRow = new JPanel(new GridLayout(1, 3, 14, 0));
        lightsRow.setOpaque(false);
        lightsRow.add(tailscaleCard);
        lightsRow.add(lockCard);
        lightsRow.add(modsCard);
        add(lightsRow, BorderLayout.NORTH);

        // Center: World state boxes + alert banner
        JPanel center = new JPanel(new BorderLayout(0, 14));
        center.setOpaque(false);

        bannerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ORANGE, 1, true),
                new EmptyBorder(10, 14, 10, 14)));
        bannerPanel.setBackground(new Color(0x2D2115));
        lblBanner.setForeground(new Color(0xFFD8A8));
        bannerPanel.add(lblBanner, BorderLayout.CENTER);
        bannerPanel.add(btnInstallPack, BorderLayout.EAST);
        bannerPanel.setVisible(false);
        center.add(bannerPanel, BorderLayout.NORTH);

        JPanel cardsGrid = new JPanel(new GridLayout(1, 2, 16, 0));
        cardsGrid.setOpaque(false);
        cardsGrid.add(buildLeftInfoCard());
        cardsGrid.add(buildRightInfoCard());
        center.add(cardsGrid, BorderLayout.CENTER);

        add(center, BorderLayout.CENTER);

        // Bottom: Smart Launch Button + Quick utilities
        add(buildActionsBar(), BorderLayout.SOUTH);

        wireActions();
    }

    private JPanel buildLeftInfoCard() {
        JPanel card = createCard("STATO DEL MONDO CONDIVISO");
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        addRow(grid, 0, "Lock Remoto:", lblLockState);
        addRow(grid, 1, "Versione Mondo:", lblWorldVer);
        addRow(grid, 2, "Giocatore Locale:", lblPlayer);
        addRow(grid, 3, "Stato Orchestrator:", lblOrchestratorState);
        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildRightInfoCard() {
        JPanel card = createCard("AMBIENTE & MODPACK");
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        addRow(grid, 0, "Ruolo Previsto:", lblRole);
        addRow(grid, 1, "Tailscale IP:", lblTailscaleIp);
        addRow(grid, 2, "Modpack Client:", lblModpack);
        addRow(grid, 3, "Profilo Server:", lblServerProfile);
        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildActionsBar() {
        JPanel bottom = new JPanel(new BorderLayout(12, 10));
        bottom.setOpaque(false);

        btnStart.setFont(btnStart.getFont().deriveFont(Font.BOLD, 16f));
        btnStart.setPreferredSize(new Dimension(380, 52));
        btnStart.setBackground( new Color(0x238636));
        btnStart.setForeground(Color.WHITE);
        btnStart.setFocusPainted(false);

        JPanel primaryRow = new JPanel(new BorderLayout(14, 0));
        primaryRow.setOpaque(false);
        primaryRow.add(btnStart, BorderLayout.CENTER);

        chkLaunchClient.setOpaque(false);
        primaryRow.add(chkLaunchClient, BorderLayout.EAST);

        JPanel secondaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        secondaryRow.setOpaque(false);
        secondaryRow.add(btnWorlds);
        secondaryRow.add(btnSyncMods);
        secondaryRow.add(btnPushMods);
        secondaryRow.add(btnImportZip);
        secondaryRow.add(btnRefresh);

        bottom.add(primaryRow, BorderLayout.NORTH);
        bottom.add(secondaryRow, BorderLayout.SOUTH);
        return bottom;
    }

    private void wireActions() {
        btnStart.addActionListener(e -> {
            if (controller.isSessionRunning()) {
                controller.requestStopSession();
            } else {
                mainFrame.selectMonitorTabOnHost();
                controller.startSession(chkLaunchClient.isSelected());
            }
        });

        btnRefresh.addActionListener(e -> {
            btnRefresh.setEnabled(false);
            controller.refreshDashboardAsync();
        });

        btnWorlds.addActionListener(e -> mainFrame.switchToWorldsTab());
        btnSyncMods.addActionListener(e -> controller.syncModsAsync(false));
        btnPushMods.addActionListener(e -> controller.syncModsAsync(true));

        btnImportZip.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(controller.baseDir().toFile());
            chooser.setDialogTitle("Seleziona Server Pack o Modpack CurseForge (.zip)");
            chooser.setFileFilter(new FileNameExtensionFilter("Archivi ZIP (*.zip)", "zip"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = chooser.getSelectedFile();
                if (f != null) controller.importCurseForgeZipAsync(f.toPath());
            }
        });

        btnInstallPack.addActionListener(e -> {
            Path inst = pendingInstaller;
            if (inst != null) {
                mainFrame.switchToTab(1);
                controller.runPendingServerInstallerAsync(inst);
            }
        });

        tailscaleCard.setAction("Apri Tailscale", () -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI.create("https://login.tailscale.com/admin/machines"));
                }
            } catch (Exception ignored) {
            }
        });
    }

    public void updateDashboard(OrchestratorController.DashboardData d) {
        btnRefresh.setEnabled(true);

        // 1. Tailscale light
        if (d.tailscaleConnected()) {
            tailscaleCard.setStatus(GREEN, "Connesso (" + d.tailscaleIp() + ")",
                    "Rete mesh P2P pronta sulla Tailnet");
            tailscaleCard.setActionVisible(false);
            lblTailscaleIp.setText(d.tailscaleIp() + ":" + (d.config() != null ? d.config().serverPort() : 25565));
        } else {
            tailscaleCard.setStatus(RED, "Disconnesso",
                    "Avvia Tailscale ed effettua il login sulla stessa Tailnet");
            tailscaleCard.setActionVisible(true);
            lblTailscaleIp.setText("Non rilevato");
        }

        if (d.config() == null) {
            lockCard.setStatus(YELLOW, "Configurazione richiesta", "Compila la scheda Configurazione");
            modsCard.setStatus(GRAY, "In attesa di config", "-");
            lblPlayer.setText("-");
            lblRole.setText("Completa prima la configurazione");
            updateSmartButton(null);
            return;
        }

        Config cfg = d.config();
        boolean lockedMode = "LOCKED".equalsIgnoreCase(cfg.modSyncMode());
        btnSyncMods.setVisible(!lockedMode);
        btnPushMods.setVisible(!lockedMode);
        lblPlayer.setText(cfg.playerId());
        lblModpack.setText(d.modpackName());
        lblServerProfile.setText(d.serverDetection().profile().label);

        // In LOCKED mode ServerInstaller installs the loader automatically from the recipe (§7)
        if (!lockedMode && d.serverDetection().profile() == ServerProfileDetector.Profile.INSTALLER_PENDING
                && d.serverDetection().installer() != null) {
            pendingInstaller = d.serverDetection().installer();
            lblBanner.setText("Rilevato Server Pack non ancora installato ("
                    + pendingInstaller.getFileName() + "). Esegui l'installazione prima di hostare.");
            bannerPanel.setVisible(true);
        } else {
            pendingInstaller = null;
            bannerPanel.setVisible(false);
        }

        // 2. Lock & World status
        if (d.remoteError() != null) {
            lockCard.setStatus(RED, "Errore Cloud / Git", shorten(d.remoteError(), 65));
            lblLockState.setText("Non raggiungibile");
            lblRole.setText("Verifica URL Repo e Token GitHub");
        } else if (d.lock() != null) {
            LockManager.Inspection insp = d.lock();
            switch (insp.state()) {
                case FREE -> {
                    lockCard.setStatus(GREEN, "LIBERO", "Nessun giocatore sta hostando in questo momento");
                    lblLockState.setText("LIBERO");
                    lblRole.setText("Pronto ad Hostare");
                }
                case OWN_LEFTOVER -> {
                    lockCard.setStatus(BLUE, "Sessione precedente di " + cfg.playerId(),
                            "Verrà recuperata automaticamente all'avvio");
                    lblLockState.setText("Recupero locale (" + cfg.playerId() + ")");
                    lblRole.setText("Ripristino Host");
                }
                case STALE -> {
                    LockFile l = insp.lock();
                    lockCard.setStatus(YELLOW, "STALE (" + l.ownerId() + ")",
                            "Heartbeat fermo da " + insp.heartbeatAgeSeconds() + "s - subentro automatico");
                    lblLockState.setText("STALE (" + l.ownerId() + ")");
                    lblRole.setText("Subentro come Host");
                }
                case HELD -> {
                    LockFile l = insp.lock();
                    boolean isMe = l.ownerId().equalsIgnoreCase(cfg.playerId());
                    lockCard.setStatus(isMe ? BLUE : ORANGE,
                            isMe ? "Sei l'Host attivo" : "Hostato da " + l.ownerId(),
                            "Indirizzo: " + l.address() + " [" + l.status() + "]");
                    lblLockState.setText(l.ownerId() + " (" + l.status() + ")");
                    lblRole.setText(isMe ? "Host in corso" : "Entra come Client su " + l.address());
                }
            }
        }

        // World version info
        if (d.remoteWorld().isPresent()) {
            WorldManifest w = d.remoteWorld().get();
            String ts = TIME_FMT.format(Instant.ofEpochSecond(w.lastUpdated()));
            lblWorldVer.setText("v" + w.version() + " (da " + w.uploadedBy() + " il " + ts
                    + ", " + FileUtils.humanSize(w.sizeBytes()) + ")");
        } else if (d.localState().worldVersion() > 0) {
            lblWorldVer.setText("v" + d.localState().worldVersion() + " (solo locale)");
        } else {
            lblWorldVer.setText("Nessun mondo remoto (verrà creato al primo avvio)");
        }

        // 3. Modpack status light
        String scopeLabel = "Policy client: " + cfg.clientSyncScope();
        if (d.modUpdatesPending()) {
            modsCard.setStatus(YELLOW, "Aggiornamenti disponibili",
                    "Verranno scaricati automaticamente all'avvio (" + scopeLabel + ")");
        } else {
            modsCard.setStatus(GREEN, "Allineato",
                    d.modpackName() + " • " + scopeLabel);
        }

        updateSmartButton(d);
    }

    public void onOrchestratorState(OrchestratorState st) {
        lblOrchestratorState.setText(st.name());
        updateSmartButton(controller.lastDashboard());
    }

    private void updateSmartButton(OrchestratorController.DashboardData d) {
        if (controller.isSessionRunning()) {
            btnStart.setText("FERMA SESSIONE (SALVA MONDO & RILASCIA LOCK)");
            btnStart.setBackground(RED);
            return;
        }
        if (d == null || d.config() == null) {
            btnStart.setText("CONFIGURA IL LAUNCHER PER INIZIARE");
            btnStart.setBackground( new Color(0x30363D));
            return;
        }
        if (d.lock() != null && d.lock().state() == LockManager.State.HELD
                && !d.lock().lock().ownerId().equalsIgnoreCase(d.config().playerId())) {
            LockFile l = d.lock().lock();
            btnStart.setText("ENTRA NEL SERVER DI " + l.ownerId().toUpperCase() + " (" + l.address() + ")");
            btnStart.setBackground(new Color(0x1F6FEB));
        } else {
            btnStart.setText("AVVIA COME HOST (MONDO LIBERO)");
            btnStart.setBackground(new Color(0x238636));
        }
    }

    // ------------------------------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------------------------------

    static JPanel createCard(String title) {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(new Color(0x21252B));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x30363D), 1, true),
                new EmptyBorder(14, 16, 14, 16)));
        JLabel hdr = new JLabel(title);
        hdr.setFont(hdr.getFont().deriveFont(Font.BOLD, 12f));
        hdr.setForeground(new Color(0x8B949E));
        card.add(hdr, BorderLayout.NORTH);
        return card;
    }

    private static JLabel valueLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13.5f));
        return l;
    }

    private static void addRow(JPanel grid, int row, String key, JComponent val) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(5, 0, 5, 10);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        JLabel k = new JLabel(key);
        k.setForeground(new Color(0x8B949E));
        grid.add(k, c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(val, c);
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String single = s.replace('\n', ' ').trim();
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    /** Traffic-light status card used in the top row. */
    private static final class StatusLightCard extends JPanel {
        private Color dotColor = GRAY;
        private final JLabel lblState = new JLabel("In verifica...");
        private final JLabel lblSub = new JLabel(" ");
        private final JButton btnAction = new JButton();

        StatusLightCard(String title) {
            super(new BorderLayout(10, 4));
            setBackground(new Color(0x21252B));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x30363D), 1, true),
                    new EmptyBorder(12, 14, 12, 14)));

            JPanel dot = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(dotColor);
                    g2.fill(new Ellipse2D.Double(2, 2, 14, 14));
                    g2.dispose();
                }
            };
            dot.setOpaque(false);
            dot.setPreferredSize(new Dimension(18, 18));

            JLabel lblTitle = new JLabel(title.toUpperCase());
            lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 11f));
            lblTitle.setForeground(new Color(0x8B949E));

            JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            header.setOpaque(false);
            header.add(dot);
            header.add(lblTitle);

            lblState.setFont(lblState.getFont().deriveFont(Font.BOLD, 14f));
            lblSub.setFont(lblSub.getFont().deriveFont(12f));
            lblSub.setForeground(new Color(0x8B949E));

            JPanel textBox = new JPanel(new GridLayout(2, 1, 0, 2));
            textBox.setOpaque(false);
            textBox.add(lblState);
            textBox.add(lblSub);

            btnAction.setVisible(false);
            btnAction.setFont(btnAction.getFont().deriveFont(11f));

            add(header, BorderLayout.NORTH);
            add(textBox, BorderLayout.CENTER);
            add(btnAction, BorderLayout.EAST);
        }

        void setStatus(Color c, String state, String subtitle) {
            this.dotColor = c;
            this.lblState.setText(state);
            this.lblSub.setText(subtitle);
            repaint();
        }

        void setAction(String text, Runnable action) {
            btnAction.setText(text);
            for (var l : btnAction.getActionListeners()) btnAction.removeActionListener(l);
            btnAction.addActionListener(e -> action.run());
        }

        void setActionVisible(boolean visible) {
            btnAction.setVisible(visible);
        }
    }
}
