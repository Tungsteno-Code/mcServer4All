package com.mcsync.gui;

import com.mcsync.OrchestratorState;
import com.mcsync.config.Config;
import com.mcsync.core.LockManager;
import com.mcsync.core.StorageSyncManager;
import com.mcsync.gui.controller.OrchestratorController;
import com.mcsync.model.LockFile;
import com.mcsync.model.WorldManifest;
import com.mcsync.model.WorldProfile;
import com.mcsync.setup.PrismInstances;
import com.mcsync.util.FileUtils;
import com.mcsync.world.CreateWorldRequest;
import com.mcsync.world.ModpackSource;
import com.mcsync.world.WorldService;
import com.mcsync.world.ZipClassifier;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vista Unificata "Mondi &amp; Avvio" (Pilastro 3 — Paradigma v3.0):
 * <ul>
 *     <li>Riepilogo live del Mondo Attivo, stato Lock P2P e modalità Single-JVM</li>
 *     <li>Tabella mondi condivisi con badge colorati (LIBERO / ONLINE / AGGIORNAMENTO)</li>
 *     <li>Hero Button animato a 60 FPS ({@link UiAnimations.AnimatedHeroButton}) per Giocare / Entrare / Fermare</li>
 *     <li>Barra di progresso fluida a 60 FPS ({@link UiAnimations.SmoothProgressBar}) con contatore {@code [X/N] - %}</li>
 *     <li>Cassetto Console &amp; Log live espandibile/collassabile senza cambiare scheda</li>
 * </ul>
 */
public final class WorldsPanel extends JPanel {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(ZoneId.systemDefault());

    private final OrchestratorController controller;
    private final MainFrame mainFrame;

    // Top summary labels
    private final JLabel lblActiveWorldTitle = new JLabel("Nessun mondo selezionato");
    private final JLabel lblActiveWorldSub = new JLabel("Crea un mondo da un modpack (.zip / .mrpack) o importa un invito .mcsync");
    private final JLabel lblLockStateTitle = new JLabel("IN ATTESA");
    private final JLabel lblLockStateSub = new JLabel("Verifica dello storage remoto in corso...");
    private final JLabel lblSyncTitle = new JLabel("Single-JVM Client-as-Server");
    private final JLabel lblSyncSub = new JLabel("Risparmio 50% RAM • Handover istantaneo dal Menu Principale");

    // Table
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Attivo", "ID Mondo", "Nome Mondo", "Stato Live", "Modpack (Remoto / Locale)", "Modalità"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private List<WorldService.WorldStatus> currentWorlds = new ArrayList<>();

    // Animated Hero Button & Progress Bar
    private final UiAnimations.AnimatedHeroButton btnHeroPlay =
            new UiAnimations.AnimatedHeroButton("▶  GIOCA NEL MONDO SELEZIONATO (SINGLE-JVM)");
    private final UiAnimations.SmoothProgressBar progressBar = new UiAnimations.SmoothProgressBar();

    // Expandable Console Drawer
    private final JPanel consoleDrawer = new JPanel(new BorderLayout(6, 6));
    private final JTextArea txtConsole = new JTextArea(8, 80);
    private final JTextField txtCommand = new JTextField();
    private final JButton btnToggleConsole = new JButton("▼ Mostra Console & Log Live");
    private boolean consoleExpanded = false;

    public WorldsPanel(OrchestratorController controller, MainFrame mainFrame) {
        super(new BorderLayout(12, 12));
        this.controller = controller;
        this.mainFrame = mainFrame;
        setBorder(new EmptyBorder(14, 20, 14, 20));
        setOpaque(false);

        add(buildSummaryCardsRow(), BorderLayout.NORTH);
        add(buildCenterWorldTableCard(), BorderLayout.CENTER);
        add(buildBottomControlAndConsoleArea(), BorderLayout.SOUTH);

        LogBus.addListener(line -> SwingUtilities.invokeLater(() -> appendConsoleLine(line)));
    }

