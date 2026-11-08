package com.mcsync.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Custom animated Swing components (60 FPS smooth interpolation, neon glows, pulsing status orbs,
 * animated gradient progress bars, and hover-elevated action buttons).
 */
public final class UiAnimations {

    public static final Color BG_DARK = new Color(0x0D1117);
    public static final Color BG_CARD = new Color(0x161B22);
    public static final Color BG_CARD_HOVER = new Color(0x1F2630);
    public static final Color BORDER_SUBTLE = new Color(0x30363D);
    public static final Color TEXT_PRIMARY = new Color(0xE6EDF3);
    public static final Color TEXT_MUTED = new Color(0x8B949E);
    public static final Color ACCENT_GREEN = new Color(0x2EA043);
    public static final Color ACCENT_GREEN_GLOW = new Color(0x3FB950);
    public static final Color ACCENT_BLUE = new Color(0x2F81F7);
    public static final Color ACCENT_PURPLE = new Color(0x8957E5);
    public static final Color ACCENT_ORANGE = new Color(0xD29922);
    public static final Color ACCENT_RED = new Color(0xF85149);

    private UiAnimations() {
    }

    /**
     * Animated pulsing status badge for the top header bar (shows a breathing neon ring around the status dot).
     */
    public static final class PulsingBadge extends JPanel {
        private Color statusColor = TEXT_MUTED;
        private String title;
        private String value;
        private float phase = 0f;
        private float hover = 0f;
        private boolean mouseOver = false;

