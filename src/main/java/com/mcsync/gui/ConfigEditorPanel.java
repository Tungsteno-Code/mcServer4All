package com.mcsync.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcsync.config.Config;
import com.mcsync.diagnostics.ConfigFixer;
import com.mcsync.gui.controller.OrchestratorController;
import com.mcsync.util.NetUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.List;

/**
 * Scheda "Impostazioni" semplificata (Pilastro 3 — §5.3 {@code ARCHITETTURA_NUOVO_PARADIGMA.md}):
 * <ol>
 *     <li><b>Nickname Giocatore ({@code playerId})</b> &amp; Rete Tailscale automatica</li>
 *     <li><b>Memoria RAM allocata a Minecraft</b> (Slider Min / Max GB per Single-JVM &amp; Server)</li>
 *     <li><b>Credenziali Storage Git</b> ({@code gitRemoteUrl}, {@code gitToken}, {@code gitBranch} + Testa Connessione)</li>
 *     <li><b>CurseForge API Key ({@code curseforgeApiKey})</b> (importata automaticamente dagli inviti {@code .mcsync})</li>
 * </ol>
 * Tutte le vecchie impostazioni ridondanti della v1.0 ({@code serverDirectory}, {@code serverStartCommand},
 * {@code clientSyncScope}, Whitelist manuale, ecc.) sono gestite automaticamente dal motore v3.0.
 */
public final class ConfigEditorPanel extends JPanel {

    private final OrchestratorController controller;

    // 1. Giocatore & Tailscale
    private final JTextField txtPlayerId = new JTextField();
    private final JComboBox<String> cmbTailscaleIp = new JComboBox<>();

    // 2. Memoria RAM
    private final JSlider sliderRamMin = new JSlider(1, 32, 4);
    private final JSlider sliderRamMax = new JSlider(2, 32, 6);
    private final JLabel lblRamValue = new JLabel("4 GB Min (-Xms)  /  6 GB Max (-Xmx)");

    // 3. Credenziali Storage Git
    private final JComboBox<String> cmbBackend = new JComboBox<>(new String[]{"GIT", "S3"});
    private final JTextField txtGitUrl = new JTextField();
    private final JPasswordField txtGitToken = new JPasswordField();
    private final JTextField txtGitBranch = new JTextField("mcsync-state");
    private final JButton btnTestConnection = new JButton("Testa Connessione");
    private final JLabel lblTestResult = new JLabel(" ");

    // 4. CurseForge API Key
    private final JPasswordField txtCurseforgeKey = new JPasswordField();

    private final UiAnimations.AnimatedHeroButton btnSave =
            new UiAnimations.AnimatedHeroButton("💾  SALVA IMPOSTAZIONI");
    private final JButton btnReload = new JButton("Ricarica da disco");

    public ConfigEditorPanel(OrchestratorController controller) {
        super(new BorderLayout(12, 12));
        this.controller = controller;
        setBorder(new EmptyBorder(16, 22, 16, 22));
        setOpaque(false);

        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.setOpaque(false);
        sections.add(buildPlayerSection());
        sections.add(Box.createVerticalStrut(12));
        sections.add(buildRamSection());
        sections.add(Box.createVerticalStrut(12));
        sections.add(buildStorageSection());
        sections.add(Box.createVerticalStrut(12));
        sections.add(buildCurseForgeSection());

        JScrollPane scroll = new JScrollPane(sections);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        add(buildFooterBar(), BorderLayout.SOUTH);

        populateNetworkDropdown();
        loadFromDisk();
    }

    private JPanel buildPlayerSection() {
        JPanel card = DashboardPanel.createCard("1. PROFILO GIOCATORE & RETE P2P TAILSCALE");
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);

        cmbTailscaleIp.setEditable(true);
        JButton btnRescanNet = new JButton("Rileva IP");
        btnRescanNet.addActionListener(e -> populateNetworkDropdown());

        JPanel ipPanel = new JPanel(new BorderLayout(6, 0));
        ipPanel.setOpaque(false);
        ipPanel.add(cmbTailscaleIp, BorderLayout.CENTER);
        ipPanel.add(btnRescanNet, BorderLayout.EAST);