    private JPanel buildSummaryCardsRow() {
        JPanel grid = new JPanel(new GridLayout(1, 3, 12, 0));
        grid.setOpaque(false);

        // Card 1: Mondo Attivo
        JPanel cWorld = DashboardPanel.createCard("MONDO ATTIVO");
        lblActiveWorldTitle.setFont(lblActiveWorldTitle.getFont().deriveFont(Font.BOLD, 16f));
        lblActiveWorldTitle.setForeground(new Color(0x58A6FF));
        lblActiveWorldSub.setForeground(new Color(0x8B949E));
        JPanel b1 = new JPanel(new GridLayout(2, 1, 0, 4));
        b1.setOpaque(false);
        b1.add(lblActiveWorldTitle);
        b1.add(lblActiveWorldSub);
        cWorld.add(b1, BorderLayout.CENTER);

        // Card 2: Stato Lock & Sessione
        JPanel cLock = DashboardPanel.createCard("STATO SESSIONE P2P");
        lblLockStateTitle.setFont(lblLockStateTitle.getFont().deriveFont(Font.BOLD, 16f));
        lblLockStateTitle.setForeground(new Color(0x3FB950));
        lblLockStateSub.setForeground(new Color(0x8B949E));
        JPanel b2 = new JPanel(new GridLayout(2, 1, 0, 4));
        b2.setOpaque(false);
        b2.add(lblLockStateTitle);
        b2.add(lblLockStateSub);
        cLock.add(b2, BorderLayout.CENTER);

        // Card 3: Sincronizzazione & Paradigma Single-JVM
        JPanel cSync = DashboardPanel.createCard("MOTORE DI SINCRONIZZAZIONE");
        lblSyncTitle.setFont(lblSyncTitle.getFont().deriveFont(Font.BOLD, 15f));
        lblSyncTitle.setForeground(new Color(0xE6EDF3));
        lblSyncSub.setForeground(new Color(0x8B949E));
        JPanel b3 = new JPanel(new GridLayout(2, 1, 0, 4));
        b3.setOpaque(false);
        b3.add(lblSyncTitle);
        b3.add(lblSyncSub);
        cSync.add(b3, BorderLayout.CENTER);

        grid.add(cWorld);
        grid.add(cLock);
        grid.add(cSync);
        return grid;
    }

