package brblocks;

import javax.swing.*;
import java.awt.*;

/** Light and dark colors (the same ones EVIA Paradigma uses) and FlatLaf when it is on the class path. */
final class Theme {
    static boolean dark;

    private Theme() {
    }

    static Color canvas() { return dark ? new Color(43, 45, 48) : new Color(248, 249, 252); }
    static Color grid() { return dark ? new Color(60, 63, 66) : new Color(214, 218, 228); }
    static Color wire() { return dark ? new Color(170, 176, 192) : new Color(70, 75, 90); }
    static Color wireUp() { return dark ? new Color(95, 190, 105) : new Color(50, 150, 60); }
    static Color wireDown() { return dark ? new Color(240, 160, 70) : new Color(215, 120, 20); }
    static Color select() { return dark ? new Color(90, 150, 255) : new Color(30, 90, 210); }
    static Color muted() { return dark ? new Color(150, 154, 160) : Color.GRAY; }

    /** Block colors per category, taken from EVIA Paradigma's block palette. */
    static Color category(String c) {
        return switch (c) {
            case "board" -> new Color(126, 200, 128);      // like Start
            case "toolchain" -> new Color(250, 168, 92);   // like For
            case "bootloader" -> new Color(240, 142, 130); // like If
            case "kernel" -> new Color(196, 160, 230);     // like Input
            case "rootfs" -> new Color(242, 202, 112);     // like Print
            case "images" -> new Color(100, 176, 208);     // like UART
            case "skeleton" -> new Color(165, 165, 170);   // like End
            case "host" -> new Color(142, 206, 186);       // like Analog Read
            default -> new Color(132, 172, 240);           // like Set
        };
    }

    static boolean hasFlatLaf() {
        try {
            Class.forName("com.formdev.flatlaf.FlatLaf");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static void apply(boolean d) {
        dark = d;
        try {
            if (hasFlatLaf()) {
                String laf = d ? "com.formdev.flatlaf.FlatDarculaLaf" : "com.formdev.flatlaf.FlatIntelliJLaf";
                UIManager.setLookAndFeel(laf);
            } else {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            }
        } catch (Exception ignored) {
        }
        for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w);
    }
}