        addFormRow(form, 0, "Nickname Giocatore (playerId):", txtPlayerId);
        addFormRow(form, 1, "Indirizzo IP Tailscale (consigliato: auto):", ipPanel);

        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildRamSection() {
        JPanel card = DashboardPanel.createCard("2. MEMORIA RAM ALLOCATA A MINECRAFT (SINGLE-JVM & SERVER)");
        JPanel ramPanel = new JPanel(new GridBagLayout());
        ramPanel.setOpaque(false);

        sliderRamMin.setMajorTickSpacing(4);
        sliderRamMin.setPaintTicks(true);
        sliderRamMax.setMajorTickSpacing(4);
        sliderRamMax.setPaintTicks(true);

        Runnable updateRamLabel = () -> {
            if (sliderRamMin.getValue() > sliderRamMax.getValue()) {
                sliderRamMax.setValue(sliderRamMin.getValue());
            }
            lblRamValue.setText(sliderRamMin.getValue() + " GB Min (-Xms)  /  "
                    + sliderRamMax.getValue() + " GB Max (-Xmx)   —   Modalità Single-JVM (0 GB sprecati per doppio processo)");
        };
        sliderRamMin.addChangeListener(e -> updateRamLabel.run());
        sliderRamMax.addChangeListener(e -> updateRamLabel.run());

        lblRamValue.setFont(lblRamValue.getFont().deriveFont(Font.BOLD, 13f));
        lblRamValue.setForeground(new Color(0x3FB950));

        addFormRow(ramPanel, 0, "Allocazione Attuale:", lblRamValue);
        addFormRow(ramPanel, 1, "RAM Minima (-Xms GB):", sliderRamMin);
        addFormRow(ramPanel, 2, "RAM Massima (-Xmx GB):", sliderRamMax);

        card.add(ramPanel, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildStorageSection() {
        JPanel card = DashboardPanel.createCard("3. CREDENZIALI STORAGE GIT CONDIVISO");
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);

        txtGitUrl.putClientProperty("JTextField.placeholderText", "https://github.com/tuo-utente/tuo-repo-privato.git");
        txtGitToken.putClientProperty("JTextField.placeholderText", "ghp_... (Personal Access Token con permesso repo)");

        JPanel testRow = new JPanel(new BorderLayout(10, 0));
        testRow.setOpaque(false);
        testRow.add(btnTestConnection, BorderLayout.WEST);
        testRow.add(lblTestResult, BorderLayout.CENTER);

        btnTestConnection.addActionListener(e -> runConnectionTest());

        addFormRow(form, 0, "Backend Archiviazione:", cmbBackend);
        addFormRow(form, 1, "URL Repository Git (gitRemoteUrl):", txtGitUrl);
        addFormRow(form, 2, "GitHub Personal Access Token (gitToken):", txtGitToken);
        addFormRow(form, 3, "Branch di Stato (gitBranch):", txtGitBranch);
        addFormRow(form, 4, "Verifica Accesso:", testRow);

        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildCurseForgeSection() {
        JPanel card = DashboardPanel.createCard("4. CURSEFORGE API KEY (RISOLUZIONE BATCH & DOWNLOAD CDN)");
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);

        txtCurseforgeKey.putClientProperty("JTextField.placeholderText",
                "$2a$10$... (Incollala una sola volta o importala automaticamente da un file d'invito .mcsync)");
        JLabel hint = new JLabel("Inclusa automaticamente nei file d'invito .mcsync esportati per gli amici.");
        hint.setForeground(new Color(0x8B949E));

        addFormRow(form, 0, "CurseForge API Key (curseforgeApiKey):", txtCurseforgeKey);
        addFormRow(form, 1, "Condivisione Automatica:", hint);

        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildFooterBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);

        btnSave.setHeroStyle("💾  SALVA IMPOSTAZIONI", new Color(0x238636), new Color(0x2EA043), false);
        btnSave.setPreferredSize(new Dimension(280, 44));
        btnSave.addActionListener(e -> saveToDisk());

        btnReload.addActionListener(e -> loadFromDisk());

        bar.add(btnReload, BorderLayout.WEST);
        bar.add(btnSave, BorderLayout.EAST);
        return bar;
    }