    private JPanel buildCenterWorldTableCard() {
        JPanel card = DashboardPanel.createCard("MONDI CONDIVISI (SELEZIONA E PREMERE GIOCA)");
        table.setRowHeight(32);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 4));

        // Custom cell renderer for live status badge column (col 3) and active star (col 0)
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
                lbl.setBorder(new EmptyBorder(0, 10, 0, 10));
                String text = value != null ? value.toString() : "";
                if (!isSelected) {
                    if (col == 0 && !text.isEmpty()) {
                        lbl.setForeground(new Color(0x3FB950));
                        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
                    } else if (col == 3) {
                        String low = text.toLowerCase();
                        if (low.contains("online")) {
                            lbl.setForeground(new Color(0x58A6FF));
                            lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
                        } else if (low.contains("aggiornamento")) {
                            lbl.setForeground(new Color(0xD29922));
                            lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
                        } else {
                            lbl.setForeground(new Color(0x3FB950));
                            lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
                        }
                    } else {
                        lbl.setForeground(new Color(0xE6EDF3));
                    }
                }
                return lbl;
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateHeroButtonForSelection();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildBottomControlAndConsoleArea() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setOpaque(false);

        // 1. Hero Button + Quick Select Row
        btnHeroPlay.addActionListener(e -> {
            if (controller.isSessionRunning()) {
                controller.requestStopSession();
            } else {
                playSelectedWorld();
            }
        });

        JButton btnSelect = new JButton("Imposta Predefinito");
        btnSelect.addActionListener(e -> selectHighlightWorld());

        JPanel heroRow = new JPanel(new BorderLayout(10, 0));
        heroRow.setOpaque(false);
        heroRow.add(btnHeroPlay, BorderLayout.CENTER);
        heroRow.add(btnSelect, BorderLayout.EAST);

        // 2. Smooth 60 FPS Progress Bar
        progressBar.IdleText("Pronto — Seleziona un mondo e premi GIOCA");

        // 3. Action Toolbar Row
        JButton btnCreate = new JButton("+ Crea Mondo...");
        JButton btnImportInvite = new JButton("Importa Invito (.mcsync)...");
        JButton btnExportInvite = new JButton("Esporta Invito (.mcsync)...");
        JButton btnUpdateModpack = new JButton("Aggiorna Modpack...");
        JButton btnRollback = new JButton("Rollback Modpack...");
        JButton btnForceUnlock = new JButton("Sblocca Lock");
        JButton btnRefresh = new JButton("Aggiorna");

        btnCreate.addActionListener(e -> showCreateWorldDialog());
        btnImportInvite.addActionListener(e -> showImportInviteDialog());
        btnExportInvite.addActionListener(e -> showExportInviteDialog());
        btnUpdateModpack.addActionListener(e -> showUpdateModpackDialog());
        btnRollback.addActionListener(e -> showRollbackDialog());
        btnForceUnlock.addActionListener(e -> confirmForceUnlock());
        btnRefresh.addActionListener(e -> {
            refreshWorlds();
            controller.refreshDashboardAsync();
        });
        btnToggleConsole.addActionListener(e -> toggleConsoleDrawer());

        JPanel actionsRow = new JPanel(new BorderLayout(8, 0));
        actionsRow.setOpaque(false);
        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftBtns.setOpaque(false);
        leftBtns.add(btnCreate);
        leftBtns.add(btnImportInvite);
        leftBtns.add(btnExportInvite);
        leftBtns.add(btnUpdateModpack);
        leftBtns.add(btnRollback);
        leftBtns.add(btnForceUnlock);
        leftBtns.add(btnRefresh);

        actionsRow.add(leftBtns, BorderLayout.CENTER);
        actionsRow.add(btnToggleConsole, BorderLayout.EAST);

        // 4. Collapsible Live Console Drawer
        txtConsole.setEditable(false);
        txtConsole.setBackground(new Color(0x0D1117));
        txtConsole.setForeground(new Color(0xD0D7DE));
        txtConsole.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane consoleScroll = new JScrollPane(txtConsole);
        consoleScroll.setPreferredSize(new Dimension(800, 155));

        JPanel cmdBar = new JPanel(new BorderLayout(6, 0));
        cmdBar.setOpaque(false);
        txtCommand.putClientProperty("JTextField.placeholderText",
                "Comando server (es. say Ciao, time set day, list)...");
        JButton btnSendCmd = new JButton("Invia");
        Runnable sendAction = () -> {
            String cmd = txtCommand.getText().trim();
            if (!cmd.isEmpty()) {
                controller.sendServerCommand(cmd);
                txtCommand.setText("");
            }
        };
        txtCommand.addActionListener(e -> sendAction.run());
        btnSendCmd.addActionListener(e -> sendAction.run());
        cmdBar.add(txtCommand, BorderLayout.CENTER);
        cmdBar.add(btnSendCmd, BorderLayout.EAST);

        consoleDrawer.setOpaque(false);
        consoleDrawer.setBorder(BorderFactory.createTitledBorder("Console & Registro Live della Sessione"));
        consoleDrawer.add(consoleScroll, BorderLayout.CENTER);
        consoleDrawer.add(cmdBar, BorderLayout.SOUTH);
        consoleDrawer.setVisible(false);

        JPanel topControls = new JPanel(new BorderLayout(0, 8));
        topControls.setOpaque(false);
        topControls.add(heroRow, BorderLayout.NORTH);
        topControls.add(progressBar, BorderLayout.CENTER);
        topControls.add(actionsRow, BorderLayout.SOUTH);

        root.add(topControls, BorderLayout.NORTH);
        root.add(consoleDrawer, BorderLayout.CENTER);
        return root;
    }

    public void toggleConsoleDrawer() {
        consoleExpanded = !consoleExpanded;
        consoleDrawer.setVisible(consoleExpanded);
        btnToggleConsole.setText(consoleExpanded ? "▲ Nascondi Console Live" : "▼ Mostra Console & Log Live");
        revalidate();
        repaint();
    }

    public void expandConsoleDrawer() {
        if (!consoleExpanded) {
            toggleConsoleDrawer();
        }
    }

    public void appendConsoleLine(String line) {
        if (txtConsole.getDocument().getLength() > 200_000) {
            txtConsole.setText("");
        }
        txtConsole.append(line + "\n");
        txtConsole.setCaretPosition(txtConsole.getDocument().getLength());
    }

    public void updatePlayers(Set<String> players) {
        if (controller.isSessionRunning()) {
            lblSyncSub.setText("Giocatori connessi (" + players.size() + "): "
                    + (players.isEmpty() ? "nessuno" : String.join(", ", players)));
        }
    }

    public void onProgressUpdated(int percent, String detail, boolean active) {
        progressBar.setProgressState(percent, detail, active);
    }

    public void onStatusToast(String message, boolean isError) {
        if (isError) {
            progressBar.setProgressState(0, "⚠ " + message, false);
        } else if (!controller.isSessionRunning()) {
            progressBar.IdleText(message);
        }
    }

    public void onOrchestratorState(OrchestratorState state) {
        lblLockStateTitle.setText(state.name());
        switch (state) {
            case CHECKING_LOCK -> {
                onProgressUpdated(12, "Verifica lock P2P sul repository remoto...", true);
            }
            case ACQUIRING_LOCK -> {
                onProgressUpdated(25, "Acquisizione lock atomico per ospitare il mondo...", true);
            }
            case SYNCING_DOWN -> {
                onProgressUpdated(45, "Download delta del mondo dal cloud (Zstd)...", true);
            }
            case SYNCING_MODS -> {
                onProgressUpdated(65, "Allineamento istanza client e modpack (hard-link cache)...", true);
            }
            case STARTING_SERVER -> {
                onProgressUpdated(85, "Avvio Single-JVM Host & apertura automatica LAN/Tailscale...", true);
            }
            case HOSTING, DAEMON_HOSTING -> {
                lblLockStateTitle.setText("SESSIONE HOST ATTIVA");
                lblLockStateTitle.setForeground(new Color(0x3FB950));
                lblLockStateSub.setText("Esci al Menu Principale di Minecraft per il Fast Handover istantaneo!");
                onProgressUpdated(100, "Mondo ONLINE su Tailscale (Single-JVM attiva)", true);
                btnHeroPlay.setHeroStyle(
                        "⏹  FERMA SESSIONE, SALVA MONDO & RILASCIA LOCK",
                        new Color(0xDA3633), new Color(0xF85149), true);
            }
            case CLIENT_MODE -> {
                lblLockStateTitle.setText("CONNESSO COME CLIENT");
                lblLockStateTitle.setForeground(new Color(0x58A6FF));
                onProgressUpdated(100, "Connesso al mondo dell'Host su Tailscale", true);
            }
            case SHUTTING_DOWN, SYNCING_UP, RELEASING_LOCK -> {
                lblLockStateTitle.setText("SALVATAGGIO & UPLOAD DELTA");
                lblLockStateTitle.setForeground(new Color(0xD29922));
                onProgressUpdated(90, "Compressione Zstd delta mondo, sanitizzazione level.dat e rilascio lock...", true);
            }
            case TERMINATED -> {
                onProgressUpdated(100, "Sessione completata e sincronizzata!", false);
                updateHeroButtonForSelection();
            }
            case FAILED -> {
                lblLockStateTitle.setText("ERRORE SESSIONE");
                lblLockStateTitle.setForeground(new Color(0xF85149));
                onProgressUpdated(0, "Sessione interrotta con errore — consulta la scheda Diagnostica", false);
                updateHeroButtonForSelection();
            }
            default -> {
            }
        }
    }

    public void onSessionFinished(int exitCode) {
        updateHeroButtonForSelection();
        refreshWorlds();
    }

    public void updateDashboardContext(OrchestratorController.DashboardData data) {
        Config cfg = data.config();
        if (cfg != null && cfg.activeWorld().isPresent()) {
            WorldProfile wp = cfg.activeWorld().get();
            lblActiveWorldTitle.setText(wp.displayName() + " (" + wp.id() + ")");
            lblActiveWorldSub.setText("Ruolo: " + wp.role() + "  •  Porta Tailscale: " + cfg.serverPort());
        }
        if (data.lock() != null) {
            LockManager.Inspection insp = data.lock();
            LockFile lock = insp.lock();
            if (!controller.isSessionRunning()) {
                switch (insp.state()) {
                    case FREE -> {
                        lblLockStateTitle.setText("LIBERO — PRONTO PER OSPITARE");
                        lblLockStateTitle.setForeground(new Color(0x3FB950));
                        lblLockStateSub.setText("Nessuno sta ospitando: avvierai il mondo in Single-JVM");
                    }
                    case HELD -> {
                        lblLockStateTitle.setText("ONLINE — OSPITATO DA " + (lock != null ? lock.ownerId().toUpperCase() : "HOST"));
                        lblLockStateTitle.setForeground(new Color(0x58A6FF));
                        lblLockStateSub.setText("Indirizzo Tailscale: " + (lock != null ? lock.address() : "-") + " (entrerai come Client)");
                    }
                    case STALE, OWN_LEFTOVER -> {
                        lblLockStateTitle.setText("LOCK SCADUTO / RECUPERABILE");
                        lblLockStateTitle.setForeground(new Color(0xD29922));
                        lblLockStateSub.setText("La sessione precedente verrà recuperata automaticamente all'avvio");
                    }
                }
            }
            Optional<WorldManifest> rm = data.remoteWorld();
            if (rm != null && rm.isPresent()) {
                WorldManifest w = rm.get();
                long localVer = data.localState() != null ? data.localState().worldVersion() : 0L;
                lblSyncTitle.setText("Mondo Cloud v" + w.version() + "  (Locale v" + localVer + ")");
                lblSyncSub.setText("Ultimo salvataggio di " + w.uploadedBy() + " il "
                        + TIME_FMT.format(Instant.ofEpochSecond(w.lastUpdated()))
                        + " (" + FileUtils.humanSize(w.sizeBytes()) + ")");
            } else {
                lblSyncTitle.setText("Single-JVM Client-as-Server");
                lblSyncSub.setText("Risparmio 50% RAM • Handover istantaneo dal Menu Principale");
            }
        }
    }

    public void refreshWorlds() {
        controller.refreshWorldsAsync(list -> {
            currentWorlds = list != null ? list : List.of();
            tableModel.setRowCount(0);
            Config cfg = controller.currentConfig();
            String activeId = cfg != null ? cfg.activeWorldId() : "";
            int selRow = -1;
            for (int i = 0; i < currentWorlds.size(); i++) {
                WorldService.WorldStatus ws = currentWorlds.get(i);
                WorldProfile p = ws.profile();
                boolean isActive = p.id().equals(activeId) || (activeId.isEmpty() && currentWorlds.size() == 1);
                if (isActive) selRow = i;
                String ver = "v" + ws.remoteModpackVersion() + " (locale: v" + ws.appliedModpackVersion() + ")";
                tableModel.addRow(new Object[]{
                        isActive ? "★ Attivo" : "",
                        p.id(),
                        p.displayName(),
                        ws.label(),
                        ver,
                        "Single-JVM P2P"
                });
            }
            if (selRow >= 0 && selRow < table.getRowCount()) {
                table.setRowSelectionInterval(selRow, selRow);
            }
            updateHeroButtonForSelection();
        });
    }

    private void updateHeroButtonForSelection() {
        if (controller.isSessionRunning()) {
            btnHeroPlay.setHeroStyle(
                    "⏹  FERMA SESSIONE, SALVA MONDO & RILASCIA LOCK",
                    new Color(0xDA3633), new Color(0xF85149), true);
            return;
        }
        int row = table.getSelectedRow();
        if (row >= 0 && row < currentWorlds.size()) {
            WorldService.WorldStatus ws = currentWorlds.get(row);
            String label = ws.label().toLowerCase();
            if (label.contains("online")) {
                btnHeroPlay.setHeroStyle(
                        "▶  UNISCITI AL MONDO '" + ws.profile().displayName().toUpperCase() + "' (CLIENT)",
                        new Color(0x1F6FEB), new Color(0x388BFD), true);
            } else {
                btnHeroPlay.setHeroStyle(
                        "▶  GIOCA ORA IN '" + ws.profile().displayName().toUpperCase() + "' (SINGLE-JVM HOST)",
                        new Color(0x238636), new Color(0x2EA043), true);
            }
        } else {
            btnHeroPlay.setHeroStyle(
                    "▶  SELEZIONA O CREA UN MONDO PER GIOCARE",
                    new Color(0x238636), new Color(0x2EA043), false);
        }
    }

    private WorldProfile selectedWorld() {
        int row = table.getSelectedRow();
        if (row >= 0 && row < currentWorlds.size()) {
            return currentWorlds.get(row).profile();
        }
        Config cfg = controller.currentConfig();
        return cfg != null ? cfg.activeWorld().orElse(null) : null;
    }

    private void selectHighlightWorld() {
        WorldProfile w = selectedWorld();
        if (w == null) {
            JOptionPane.showMessageDialog(this, "Seleziona un mondo dalla lista.", "Mondi", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        controller.selectWorldAsync(w.id(), () -> {
            refreshWorlds();
            controller.refreshDashboardAsync();
        });
    }

    private void playSelectedWorld() {
        WorldProfile w = selectedWorld();
        if (w == null) {
            JOptionPane.showMessageDialog(this, "Seleziona o crea prima un mondo.", "Mondi", JOptionPane.WARNING_MESSAGE);
            return;
        }
        controller.selectWorldAsync(w.id(), () -> {
            mainFrame.selectMonitorTabOnHost();
            controller.startSession(true);
        });
    }

    private void showCreateWorldDialog() {
        JTextField txtName = new JTextField("Nuovo Mondo con gli amici", 24);
        JTextField txtId = new JTextField("nuovo-mondo", 18);
        JComboBox<String> cmbSourceType = new JComboBox<>(new String[]{
                "Archivio CurseForge (.zip) o Modrinth (.mrpack)",
                "Istanza Prism Launcher esistente",
                "Cartella manuale (mods/ + config/)"
        });
        JTextField txtClientPack = new JTextField(26);
        JButton btnBrowseClient = new JButton("Sfoglia...");

        List<PrismInstances.Instance> prismInstances = PrismInstances.scan(controller.baseDir());
        JComboBox<PrismInstances.Instance> cmbPrism = new JComboBox<>(prismInstances.toArray(new PrismInstances.Instance[0]));

        JTextField txtServerPack = new JTextField(26);
        JButton btnBrowseServer = new JButton("Sfoglia (Opzionale)...");

        JSpinner spRamMin = new JSpinner(new SpinnerNumberModel(6144, 1024, 32768, 512));
        JSpinner spRamMax = new JSpinner(new SpinnerNumberModel(8192, 2048, 32768, 512));
        JSpinner spPort = new JSpinner(new SpinnerNumberModel(25565, 1024, 65535, 1));
        JCheckBox chkExportInvite = new JCheckBox("Esporta subito il file d'invito (.mcsync) dopo la creazione", true);

        btnBrowseClient.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(controller.baseDir().toFile());
            if (cmbSourceType.getSelectedIndex() == 2) {
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            } else {
                fc.setFileFilter(new FileNameExtensionFilter("Modpack (*.zip, *.mrpack)", "zip", "mrpack"));
            }
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                txtClientPack.setText(fc.getSelectedFile().getAbsolutePath());
                if (txtId.getText().equals("nuovo-mondo")) {
                    String fn = fc.getSelectedFile().getName();
                    int dot = fn.lastIndexOf('.');
                    String slug = WorldProfile.slugify(dot > 0 ? fn.substring(0, dot) : fn);
                    txtId.setText(slug);
                    txtName.setText(dot > 0 ? fn.substring(0, dot) : fn);
                }
            }
        });

        btnBrowseServer.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(controller.baseDir().toFile());
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                txtServerPack.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        int r = 0;
        addFormRow(form, r++, "Nome Visualizzato:", txtName);
        addFormRow(form, r++, "ID Mondo (slug):", txtId);
        addFormRow(form, r++, "Tipo Sorgente Client:", cmbSourceType);

        JPanel clientRow = new JPanel(new BorderLayout(6, 0));
        clientRow.add(txtClientPack, BorderLayout.CENTER);
        clientRow.add(btnBrowseClient, BorderLayout.EAST);
        addFormRow(form, r++, "Percorso Modpack Client:", clientRow);
        addFormRow(form, r++, "Oppure Istanza Prism:", cmbPrism);

        JPanel serverRow = new JPanel(new BorderLayout(6, 0));
        serverRow.add(txtServerPack, BorderLayout.CENTER);
        serverRow.add(btnBrowseServer, BorderLayout.EAST);
        addFormRow(form, r++, "Server Pack (Opzionale):", serverRow);

        JPanel ramRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ramRow.add(new JLabel("Min (MB):"));
        ramRow.add(spRamMin);
        ramRow.add(new JLabel("Max (MB):"));
        ramRow.add(spRamMax);
        ramRow.add(new JLabel("Porta:"));
        ramRow.add(spPort);
        addFormRow(form, r++, "Risorse Gioco:", ramRow);
        addFormRow(form, r, "", chkExportInvite);

        int res = JOptionPane.showConfirmDialog(this, form, "Crea Nuovo Mondo Condiviso",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;

        try {
            ModpackSource clientSrc;
            if (cmbSourceType.getSelectedIndex() == 1 && cmbPrism.getSelectedItem() != null) {
                PrismInstances.Instance inst = (PrismInstances.Instance) cmbPrism.getSelectedItem();
                clientSrc = new ModpackSource.PrismInstance(inst.gameDir().getParent());
            } else {
                Path p = Path.of(txtClientPack.getText().trim());
                ZipClassifier.Classification cls = ZipClassifier.classify(p);
                if (cls.isAmbiguous()) {
                    int confirm = JOptionPane.showConfirmDialog(this,
                            cls.summary() + "\nVuoi procedere trattandolo come Modpack Client CurseForge?",
                            "Conferma formato archivio", JOptionPane.YES_NO_OPTION);
                    if (confirm != JOptionPane.YES_OPTION) return;
                }
                clientSrc = ZipClassifier.toModpackSource(p);
            }

            ModpackSource serverSrc = null;
            if (!txtServerPack.getText().trim().isEmpty()) {
                serverSrc = ZipClassifier.toModpackSource(Path.of(txtServerPack.getText().trim()));
            }

            String wid = WorldProfile.slugify(txtId.getText().trim());
            CreateWorldRequest req = new CreateWorldRequest(
                    wid,
                    txtName.getText().trim(),
                    clientSrc,
                    serverSrc,
                    (Integer) spRamMin.getValue(),
                    (Integer) spRamMax.getValue(),
                    (Integer) spPort.getValue(),
                    List.of(),
                    chkExportInvite.isSelected());

            Path inviteOut = chkExportInvite.isSelected() ? controller.baseDir().resolve(wid + ".mcsync") : null;
            controller.createWorldAsync(req, inviteOut, () -> {
                refreshWorlds();
                controller.refreshDashboardAsync();
            });
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Errore Creazione Mondo", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showImportInviteDialog() {
        JFileChooser fc = new JFileChooser(controller.baseDir().toFile());
        fc.setDialogTitle("Seleziona file d'invito (.mcsync)");
        fc.setFileFilter(new FileNameExtensionFilter("Invito mcServer4all (*.mcsync)", "mcsync", "json"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
            controller.importInviteAsync(fc.getSelectedFile().toPath(), () -> {
                refreshWorlds();
                controller.refreshDashboardAsync();
                mainFrame.reloadConfigTab();
            });
        }
    }

    private void showExportInviteDialog() {
        WorldProfile w = selectedWorld();
        if (w == null) {
            JOptionPane.showMessageDialog(this, "Seleziona prima un mondo dalla lista.", "Esporta Invito", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser(controller.baseDir().toFile());
        fc.setDialogTitle("Salva file d'invito (.mcsync)");
        fc.setSelectedFile(new File(controller.baseDir().toFile(), w.id() + ".mcsync"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
            controller.exportInviteAsync(w.id(), fc.getSelectedFile().toPath());
        }
    }

    private void showUpdateModpackDialog() {
        WorldProfile w = selectedWorld();
        if (w == null) {
            JOptionPane.showMessageDialog(this, "Seleziona prima un mondo dalla lista.", "Aggiorna Modpack", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String[] choices = new String[]{
                "Dalla mia istanza Prism del mondo (" + w.prismInstanceId() + ")",
                "Da un nuovo file .zip CurseForge o .mrpack Modrinth..."
        };
        int ch = JOptionPane.showOptionDialog(this,
                "Come vuoi aggiornare il modpack bloccato del mondo '" + w.displayName() + "'?",
                "Aggiorna Modpack", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, choices, choices[0]);
        if (ch < 0) return;

        try {
            ModpackSource src;
            if (ch == 0 && !w.clientInstanceDirectory().isBlank()) {
                Path instDir = Path.of(Config.expandHome(w.clientInstanceDirectory())).getParent();
                src = new ModpackSource.PrismInstance(instDir);
            } else {
                JFileChooser fc = new JFileChooser(controller.baseDir().toFile());
                fc.setDialogTitle("Seleziona il nuovo Modpack (.zip, .mrpack o cartella)");
                fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) return;
                src = ZipClassifier.toModpackSource(fc.getSelectedFile().toPath());
            }

            controller.updateModpackAsync(w.id(), src, WorldService.UpdateOptions.defaults(), diff -> {
                AtomicBoolean approved = new AtomicBoolean(false);
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        JTextArea area = new JTextArea(diff.render(), 16, 56);
                        area.setEditable(false);
                        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                        String warn = diff.removed().isEmpty() ? ""
                                : "\nATTENZIONE: " + diff.removed().size() + " mod verranno rimosse (verrà creato un backup pre-update automatico).";
                        int ans = JOptionPane.showConfirmDialog(this,
                                new Object[]{"Differenze calcolate per il nuovo lockfile:" + warn, new JScrollPane(area)},
                                "Conferma Aggiornamento Modpack", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        approved.set(ans == JOptionPane.YES_OPTION);
                    });
                } catch (Exception ignored) {
                }
                return approved.get();
            }, this::refreshWorlds);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Errore Aggiornamento Modpack", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showRollbackDialog() {
        WorldProfile w = selectedWorld();
        if (w == null) {
            JOptionPane.showMessageDialog(this, "Seleziona prima un mondo dalla lista.", "Rollback Modpack", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            WorldService ws = controller.createWorldService(controller.currentConfig());
            List<Long> versions = ws.modpackVersions(w.id());
            if (versions.size() < 2) {
                JOptionPane.showMessageDialog(this,
                        "Nessuna versione precedente disponibile in locks/ per il mondo '" + w.displayName() + "'.",
                        "Rollback Modpack", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            List<Long> previous = versions.subList(0, versions.size() - 1);
            Long chosen = (Long) JOptionPane.showInputDialog(this,
                    "Seleziona la versione precedente del modpack a cui tornare:",
                    "Rollback Modpack — " + w.displayName(),
                    JOptionPane.QUESTION_MESSAGE, null,
                    previous.toArray(new Long[0]), previous.get(previous.size() - 1));
            if (chosen != null) {
                controller.rollbackModpackAsync(w.id(), chosen, this::refreshWorlds);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Errore Rollback", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void confirmForceUnlock() {
        int ans = JOptionPane.showConfirmDialog(this,
                "Sei sicuro di voler eliminare forzatamente il lock remoto?\n"
                        + "Esegui questa operazione SOLO se nessun amico sta realmente ospitando la partita.",
                "Conferma Sblocco Forzato", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans == JOptionPane.YES_OPTION) {
            controller.forceUnlockAsync();
        }
    }

    private static void addFormRow(JPanel form, int row, String label, JComponent comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(4, 4, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(comp, c);
    }
}
