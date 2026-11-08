package com.mcsync.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.mcsync.gui.controller.OrchestratorController;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Insets;
import java.nio.file.Path;

/**
 * Installs {@link LogBus}, configures the FlatLaf theme and opens {@link MainFrame}.
 */
public final class GuiLauncher {

    private static volatile boolean darkMode = true;

    private GuiLauncher() {
    }

    public static void launch(Path configPath, boolean debug) {
        LogBus.install();
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {
            applyTheme(true);
            OrchestratorController controller = new OrchestratorController(configPath);
            MainFrame frame = new MainFrame(controller);
            frame.setVisible(true);
            controller.refreshDashboardAsync();
        });
    }

    public static boolean isDarkMode() {
        return darkMode;
    }

    public static void toggleTheme(java.awt.Window window) {
        applyTheme(!darkMode);
        if (window != null) {
            SwingUtilities.updateComponentTreeUI(window);
            window.repaint();
        }
    }

    public static void applyTheme(boolean dark) {
        darkMode = dark;
        try {
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("TabbedPane.tabInsets", new Insets(10, 18, 10, 18));
            UIManager.put("Component.focusWidth", 1);
            if (dark) {
                UIManager.put("Panel.background", new Color(0x181A1F));
                UIManager.put("TabbedPane.selectedBackground", new Color(0x232730));
            }
        } catch (Exception ignored) {
        }
    }
}
