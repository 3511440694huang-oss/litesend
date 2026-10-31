import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 轻传的猫娘吉祥物 + 图标底图（纯 JDK AWT）。
 *
 * 输出：
 *   art/mascot.png  透明底角色，用于应用内吉祥物
 *   art/icon.png    带奶油底色的 1024 方图，交给 IconMaker 生成全套 mipmap
 *
 * 造型刻意做得很「几何」：圆角多边形拼出耳朵/蝴蝶结，帽子式的刘海用 Area 裁出来，
 * 这样在 48dp 的小尺寸下轮廓依然干净，不会糊成一团。
 */
public class MascotMaker {

    // ---- 配色（与 App 主题一致：奶油 + 珊瑚粉 + 薄荷绿）
    static final Color CREAM = new Color(0xFF, 0xF3, 0xE9);
    static final Color FUR = new Color(0xFF, 0xF7, 0xF0);
    static final Color SKIN = new Color(0xFF, 0xF0, 0xE7);
    static final Color HAIR = new Color(0xF7, 0xCB, 0xA4);
    static final Color HAIR_LIGHT = new Color(0xFF, 0xE9, 0xD6);
    static final Color EAR_IN = new Color(0xFF, 0xC2, 0xC8);
    static final Color HOODIE = new Color(0xFF, 0x8F, 0xA3);
    static final Color HOODIE_DARK = new Color(0xEF, 0x74, 0x8C);
    static final Color BLUSH = new Color(0xFF, 0xAE, 0xB4);
    static final Color MINT = new Color(0x6F, 0xDD, 0xBB);
    static final Color INK = new Color(0x4A, 0x3A, 0x34);
    static final Color BELL = new Color(0xFF, 0xC4, 0x6B);
    static final Color BELL_DARK = new Color(0xE0, 0x9C, 0x3E);
    static final Color WHITE = Color.WHITE;

    static final int S = 1024;

