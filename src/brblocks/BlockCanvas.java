package brblocks;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The block diagram: dot grid, wires, blocks. Wheel zooms, dragging empty space pans,
 * dragging a block moves it, clicking selects it and lights up what it needs (green)
 * and what needs it (orange); double-click opens it.
 */
final class BlockCanvas extends JComponent {
    private Graph graph = new Graph();
    private double zoom = 1, ox, oy;      // screen = model * zoom + offset
    private Graph.Node selected;
    private Set<Graph.Node> up = Set.of(), down = Set.of(), matches = Set.of();
    private Consumer<Graph.Node> onSelect = n -> {};
    private String emptyText = "";

    BlockCanvas() {
        setFocusable(true);
        setToolTipText("");
        Mouse m = new Mouse();
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_F -> fit();
                    case KeyEvent.VK_0 -> zoomAt(1 / zoom, getWidth() / 2.0, getHeight() / 2.0);
                    case KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> zoomAt(1.2, getWidth() / 2.0, getHeight() / 2.0);
                    case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> zoomAt(1 / 1.2, getWidth() / 2.0, getHeight() / 2.0);
                    case KeyEvent.VK_ESCAPE -> select(null);
                    case KeyEvent.VK_ENTER -> { if (selected != null && selected.open != null) selected.open.run(); }
                    default -> { }
                }
            }
        });
    }

    void onSelect(Consumer<Graph.Node> c) {
        onSelect = c;
    }

    void setEmptyText(String s) {
        emptyText = s;
        repaint();
    }

    Graph graph() {
        return graph;
    }

    /** Shows a graph: sizes the blocks, lays them out and fits them in the window. */
    void setGraph(Graph g) {
        graph = g;
        FontMetrics c = getFontMetrics(Block.CODE_FONT), t = getFontMetrics(Block.TITLE_FONT),
                m = getFontMetrics(Block.META_FONT);
        for (Graph.Node n : g.nodes) n.w = Block.width(n, c, t, m);
        g.layout();
        selected = null;
        up = down = matches = Set.of();
        onSelect.accept(null);
        fit();
    }

    void select(Graph.Node n) {
        selected = n;
        up = n == null ? Set.of() : graph.reach(n, false);
        down = n == null ? Set.of() : graph.reach(n, true);
        onSelect.accept(n);
        repaint();
    }

    Graph.Node selected() {
        return selected;
    }

    void setMatches(Set<Graph.Node> m) {
        matches = m;
        repaint();
    }

    /** Scrolls so the node sits in the middle. */
    void center(Graph.Node n) {
        ox = getWidth() / 2.0 - (n.x + n.w / 2.0) * zoom;
        oy = getHeight() / 2.0 - (n.y + Block.H / 2.0) * zoom;
        repaint();
    }

    void fit() {
        if (graph.nodes.isEmpty() || getWidth() == 0) {
            repaint();
            return;
        }
        Rectangle2D b = diagramBounds();
        double m = 40;
        zoom = Math.max(0.15, Math.min(1.25, Math.min((getWidth() - 2 * m) / b.getWidth(),
                (getHeight() - 2 * m) / b.getHeight())));
        ox = getWidth() / 2.0 - b.getCenterX() * zoom;
        oy = getHeight() / 2.0 - b.getCenterY() * zoom;
        repaint();
    }

    private Rectangle2D diagramBounds() {
        Rectangle2D r = null;
        for (Graph.Node n : graph.nodes) {
            Rectangle2D nb = new Rectangle2D.Double(n.x - Block.PAD, n.y - Block.PAD,
                    n.w + 2 * Block.PAD, Block.H + 2 * Block.PAD);
            if (r == null) r = nb; else r.add(nb);
        }
        return r == null ? new Rectangle2D.Double() : r;
    }

    private void zoomAt(double f, double sx, double sy) {
        double nz = Math.max(0.1, Math.min(3, zoom * f));
        double mx = (sx - ox) / zoom, my = (sy - oy) / zoom;
        zoom = nz;
        ox = sx - mx * zoom;
        oy = sy - my * zoom;
        repaint();
    }

    private Point2D toModel(Point p) {
        return new Point2D.Double((p.x - ox) / zoom, (p.y - oy) / zoom);
    }

    private Graph.Node nodeAt(Point p) {
        Point2D m = toModel(p);
        for (int i = graph.nodes.size() - 1; i >= 0; i--) {
            Graph.Node n = graph.nodes.get(i);
            if (m.getX() >= n.x && m.getX() <= n.x + n.w && m.getY() >= n.y && m.getY() <= n.y + Block.H) return n;
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Graph.Node n = nodeAt(e.getPoint());
        if (n == null) return null;
        return "<html><b>" + n.label + "</b><br>" + n.title.toLowerCase()
                + (n.open != null ? "<br><i>Double-click to open</i>" : "") + "</html>";
    }

    // ================================================================ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Theme.canvas());
        g.fillRect(0, 0, getWidth(), getHeight());

        // dot grid every 20 diagram units, thinned out when zoomed far out
        g.setColor(Theme.grid());
        double step = 20 * zoom;
        while (step < 10) step *= 2;
        int dot = zoom >= 0.75 ? 2 : 1;
        double sx = ((ox % step) + step) % step, sy = ((oy % step) + step) % step;
        for (double x = sx; x < getWidth(); x += step)
            for (double y = sy; y < getHeight(); y += step)
                g.fillRect((int) Math.round(x), (int) Math.round(y), dot, dot);

        if (graph.nodes.isEmpty()) {
            g.setColor(Theme.muted());
            g.setFont(getFont() == null ? Block.TITLE_FONT : getFont().deriveFont(14f));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(emptyText, (getWidth() - fm.stringWidth(emptyText)) / 2, getHeight() / 2);
            g.dispose();
            return;
        }

        g.translate(ox, oy);
        g.scale(zoom, zoom);
        boolean focus = selected != null;
        Composite normal = g.getComposite(), faded = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f);

        // wires: faded ones first, lit ones on top
        Set<Graph.Node> upAll = new HashSet<>(up), downAll = new HashSet<>(down);
        if (selected != null) { upAll.add(selected); downAll.add(selected); }
        for (int pass = 0; pass < 2; pass++) {
            for (Graph.Edge e : graph.edges) {
                boolean isUp = focus && upAll.contains(e.from()) && upAll.contains(e.to());
                boolean isDown = focus && downAll.contains(e.from()) && downAll.contains(e.to());
                boolean lit = isUp || isDown;
                if ((pass == 1) != lit && focus) continue;
                if (pass == 1 && !focus) continue;
                g.setComposite(focus && !lit ? faded : normal);
                g.setColor(isUp ? Theme.wireUp() : isDown ? Theme.wireDown() : Theme.wire());
                g.setStroke(new BasicStroke(lit ? 2.8f : 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                CubicCurve2D c = curve(Block.outPort(e.from()), Block.inPort(e.to()));
                g.draw(c);
                arrow(g, c);
            }
        }

        for (Graph.Node n : graph.nodes) {
            boolean related = !focus || n == selected || up.contains(n) || down.contains(n) || matches.contains(n);
            g.setComposite(related ? normal : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            int state = n == selected ? 1 : matches.contains(n) ? 2 : 0;
            Block.paint(g, n, graph.hasOutput(n), state, Theme.select());
        }
        g.dispose();
    }

    private static CubicCurve2D curve(Point a, Point b) {
        double dy = Math.max(40, Math.abs(b.y - a.y) / 2.0);
        return new CubicCurve2D.Double(a.x, a.y, a.x, a.y + dy, b.x, b.y - dy, b.x, b.y);
    }

    private static void arrow(Graphics2D g, CubicCurve2D c) {
        double angle = Math.atan2(c.getY2() - c.getCtrlY2(), c.getX2() - c.getCtrlX2());
        double tipX = c.getX2(), tipY = c.getY2() - Block.PORT_R;
        double len = 11, spread = Math.toRadians(25);
        Path2D head = new Path2D.Double();
        head.moveTo(tipX, tipY);
        head.lineTo(tipX - len * Math.cos(angle - spread), tipY - len * Math.sin(angle - spread));
        head.lineTo(tipX - len * Math.cos(angle + spread), tipY - len * Math.sin(angle + spread));
        head.closePath();
        g.fill(head);
    }

    // ================================================================ mouse

    private final class Mouse extends MouseAdapter {
        private Point last;
        private Graph.Node dragging;

        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            last = e.getPoint();
            dragging = SwingUtilities.isLeftMouseButton(e) ? nodeAt(e.getPoint()) : null;
            if (dragging != null && dragging != selected) select(dragging);
            setCursor(Cursor.getPredefinedCursor(dragging != null ? Cursor.MOVE_CURSOR : Cursor.HAND_CURSOR));
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (last == null) return;
            double dx = e.getX() - last.x, dy = e.getY() - last.y;
            if (dragging != null) {
                dragging.x += dx / zoom;
                dragging.y += dy / zoom;
            } else {
                ox += dx;
                oy += dy;
            }
            last = e.getPoint();
            repaint();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            last = null;
            dragging = null;
            setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            Graph.Node n = nodeAt(e.getPoint());
            if (e.getClickCount() == 1 && n == null) select(null);
            if (e.getClickCount() == 2 && n != null && n.open != null) n.open.run();
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            zoomAt(Math.pow(1.1, -e.getPreciseWheelRotation()), e.getX(), e.getY());
        }
    }
}
