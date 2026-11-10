import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Draws The Grid's 256x256 icon: three blocks on a dot grid.  java MakeIcon.java out.png */
public class MakeIcon {
    public static void main(String[] a) throws Exception {
        int S = 256;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(new Color(43, 45, 48));
        g.fill(new RoundRectangle2D.Float(8, 8, S - 16, S - 16, 48, 48));
        g.setColor(new Color(75, 79, 84));
        for (int x = 28; x < S - 20; x += 20)
            for (int y = 28; y < S - 20; y += 20) g.fillRect(x, y, 3, 3);

        Point top = new Point(128, 82), l = new Point(76, 190), r = new Point(180, 190);
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(170, 176, 192));
        for (Point p : new Point[]{l, r}) {
            g.draw(new CubicCurve2D.Float(top.x, top.y, top.x, top.y + 40, p.x, p.y - 64, p.x, p.y - 26));
        }
        block(g, 128, 56, new Color(126, 200, 128));
        block(g, 76, 164, new Color(132, 172, 240));
        block(g, 180, 164, new Color(250, 168, 92));
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }

    static void block(Graphics2D g, int cx, int cy, Color c) {
        int w = 88, h = 52;
        float x = cx - w / 2f, y = cy - h / 2f;
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Float(x + 4, y + 4, w, h, 18, 18));
        g.setPaint(new GradientPaint(0, y, blend(c, Color.WHITE, 0.45f), 0, y + h, c));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 18, 18));
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(blend(c, Color.BLACK, 0.45f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 18, 18));
        g.setColor(new Color(0, 0, 0, 120));
        g.fill(new RoundRectangle2D.Float(x + 12, y + 13, 36, 7, 4, 4));
        g.setColor(new Color(20, 20, 30, 200));
        g.fill(new RoundRectangle2D.Float(x + 12, y + 29, 60, 9, 4, 4));
        port(g, cx, y, Color.WHITE);
        port(g, cx, y + h, Color.DARK_GRAY);
    }

    static void port(Graphics2D g, float x, float y, Color fill) {
        Ellipse2D e = new Ellipse2D.Float(x - 8, y - 8, 16, 16);
        g.setColor(fill);
        g.fill(e);
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(new Color(40, 40, 40));
        g.draw(e);
    }

    static Color blend(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