    private void populateNetworkDropdown() {
        Object prev = cmbTailscaleIp.getSelectedItem();
        cmbTailscaleIp.removeAllItems();
        cmbTailscaleIp.addItem("auto");
        String ts = NetUtils.detectTailscaleIp();
        if (ts != null) cmbTailscaleIp.addItem(ts);
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        String item = a.getHostAddress() + " (" + ni.getDisplayName() + ")";
                        cmbTailscaleIp.addItem(item);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (prev != null) cmbTailscaleIp.setSelectedItem(prev);
    }

    public final void refreshPrismInstances() {
        // Mantenuto per compatibilità con chiamanti esterni; le istanze Prism sono ora gestite per-mondo.
    }

    public final void loadFromDisk() {
        try {
            ObjectNode node = controller.configFixer().read();
            txtPlayerId.setText(text(node, "playerId", "player_alpha"));
            cmbTailscaleIp.setSelectedItem(text(node, "localTailscaleIp", "auto"));
            cmbBackend.setSelectedItem(text(node, "storageBackend", "GIT"));
            txtGitUrl.setText(text(node, "gitRemoteUrl", ""));
            txtGitToken.setText(text(node, "gitToken", ""));
            txtGitBranch.setText(text(node, "gitBranch", "mcsync-state"));
            txtCurseforgeKey.setText(text(node, "curseforgeApiKey", ""));

            Config cfg = controller.reloadConfigQuietly();
            if (cfg != null) {
                int[] ram = ConfigFixer.readRam(cfg);
                sliderRamMin.setValue(Math.max(1, Math.min(32, ram[0])));
                sliderRamMax.setValue(Math.max(2, Math.min(32, ram[1])));
            }
        } catch (Exception e) {
            controller.notifyStatus("Impossibile leggere config.json: " + e.getMessage(), true);
        }
    }

    private void saveToDisk() {
        try {
            String rawIp = String.valueOf(cmbTailscaleIp.getSelectedItem()).trim();
            int paren = rawIp.indexOf(' ');
            if (paren > 0) rawIp = rawIp.substring(0, paren).trim();

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("playerId", txtPlayerId.getText().trim());
            updates.put("localTailscaleIp", rawIp.isEmpty() ? "auto" : rawIp);
            updates.put("storageBackend", String.valueOf(cmbBackend.getSelectedItem()));
            updates.put("gitRemoteUrl", txtGitUrl.getText().trim());
            updates.put("gitToken", new String(txtGitToken.getPassword()).trim());
            updates.put("gitBranch", txtGitBranch.getText().trim());
            updates.put("curseforgeApiKey", new String(txtCurseforgeKey.getPassword()).trim());
            updates.put("acceptEula", true);

            controller.configFixer().set(updates);
            Config cfg = Config.load(controller.configPath());
            ConfigFixer.applyRam(controller.configPath(), cfg, sliderRamMin.getValue(), sliderRamMax.getValue());

            controller.notifyStatus("Impostazioni salvate con successo in config.json!", false);
            controller.refreshDashboardAsync();
        } catch (Exception e) {
            controller.notifyStatus("Configurazione non valida: " + e.getMessage(), true);
            JOptionPane.showMessageDialog(this, e.getMessage(), "Errore Configurazione", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void runConnectionTest() {
        btnTestConnection.setEnabled(false);
        lblTestResult.setForeground(new Color(0x8B949E));
        lblTestResult.setText("Verifica accesso al repository in corso...");
        try {
            saveToDisk();
            Config cfg = Config.load(controller.configPath());
            controller.testStorageConnectionAsync(cfg, ok -> {
                btnTestConnection.setEnabled(true);
                lblTestResult.setForeground(new Color(0x3FB950));
                lblTestResult.setText(ok);
            }, err -> {
                btnTestConnection.setEnabled(true);
                lblTestResult.setForeground(new Color(0xF85149));
                lblTestResult.setText("Errore: " + err);
            });
        } catch (Exception e) {
            btnTestConnection.setEnabled(true);
            lblTestResult.setForeground(new Color(0xF85149));
            lblTestResult.setText("Errore: " + e.getMessage());
        }
    }

    private static String text(ObjectNode n, String key, String def) {
        JsonNode v = n.get(key);
        return v != null && v.isTextual() ? v.asText() : def;
    }

    private static void addFormRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(6, 4, 6, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        JLabel l = new JLabel(label);
        l.setForeground(new Color(0x8B949E));
        form.add(l, c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
    }
}
