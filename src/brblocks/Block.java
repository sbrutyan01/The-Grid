package brblocks;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/** Paints one block the way EVIA Paradigma does: rounded gradient body, title, code line, ports. */
final class Block {
    static final int MIN_W = 170, MAX_W = 360, H = 54, PORT_R = 6, PAD = 8;

    static final Font TITLE_FONT = new Font(Font.DIALOG, Font.BOLD, 11);
    static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    static final Font META_FONT = new Font(Font.DIALOG, Font.PLAIN, 10);

    private Block() {
    }

    /** Body width for the node's text. */
    static int width(Graph.Node n, FontMetrics code, FontMetrics title, FontMetrics meta) {
        int t = title.stringWidth(n.title) + (n.meta.isEmpty() ? 0 : meta.stringWidth(n.meta) + 16);
        int need = Math.max(code.stringWidth(n.label), t) + 26;
        return Math.max(MIN_W, Math.min(MAX_W, need));
    }

    /** Paints in diagram units; state: 0 normal, 1 selected, 2 search match. */
    static void paint(Graphics2D g, Graph.Node n, boolean output, int state, Color selectColor) {
        float x = (float) n.x, y = (float) n.y;
        int w = n.w;
        Color base = n.color;
        RoundRectangle2D body = new RoundRectangle2D.Float(x, y, w, H, 14, 14);

        g.setColor(new Color(0, 0, 0, 35));
        g.fill(new RoundRectangle2D.Float(x + 3, y + 3, w, H, 14, 14));

        g.setPaint(new GradientPaint(0, y, blend(base, Color.WHITE, 0.45f), 0, y + H, base));
        g.fill(body);

        Color border;
        float bw;
        if (state == 1) { border = selectColor; bw = 2.5f; }
        else if (state == 2) { border = new Color(255, 150, 0); bw = 3.5f; }
        else { border = blend(base, Color.BLACK, 0.45f); bw = 1.2f; }
        g.setStroke(n.dashed && state == 0
                ? new BasicStroke(bw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 4f}, 0f)
                : new BasicStroke(bw));
        g.setColor(border);
        g.draw(body);

        int metaW = 0;
        if (!n.meta.isEmpty()) {
            g.setFont(META_FONT);
            FontMetrics fm = g.getFontMetrics();
            metaW = fm.stringWidth(n.meta) + 12;
            g.setColor(new Color(0, 0, 0, 130));
            g.drawString(n.meta, x + w - 9 - fm.stringWidth(n.meta), y + 15);
        }
        g.setFont(TITLE_FONT);
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(ellipsize(n.title, g.getFontMetrics(), w - 18 - metaW), x + 9, y + 15);

        if (!n.label.isEmpty()) {
            g.setFont(CODE_FONT);
            g.setColor(new Color(20, 20, 30));
            g.drawString(ellipsize(n.label, g.getFontMetrics(), w - 18), x + 9, y + 38);
        }

        if (n.hasInput) port(g, inPort(n), Color.WHITE);
        if (output) port(g, outPort(n), Color.DARK_GRAY);
    }

    static Point inPort(Graph.Node n) {
        return new Point((int) Math.round(n.x + n.w / 2.0), (int) Math.round(n.y));
    }

    static Point outPort(Graph.Node n) {
        return new Point((int) Math.round(n.x + n.w / 2.0), (int) Math.round(n.y + H));
    }

    private static void port(Graphics2D g, Point p, Color fill) {
        Ellipse2D e = new Ellipse2D.Float(p.x - PORT_R, p.y - PORT_R, PORT_R * 2, PORT_R * 2);
        g.setColor(fill);
        g.fill(e);
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(40, 40, 40));
        g.draw(e);
    }

    static String ellipsize(String s, FontMetrics fm, int max) {
        if (fm.stringWidth(s) <= max) return s;
        while (!s.isEmpty() && fm.stringWidth(s + "...") > max) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    static Color blend(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