    // 脸/头发的几何中心与半径
    static final double CX = 512, CY = 552;
    static final double FACE_RX = 192, FACE_RY = 186;
    static final double HAIR_RX = 306, HAIR_RY = 302;
    static final double BANGS_Y = 452;   // 刘海下缘

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "art");
        out.mkdirs();

        BufferedImage icon = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gi = quality(icon);
        drawBackground(gi);
        drawCharacter(gi);
        gi.dispose();
        ImageIO.write(icon, "png", new File(out, "icon.png"));

        BufferedImage mascot = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gm = quality(mascot);
        drawCharacter(gm);
        gm.dispose();
        ImageIO.write(mascot, "png", new File(out, "mascot.png"));

        System.out.println("mascot: " + new File(out, "mascot.png").getAbsolutePath());
        System.out.println("icon:   " + new File(out, "icon.png").getAbsolutePath());
    }

    static Graphics2D quality(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    // ------------------------------------------------------------------ 背景

    static void drawBackground(Graphics2D g) {
        g.setPaint(CREAM);
        g.fillRect(0, 0, S, S);
        g.setPaint(new RadialGradientPaint(
            new Point2D.Float(780, 880), 640,
            new float[]{0f, 1f},
            new Color[]{new Color(0x66FFC7A8, true), new Color(0x00FFC7A8, true)}));
        g.fillRect(0, 0, S, S);
        g.setPaint(new RadialGradientPaint(
            new Point2D.Float(200, 160), 420,
            new float[]{0f, 1f},
            new Color[]{new Color(0x339FE8D0, true), new Color(0x009FE8D0, true)}));
        g.fillRect(0, 0, S, S);

        g.setPaint(new Color(0x2EFF8FA3, true));
        g.fill(new Ellipse2D.Float(126, 690, 46, 46));
        g.setPaint(new Color(0x3AFFC46B, true));
        g.fill(new Ellipse2D.Float(866, 250, 34, 34));
        g.setPaint(new Color(0x2E7FE0C0, true));
        g.fill(new Ellipse2D.Float(842, 700, 22, 22));
        sparkle(g, 890, 148, 32, new Color(0x99FFC46B, true));
        sparkle(g, 136, 388, 20, new Color(0x669FE8D0, true));
    }

    static void sparkle(Graphics2D g, double cx, double cy, double r, Color color) {
        Path2D p = new Path2D.Double();
        double r2 = r * 0.3;
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI / 4 * i - Math.PI / 2;
            double rr = (i % 2 == 0) ? r : r2;
            double x = cx + Math.cos(ang) * rr;
            double y = cy + Math.sin(ang) * rr;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        g.setPaint(color);
        g.fill(p);
    }

    // ------------------------------------------------------------------ 角色

    static void drawCharacter(Graphics2D g) {
        drawBody(g);
        drawNeck(g);
        drawHairBack(g);
        drawEars(g);
        drawFace(g);
        drawFringe(g);
        drawFeatures(g);
        drawCollar(g);
        drawBow(g);
    }

    /** 脖子：让头不像是浮在衣服上。 */
    static void drawNeck(Graphics2D g) {
        g.setPaint(SKIN);
        g.fill(new RoundRectangle2D.Double(466, 690, 92, 130, 40, 40));
    }

    /** 珊瑚粉连帽衫肩膀 + 白色领口。 */
    static void drawBody(Graphics2D g) {
        Path2D body = new Path2D.Double();
        body.moveTo(180, 1030);
        body.curveTo(190, 862, 330, 792, 512, 792);
        body.curveTo(694, 792, 834, 862, 844, 1030);
        body.closePath();
        g.setPaint(HOODIE);
        g.fill(body);

        g.setColor(new Color(0x2EFFFFFF, true));
        g.setStroke(stroke(16f));
        g.draw(new QuadCurve2D.Double(292, 934, 330, 976, 366, 1000));
        g.draw(new QuadCurve2D.Double(708, 934, 670, 976, 634, 1000));
    }

    /** 白色领口画在脸之后，压在脖子的下缘。 */
    static void drawCollar(Graphics2D g) {
        Path2D collar = new Path2D.Double();
        collar.moveTo(416, 800);
        collar.curveTo(452, 874, 572, 874, 608, 800);
        collar.curveTo(576, 780, 448, 780, 416, 800);
        collar.closePath();
        g.setPaint(WHITE);
        g.fill(collar);
        g.setStroke(stroke(8f));
        g.setPaint(INK);
        g.draw(collar);

        // 领口的小铃铛
        double bx = 512, by = 828;
        g.setPaint(BELL);
        g.fill(new Ellipse2D.Double(bx - 26, by - 24, 52, 48));
        g.setPaint(INK);
        g.setStroke(stroke(8f));
        g.draw(new Ellipse2D.Double(bx - 26, by - 24, 52, 48));
        g.setPaint(BELL_DARK);
        g.setStroke(stroke(6f));
        g.draw(new Line2D.Double(bx - 17, by + 7, bx + 17, by + 7));
        g.setPaint(INK);
        g.fill(new Ellipse2D.Double(bx - 6, by - 1, 12, 12));
    }

    /** 后发：一整块圆顶，脸盖在中间，只露出边缘的一圈。 */
    static void drawHairBack(Graphics2D g) {
        Ellipse2D hair = new Ellipse2D.Double(CX - HAIR_RX, CY - HAIR_RY, HAIR_RX * 2, HAIR_RY * 2);
        g.setPaint(HAIR);
        g.fill(hair);
        g.setPaint(INK);
        g.setStroke(stroke(13f));
        g.draw(hair);
    }

    /** 猫耳：圆角三角形，画在头发之上、刘海之下。 */
    static void drawEars(Graphics2D g) {
        ear(g, new double[][]{{322, 372}, {330, 178}, {492, 300}});
        ear(g, new double[][]{{702, 372}, {694, 178}, {532, 300}});
    }

    static void ear(Graphics2D g, double[][] pts) {
        double[] b1 = pts[0], tip = pts[1], b2 = pts[2];
        Path2D fill = roundedPolygon(pts, 58);
        g.setPaint(FUR);
        g.fill(fill);

        // 只描两条侧边 + 圆角耳尖：底边不画，耳根就自然长在头发里，
        // 不会出现「贴上去的方块」。
        double r = 58;
        double len1 = Math.hypot(b1[0] - tip[0], b1[1] - tip[1]);
        double len2 = Math.hypot(b2[0] - tip[0], b2[1] - tip[1]);
        double ax = tip[0] + (b1[0] - tip[0]) / len1 * r;
        double ay = tip[1] + (b1[1] - tip[1]) / len1 * r;
        double bx = tip[0] + (b2[0] - tip[0]) / len2 * r;
        double by = tip[1] + (b2[1] - tip[1]) / len2 * r;
        Path2D outline = new Path2D.Double();
        outline.moveTo(b1[0], b1[1]);
        outline.lineTo(ax, ay);
        outline.quadTo(tip[0], tip[1], bx, by);
        outline.lineTo(b2[0], b2[1]);
        g.setPaint(INK);
        g.setStroke(stroke(12f));
        g.draw(outline);

        // 内耳：向重心收缩
        double cx = (b1[0] + tip[0] + b2[0]) / 3;
        double cy = (b1[1] + tip[1] + b2[1]) / 3;
        double[][] inner = new double[3][2];
        for (int i = 0; i < 3; i++) {
            inner[i][0] = cx + (pts[i][0] - cx) * 0.5;
            inner[i][1] = cy + (pts[i][1] - cy) * 0.5;
        }
        g.setPaint(EAR_IN);
        g.fill(roundedPolygon(inner, 26));
    }

    static void drawFace(Graphics2D g) {
        Ellipse2D face = new Ellipse2D.Double(CX - FACE_RX, CY - FACE_RY, FACE_RX * 2, FACE_RY * 2);
        g.setPaint(SKIN);
        g.fill(face);
        g.setPaint(INK);
        g.setStroke(stroke(12f));
        g.draw(face);
    }

    /** 刘海：拿头发圆顶和「上半平面」求交，得到帽子一样的一整块。 */
    static void drawFringe(Graphics2D g) {
        Area hair = new Area(new Ellipse2D.Double(CX - HAIR_RX, CY - HAIR_RY, HAIR_RX * 2, HAIR_RY * 2));
        Path2D clip = new Path2D.Double();
        clip.moveTo(-80, -80);
        clip.lineTo(1104, -80);
        clip.lineTo(1104, BANGS_Y);
        clip.quadTo(CX, BANGS_Y + 46, -80, BANGS_Y);
        clip.closePath();
        hair.intersect(new Area(clip));

        g.setPaint(HAIR_LIGHT);
        g.fill(hair);

        // 只描发际线：一段微弯的弧，不作第二圈外轮廓
        g.setPaint(INK);
        g.setStroke(stroke(9f));
        Path2D edge = new Path2D.Double();
        double leftX = CX - Math.sqrt(Math.max(0, 1 - Math.pow((BANGS_Y - CY) / HAIR_RY, 2))) * HAIR_RX + 4;
        double rightX = 2 * CX - leftX;
        edge.moveTo(leftX, BANGS_Y);
        edge.quadTo(CX, BANGS_Y + 46, rightX, BANGS_Y);
        g.draw(edge);

        // 发梢高光
        g.setPaint(new Color(0x88FFECDB, true));
        g.setStroke(stroke(15f));
        g.draw(new QuadCurve2D.Double(392, 380, 430, 300, 500, 262));
        g.draw(new QuadCurve2D.Double(596, 272, 656, 306, 700, 372));
    }

    static void drawFeatures(Graphics2D g) {
        g.setPaint(new Color(0x88FFAEB4, true));
        g.fill(new Ellipse2D.Double(288, 654, 104, 52));
        g.fill(new Ellipse2D.Double(632, 654, 104, 52));

        eye(g, 408, 566);
        eye(g, 616, 566);

        Path2D nose = new Path2D.Double();
        nose.moveTo(512, 626);
        nose.curveTo(500, 642, 500, 648, 512, 650);
        nose.curveTo(524, 648, 524, 642, 512, 626);
        nose.closePath();
        g.setPaint(new Color(0xF0A6A6));
        g.fill(nose);

        g.setPaint(INK);
        g.setStroke(stroke(9f));
        Path2D m = new Path2D.Double();
        m.moveTo(462, 686);
        m.curveTo(476, 712, 500, 712, 512, 690);
        m.curveTo(524, 712, 548, 712, 562, 686);
        g.draw(m);

        // 胡须
        g.setStroke(stroke(7f));
        g.setPaint(new Color(0x664A3A34, true));
        g.draw(new Line2D.Double(252, 640, 326, 632));
        g.draw(new Line2D.Double(248, 684, 324, 676));
        g.draw(new Line2D.Double(772, 640, 698, 632));
        g.draw(new Line2D.Double(776, 684, 700, 676));
    }

    static void eye(Graphics2D g, double cx, double cy) {
        g.setPaint(WHITE);
        g.fill(new Ellipse2D.Double(cx - 62, cy - 74, 124, 148));

        g.setPaint(MINT);
        g.fill(new Ellipse2D.Double(cx - 52, cy - 58, 104, 118));

        g.setPaint(new Color(0x2E2622));
        g.fill(new Ellipse2D.Double(cx - 26, cy - 26, 52, 62));

        g.setPaint(WHITE);
        g.fill(new Ellipse2D.Double(cx - 40, cy - 50, 34, 34));
        g.fill(new Ellipse2D.Double(cx + 8, cy + 16, 18, 18));

        g.setPaint(new Color(0x2E3FB596, true));
        g.fill(new Ellipse2D.Double(cx - 44, cy + 36, 88, 22));

        g.setPaint(INK);
        g.setStroke(stroke(14f));
        Path2D lash = new Path2D.Double();
        lash.moveTo(cx - 66, cy - 22);
        lash.curveTo(cx - 58, cy - 90, cx + 58, cy - 90, cx + 66, cy - 22);
        g.draw(lash);

        g.setStroke(stroke(10f));
        g.draw(new Line2D.Double(cx + 62, cy - 30, cx + 88, cy - 56));
    }

    /** 蝴蝶结发饰：两个圆角三角 + 一个结。 */
    static void drawBow(Graphics2D g) {
        double cx = 344, cy = 338;
        double[][] left = {{cx - 6, cy - 6}, {cx - 92, cy - 56}, {cx - 84, cy + 40}};
        double[][] right = {{cx + 6, cy - 4}, {cx + 86, cy - 50}, {cx + 80, cy + 42}};

        g.setPaint(HOODIE_DARK);
        g.fill(roundedPolygon(left, 38));
        g.fill(roundedPolygon(right, 38));
        g.setPaint(INK);
        g.setStroke(stroke(10f));
        g.draw(roundedPolygon(left, 38));
        g.draw(roundedPolygon(right, 38));

        g.setPaint(HOODIE);
        g.fill(new Ellipse2D.Double(cx - 28, cy - 26, 56, 52));
        g.setPaint(INK);
        g.setStroke(stroke(10f));
        g.draw(new Ellipse2D.Double(cx - 28, cy - 26, 56, 52));


    }

    // ------------------------------------------------------------------ 工具

    static BasicStroke stroke(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /** 圆角多边形：每个顶点用二次曲线倒角，半径按相邻边长一半收敛。 */
    static Path2D roundedPolygon(double[][] pts, double radius) {
        int n = pts.length;
        Path2D p = new Path2D.Double();
        double[] prevEnd = new double[2];
        for (int i = 0; i < n; i++) {
            double[] cur = pts[i];
            double[] prev = pts[(i - 1 + n) % n];
            double[] next = pts[(i + 1) % n];
            double lenPrev = Math.hypot(cur[0] - prev[0], cur[1] - prev[1]);
            double lenNext = Math.hypot(next[0] - cur[0], next[1] - cur[1]);
            double r = Math.min(radius, Math.min(lenPrev, lenNext) / 2.2);
            double ax = cur[0] + (prev[0] - cur[0]) / lenPrev * r;
            double ay = cur[1] + (prev[1] - cur[1]) / lenPrev * r;
            double bx = cur[0] + (next[0] - cur[0]) / lenNext * r;
            double by = cur[1] + (next[1] - cur[1]) / lenNext * r;
            if (i == 0) {
                p.moveTo(ax, ay);
            } else {
                p.lineTo(prevEnd[0], prevEnd[1]);
                p.quadTo(cur[0], cur[1], ax, ay);
            }
            prevEnd[0] = bx;
            prevEnd[1] = by;
        }
        p.lineTo(prevEnd[0], prevEnd[1]);
        double[] first = pts[0];
        double[] last = pts[n - 1];
        double len = Math.hypot(first[0] - last[0], first[1] - last[1]);
        double r = Math.min(radius, len / 2.2);
        double ax = first[0] + (last[0] - first[0]) / len * r;
        double ay = first[1] + (last[1] - first[1]) / len * r;
        p.quadTo(first[0], first[1], ax, ay);
        p.closePath();
        return p;
    }
}