        public PulsingBadge(String title, String initialValue) {
            this.title = title;
            this.value = initialValue;
            setOpaque(false);
            setPreferredSize(new Dimension(235, 42));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            Timer timer = new Timer(25, e -> {
                phase = (phase + 0.09f) % (float) (Math.PI * 2);
                float target = mouseOver ? 1f : 0f;
                hover += (target - hover) * 0.2f;
                repaint();
            });
            timer.start();

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    mouseOver = true;
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    mouseOver = false;
                }
            });
        }

        public void updateBadge(Color color, String newValue) {
            this.statusColor = color;
            this.value = newValue;
            repaint();
        }

        public void setBadge(String newValue, Color color) {
            updateBadge(color, newValue);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            int w = getWidth();
            int h = getHeight();

            // Pill background with subtle hover glow
            Color bg = blend(new Color(0x1C2128), new Color(0x262C36), hover);
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 16, 16));

            Color borderCol = blend(BORDER_SUBTLE, statusColor, 0.35f + 0.35f * hover);
            g2.setColor(borderCol);
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, 16, 16));

            // Breathing pulse halo around the dot
            float pulse = (float) ((Math.sin(phase) + 1.0) * 0.5); // 0..1
            float haloRadius = 6f + pulse * 5.5f;
            int dotCx = 20;
            int dotCy = h / 2;

            g2.setColor(withAlpha(statusColor, (int) (75 * (1f - pulse * 0.6f))));
            g2.fill(new Ellipse2D.Float(dotCx - haloRadius, dotCy - haloRadius, haloRadius * 2, haloRadius * 2));

            // Core glowing dot
            g2.setColor(statusColor);
            g2.fill(new Ellipse2D.Float(dotCx - 5, dotCy - 5, 10, 10));

            // Labels
            g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
            g2.setColor(TEXT_MUTED);
            g2.drawString(title.toUpperCase(), 36, 16);

            g2.setFont(getFont().deriveFont(Font.BOLD, 12.5f));
            g2.setColor(TEXT_PRIMARY);
            g2.drawString(shorten(value, 24), 36, 32);

            g2.dispose();
        }
    }

    /**
     * Primary hero button with animated gradient, hover scale/glow, and active pulsing border.
     */
    public static final class AnimatedHeroButton extends JButton {
        private Color colorStart = new Color(0x238636);
        private Color colorEnd = new Color(0x2EA043);
        private float hoverProgress = 0f;
        private float shimmerPhase = 0f;
        private boolean hovered = false;
        private boolean pulsing = false;

        public AnimatedHeroButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 15f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(380, 50));

            Timer anim = new Timer(20, e -> {
                float target = hovered ? 1f : 0f;
                hoverProgress += (target - hoverProgress) * 0.18f;
                shimmerPhase = (shimmerPhase + 0.025f) % 1.0f;
                if (hovered || hoverProgress > 0.01f || pulsing) {
                    repaint();
                }
            });
            anim.start();

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });
        }

        public void setPalette(Color start, Color end, boolean pulse) {
            this.colorStart = start;
            this.colorEnd = end;
            this.pulsing = pulse;
            repaint();
        }

        public void setHeroStyle(String text, Color start, Color end, boolean pulse) {
            setText(text);
            setPalette(start, end, pulse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            int w = getWidth();
            int h = getHeight();

            Color c1 = blend(colorStart, Color.WHITE, hoverProgress * 0.12f);
            Color c2 = blend(colorEnd, Color.WHITE, hoverProgress * 0.18f);

            // Outer glow
            if (hoverProgress > 0.02f || pulsing) {
                float pulseAlpha = pulsing ? (float) (0.25 + 0.2 * Math.sin(shimmerPhase * Math.PI * 2)) : hoverProgress * 0.35f;
                g2.setColor(withAlpha(c2, (int) (pulseAlpha * 255)));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
            }

            // Main gradient body
            GradientPaint gp = new GradientPaint(0, 0, c1, w, h, c2);
            g2.setPaint(gp);
            g2.fill(new RoundRectangle2D.Float(3, 3, w - 6, h - 6, 14, 14));

            // Subtle moving light sweep on hover/pulse
            if (hovered || pulsing) {
                float sweepX = shimmerPhase * (w + 160) - 80;
                LinearGradientPaint sweep = new LinearGradientPaint(
                        sweepX - 60, 0, sweepX + 60, h,
                        new float[]{0f, 0.5f, 1f},
                        new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 45), new Color(255, 255, 255, 0)});
                g2.setPaint(sweep);
                g2.fill(new RoundRectangle2D.Float(3, 3, w - 6, h - 6, 14, 14));
            }

            // Crisp top highlight border
            g2.setColor(new Color(255, 255, 255, (int) (45 + 45 * hoverProgress)));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new RoundRectangle2D.Float(3.5f, 3.5f, w - 7, h - 7, 14, 14));

            // Centered text
            FontMetrics fm = g2.getFontMetrics(getFont());
            String text = getText();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setFont(getFont());
            g2.setColor(new Color(0, 0, 0, 90));
            g2.drawString(text, tx, ty + 1);
            g2.setColor(getForeground());
            g2.drawString(text, tx, ty);

            g2.dispose();
        }
    }

    /**
     * Smoothly animated gradient progress bar with indeterminate cyber-wave and percentage counter.
     */
    public static final class SmoothProgressBar extends JComponent {
        private float displayedValue = 0f;
        private float targetValue = 0f;
        private boolean indeterminate = false;
        private boolean active = false;
        private float wavePhase = 0f;
        private String statusText = "Pronto";

        public SmoothProgressBar() {
            setPreferredSize(new Dimension(400, 30));
            setOpaque(false);

            Timer timer = new Timer(16, e -> {
                wavePhase = (wavePhase + 0.022f) % 1.0f;
                float diff = targetValue - displayedValue;
                if (Math.abs(diff) > 0.1f) {
                    displayedValue += diff * 0.16f;
                    repaint();
                } else if (active || indeterminate) {
                    displayedValue = targetValue;
                    repaint();
                }
            });
            timer.start();
        }

        public void setProgressState(int percent, String detail, boolean isActive) {
            this.active = isActive;
            if (percent < 0) {
                this.indeterminate = isActive;
            } else {
                this.indeterminate = false;
                this.targetValue = Math.max(0, Math.min(100, percent));
            }
            if (detail != null && !detail.isBlank()) {
                this.statusText = detail;
            }
            repaint();
        }

        public void IdleText(String text) {
            this.active = false;
            this.indeterminate = false;
            this.targetValue = 0f;
            this.statusText = text != null ? text : "Pronto";
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            int w = getWidth();
            int h = getHeight();

            // Track
            g2.setColor(new Color(0x12161C));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));

            if (indeterminate && active) {
                int barW = Math.max(90, w / 3);
                float x = wavePhase * (w + barW) - barW;
                GradientPaint gp = new GradientPaint(x, 0, ACCENT_BLUE, x + barW, 0, ACCENT_PURPLE);
                g2.setPaint(gp);
                g2.setClip(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 11, 11));
                g2.fill(new RoundRectangle2D.Float(x, 1, barW, h - 2, 10, 10));
                g2.setClip(null);
            } else if (displayedValue > 0.5f) {
                int fillW = (int) ((w - 2) * (displayedValue / 100f));
                GradientPaint gp = new GradientPaint(0, 0, ACCENT_BLUE, w, 0, ACCENT_GREEN_GLOW);
                g2.setPaint(gp);
                g2.setClip(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 11, 11));
                g2.fill(new RoundRectangle2D.Float(1, 1, Math.max(12, fillW), h - 2, 10, 10));

                if (active) {
                    float sweepX = wavePhase * (fillW + 120) - 60;
                    LinearGradientPaint shine = new LinearGradientPaint(
                            sweepX - 45, 0, sweepX + 45, 0,
                            new float[]{0f, 0.5f, 1f},
                            new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 70), new Color(255, 255, 255, 0)});
                    g2.setPaint(shine);
                    g2.fillRect(1, 1, fillW, h - 2);
                }
                g2.setClip(null);
            }

            // Border
            g2.setColor(active ? blend(BORDER_SUBTLE, ACCENT_BLUE, 0.5f) : BORDER_SUBTLE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 12, 12));

            // Overlay label
            String label = statusText;
            if (!indeterminate && displayedValue > 0.5f && active) {
                label = String.format("%s  (%d%%)", shorten(statusText, 75), Math.round(displayedValue));
            } else {
                label = shorten(statusText, 85);
            }
            g2.setFont(getFont().deriveFont(Font.BOLD, 12f));
            FontMetrics fm = g2.getFontMetrics();
            int tx = 14;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(new Color(0, 0, 0, 140));
            g2.drawString(label, tx, ty + 1);
            g2.setColor(TEXT_PRIMARY);
            g2.drawString(label, tx, ty);

            g2.dispose();
        }
    }

    public static Color blend(Color a, Color b, float ratio) {
        float t = Math.max(0f, Math.min(1f, ratio));
        int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, g, bl);
    }

    public static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String single = s.replace('\n', ' ').trim();
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }
}
