package com.mcsync.gui;

import com.mcsync.diagnostics.LogAnalyzer;
import com.mcsync.gui.controller.OrchestratorController;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URI;
import java.util.List;

/**
 * Schermata 4: Centro Diagnostico Intelligente (Log & Assistente Risoluzione Problemi).
 *
 * <ul>
 *     <li>Rileva automaticamente errori noti nei log tramite {@link LogAnalyzer}</li>
 *     <li>Mostra Box Errore, Spiegazione Umana e Pulsante di Correzione Automatica a 1 clic</li>
 *     <li>Visualizza l'intero flusso log del launcher in basso</li>
 * </ul>
 */
public final class DiagnosticsPanel extends JPanel {

    private final OrchestratorController controller;
    private final MainFrame mainFrame;

    private final JPanel cardsContainer = new JPanel();
    private final JTextArea logArea = new JTextArea();
    private final JLabel lblCount = new JLabel("Nessuna anomalia rilevata nei log");

    public DiagnosticsPanel(OrchestratorController controller, MainFrame mainFrame) {
        super(new BorderLayout(12, 12));
        this.controller = controller;
        this.mainFrame = mainFrame;
        setBorder(new EmptyBorder(16, 20, 16, 20));
        setOpaque(false);

        // Top toolbar
        JPanel topBar = new JPanel(new BorderLayout(10, 0));
        topBar.setOpaque(false);
        lblCount.setFont(lblCount.getFont().deriveFont(Font.BOLD, 14f));
        topBar.add(lblCount, BorderLayout.WEST);

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnGroup.setOpaque(false);
        JButton btnAnalyze = new JButton("Rianalizza Log");
        JButton btnForceUnlock = new JButton("Sblocca Sessione (Force Unlock)");
        JButton btnClear = new JButton("Pulisci Log");
        btnAnalyze.addActionListener(e -> refreshDiagnoses());
        btnForceUnlock.addActionListener(e -> controller.forceUnlockAsync());
        btnClear.addActionListener(e -> {
            LogBus.clear();
            logArea.setText("");
            refreshDiagnoses();
        });
        btnGroup.add(btnAnalyze);
        btnGroup.add(btnForceUnlock);
        btnGroup.add(btnClear);
        topBar.add(btnGroup, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // Center split: Diagnosis cards (top) + Full launcher log (bottom)
        cardsContainer.setLayout(new BoxLayout(cardsContainer, BoxLayout.Y_AXIS));
        cardsContainer.setOpaque(false);
        JScrollPane cardsScroll = new JScrollPane(cardsContainer);
        cardsScroll.setBorder(BorderFactory.createTitledBorder("Schede di Diagnostica Guidata & Auto-Fix"));
        cardsScroll.getVerticalScrollBar().setUnitIncrement(16);

        logArea.setEditable(false);
        logArea.setBackground(new Color(0x121417));
        logArea.setForeground(new Color(0xD0D7DE));
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Registro Completo del Launcher (System.out / System.err)"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, cardsScroll, logScroll);
        split.setDividerLocation(250);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        // Populate existing lines and listen to new ones
        for (String line : LogBus.snapshot()) {
            logArea.append(line + "\n");
        }
        LogBus.addListener(line -> SwingUtilities.invokeLater(() -> onNewLogLine(line)));
        refreshDiagnoses();
    }

    private void onNewLogLine(String line) {
        if (logArea.getDocument().getLength() > 300_000) {
            logArea.setText("");
        }
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        if (LogAnalyzer.match(line) != null) {
            refreshDiagnoses();
            mainFrame.highlightDiagnosticsTab();
        }
    }

    public final void refreshDiagnoses() {
        com.mcsync.config.Config cfg = controller.currentConfig();
        String worldName = cfg != null ? cfg.activeWorld().map(w -> w.displayName()).orElse(cfg.activeWorldId()) : null;
        List<LogAnalyzer.Diagnosis> list = LogAnalyzer.analyze(LogBus.snapshot(), worldName);
        cardsContainer.removeAll();

        if (list.isEmpty()) {
            lblCount.setText("Nessun problema noto rilevato nei log");
            lblCount.setForeground(new Color(0x3FB950));
            JPanel okCard = DashboardPanel.createCard("STATO DIAGNOSTICO: TUTTO REGOLARE");
            JLabel msg = new JLabel("Non sono presenti errori noti (getsockopt, run.bat, Java 8, token GitHub o lock bloccati).");
            msg.setForeground(new Color(0x8B949E));
            okCard.add(msg, BorderLayout.CENTER);
            cardsContainer.add(okCard);
        } else {
            lblCount.setText("Rilevati " + list.size() + " problemi con soluzione guidata disponibile");
            lblCount.setForeground(new Color(0xF85149));
            for (LogAnalyzer.Diagnosis d : list) {
                cardsContainer.add(buildDiagnosisCard(d));
                cardsContainer.add(Box.createVerticalStrut(10));
            }
        }
        cardsContainer.revalidate();
        cardsContainer.repaint();
    }

    private JPanel buildDiagnosisCard(LogAnalyzer.Diagnosis d) {
        JPanel card = new JPanel(new BorderLayout(12, 8));
        card.setBackground(new Color(0x281D20));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xF85149), 1, true),
                new EmptyBorder(12, 14, 12, 14)));

        JLabel title = new JLabel("⚠ " + d.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setForeground(new Color(0xFF7B72));

        JTextArea explain = new JTextArea(
                "Cosa significa: " + d.explanation() + "\nEstratto dal log: " + d.evidence());
        explain.setEditable(false);
        explain.setLineWrap(true);
        explain.setWrapStyleWord(true);
        explain.setOpaque(false);
        explain.setForeground(new Color(0xE6EDF3));
        explain.setFont(explain.getFont().deriveFont(12.5f));

        card.add(title, BorderLayout.NORTH);
        card.add(explain, BorderLayout.CENTER);

        if (d.fix() != LogAnalyzer.FixAction.NONE) {
            JButton btnFix = new JButton("🔧 " + d.fix().label);
            btnFix.setBackground(new Color(0x238636));
            btnFix.setForeground(Color.WHITE);
            btnFix.setFont(btnFix.getFont().deriveFont(Font.BOLD, 12.5f));
            btnFix.addActionListener(e -> executeFix(d.fix()));
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            east.setOpaque(false);
            east.add(btnFix);
            card.add(east, BorderLayout.EAST);
        }
        return card;
    }

    private void executeFix(LogAnalyzer.FixAction fix) {
        try {
            switch (fix) {
                case FORCE_UNLOCK -> controller.forceUnlockAsync();
                case PULL_MODS -> controller.syncModsAsync(false);
                case OPEN_CONFIG -> mainFrame.switchToTab(2);
                case OPEN_TAILSCALE_ADMIN -> {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI.create("https://login.tailscale.com/admin/machines"));
                    }
                }
                default -> {
                    String msg = controller.configFixer().apply(fix, controller.currentConfig());
                    controller.reloadConfigQuietly();
                    mainFrame.reloadConfigTab();
                    controller.refreshDashboardAsync();
                    controller.notifyStatus("Fix applicato: " + msg, false);
                    JOptionPane.showMessageDialog(this, msg, "Correzione Automatica Applicata",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        } catch (Exception ex) {
            controller.notifyStatus("Impossibile applicare il fix: " + ex.getMessage(), true);
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Errore Fix", JOptionPane.ERROR_MESSAGE);
        }
    }
}
