import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 轻传图标生成器（无第三方依赖，纯 JDK AWT/ImageIO）。
 *
 * 用法：
 *   java IconMaker --src art/icon_src.png --res app/src/main/res --preview tools/icon_preview.png
 *   java IconMaker --res app/src/main/res --preview tools/icon_preview.png      # 无源图时用矢量回退
 *
 * 产出：
 *   mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        （圆角方形，旧系统）
 *   mipmap-<density>/ic_launcher_round.png                                 （圆形）
 *   mipmap-<density>/ic_launcher_bg.png                                    （自适应图标背景，满幅）
 *   mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml       （自适应图标描述）
 *
 * 说明：自适应图标用「满幅背景 + 透明前景」，系统按圆形/方形遮罩裁切，
 * 满幅绘制的内容天然贴合遮罩，比把整张图塞进 72dp 安全区更自然。
 */
public class IconMaker {

    private static final int[] DENSITY_SIZE = {48, 72, 96, 144, 192};
    private static final String[] DENSITY_DIR = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] ADAPTIVE_SIZE = {108, 162, 216, 324, 432};

    public static void main(String[] args) throws Exception {
        String src = null, res = null, preview = null, fgSrc = null, bgHex = "FFF7EF";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--src")) src = args[++i];
            else if (args[i].equals("--res")) res = args[++i];
            else if (args[i].equals("--preview")) preview = args[++i];
            else if (args[i].equals("--fg")) fgSrc = args[++i];
            else if (args[i].equals("--bg")) bgHex = args[++i];
        }
        if (res == null) {
            System.err.println("需要 --res <res 目录>");
            System.exit(2);
        }
        BufferedImage art;
        File srcFile = src == null ? null : new File(src);
        if (srcFile != null && srcFile.isFile()) {
            art = coverSquare(ImageIO.read(srcFile), 1024);
            System.out.println("使用源图: " + srcFile.getAbsolutePath() + " -> 1024x1024");
        } else {
            art = drawFallback(1024);
            System.out.println("未找到源图，使用矢量回退绘制");
        }

        BufferedImage fgArt = art;
        if (fgSrc != null) {
            File f = new File(fgSrc);
            if (f.isFile()) {
                fgArt = coverSquare(ImageIO.read(f), 1024);
                System.out.println("自适应前景: " + f.getAbsolutePath());
            }
        }
        int bgColor = (int) Long.parseLong(bgHex, 16) | 0xFF000000;

        File resDir = new File(res);
        for (int i = 0; i < DENSITY_SIZE.length; i++) {
            File dir = new File(resDir, "mipmap-" + DENSITY_DIR[i]);
            dir.mkdirs();
            int size = DENSITY_SIZE[i];
            BufferedImage scaled = scale(art, size);
            write(maskRoundRect(scaled, (int) Math.round(size * 0.22)), new File(dir, "ic_launcher.png"));
            write(maskCircle(scaled), new File(dir, "ic_launcher_round.png"));

            // 自适应图标：背景纯色铺满 108dp，前景缩到 75%（72dp 安全区）——
            // 直接把满幅图塞背景会被系统按 1.5 倍裁切，猫耳会被切掉。
            BufferedImage bg = new BufferedImage(ADAPTIVE_SIZE[i], ADAPTIVE_SIZE[i], BufferedImage.TYPE_INT_ARGB);
            Graphics2D gb = quality(bg);
            gb.setPaint(new Color(bgColor, true));
            gb.fillRect(0, 0, ADAPTIVE_SIZE[i], ADAPTIVE_SIZE[i]);
            gb.dispose();
            write(bg, new File(dir, "ic_launcher_bg.png"));

            int fgSize = (int) Math.round(ADAPTIVE_SIZE[i] * 0.75);
            BufferedImage fg = new BufferedImage(ADAPTIVE_SIZE[i], ADAPTIVE_SIZE[i], BufferedImage.TYPE_INT_ARGB);
            Graphics2D gf = quality(fg);
            int off = (ADAPTIVE_SIZE[i] - fgSize) / 2;
            gf.drawImage(fgArt, off, off, fgSize, fgSize, null);
            gf.dispose();
            write(fg, new File(dir, "ic_launcher_fg.png"));
        }

        File anydpi = new File(resDir, "mipmap-anydpi-v26");
        anydpi.mkdirs();
        writeText(new File(anydpi, "ic_launcher.xml"), adaptiveXml(false));
        writeText(new File(anydpi, "ic_launcher_round.xml"), adaptiveXml(true));

        // 旧的 vector 占位（mipmap-anydpi/）会让 24/25 走矢量；有 PNG 后删掉更稳
        for (String name : new String[]{"ic_launcher.xml", "ic_launcher_round.xml"}) {
            File legacy = new File(resDir, "mipmap-anydpi/" + name);
            if (legacy.isFile()) {
                System.out.println("删除旧占位: " + legacy.getAbsolutePath());
                legacy.delete();
            }
        }

        if (preview != null) {
            writePreview(art, new File(preview));
            System.out.println("预览: " + new File(preview).getAbsolutePath());
        }
        System.out.println("图标生成完成: " + resDir.getAbsolutePath());
    }

    // ------------------------------------------------------------ 图像处理

    private static BufferedImage coverSquare(BufferedImage src, int size) {
        int w = src.getWidth(), h = src.getHeight();
        double side = Math.min(w, h);
        int x = (int) ((w - side) / 2), y = (int) ((h - side) / 2);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out);
        g.drawImage(src, 0, 0, size, size, x, y, x + (int) side, y + (int) side, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static BufferedImage maskRoundRect(BufferedImage src, int radius) {
        int s = src.getWidth();
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out);
        g.setClip(new RoundRectangle2D.Float(0, 0, s, s, radius * 2, radius * 2));
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage maskCircle(BufferedImage src) {
        int s = src.getWidth();
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(out);
        g.setClip(new Ellipse2D.Float(0, 0, s, s));
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Graphics2D quality(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static void write(BufferedImage img, File file) throws Exception {
        ImageIO.write(img, "png", file);
    }

    private static void writeText(File file, String text) throws Exception {
        java.nio.file.Files.write(file.toPath(), text.getBytes("UTF-8"));
    }

    private static String adaptiveXml(boolean round) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <background android:drawable=\"@mipmap/ic_launcher_bg\" />\n"
            + "    <foreground android:drawable=\"@mipmap/ic_launcher_fg\" />\n"
            + "</adaptive-icon>\n";
    }

    // ------------------------------------------------------------ 回退矢量绘制

    private static BufferedImage drawFallback(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img);
        float u = s / 1024f;

        // 底色：近黑 + 底部薄荷光晕
        g.setPaint(new Color(0xFF0A0D10, true));
        g.fillRect(0, 0, s, s);
        RadialGradientPaint glow = new RadialGradientPaint(
            new Point2D.Float(700 * u, 820 * u), 620 * u,
            new float[]{0f, 1f},
            new Color[]{new Color(0x552EE6A8, true), new Color(0x002EE6A8, true)});
        g.setPaint(glow);
        g.fillRect(0, 0, s, s);

        // 两条相反的箭头（上右、下左）
        g.setStroke(new BasicStroke(74 * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(new LinearGradientPaint(
            new Point2D.Float(240 * u, 300 * u), new Point2D.Float(800 * u, 760 * u),
            new float[]{0f, 1f},
            new Color[]{new Color(0xFF2EE6A8, true), new Color(0xFF4CC9F0, true)}));
        g.draw(new Line2D.Float(300 * u, 400 * u, 690 * u, 400 * u));
        g.draw(new Line2D.Float(724 * u, 624 * u, 334 * u, 624 * u));

        // 箭头头部
        g.setPaint(new Color(0xFF2EE6A8, true));
        Path2D up = new Path2D.Float();
        up.moveTo(628 * u, 286 * u);
        up.lineTo(812 * u, 400 * u);
        up.lineTo(628 * u, 514 * u);
        up.closePath();
        g.fill(up);

        g.setPaint(new Color(0xFF4CC9F0, true));
        Path2D down = new Path2D.Float();
        down.moveTo(396 * u, 510 * u);
        down.lineTo(212 * u, 624 * u);
        down.lineTo(396 * u, 738 * u);
        down.closePath();
        g.fill(down);

        // 右上角小星芒
        g.setPaint(new Color(0xBFD9FFF0, true));
        Path2D star = new Path2D.Float();
        double cx = 806 * u, cy = 250 * u, r = 58 * u, r2 = 16 * u;
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI / 4 * i;
            double rr = (i % 2 == 0) ? r : r2;
            double px = cx + Math.cos(ang) * rr;
            double py = cy + Math.sin(ang) * rr;
            if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.closePath();
        g.fill(star);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------ 预览图

    private static void writePreview(BufferedImage art, File out) throws Exception {
        int cell = 256, gap = 28, pad = 28;
        BufferedImage img = new BufferedImage(pad * 2 + cell * 3 + gap * 2, pad * 2 + cell + 44,
            BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img);
        g.setPaint(new Color(0xFF20262B));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        BufferedImage base = scale(art, cell);
        g.drawImage(maskRoundRect(base, (int) (cell * 0.22)), pad, pad, null);
        g.drawImage(maskCircle(base), pad + cell + gap, pad, null);
        g.drawImage(base, pad + (cell + gap) * 2, pad, null);
        // Termux 的 JDK 缺 fontmanager 本地库，预览图不画文字，只用色块区分
        g.setPaint(new Color(0xFF3A4450));
        for (int i = 0; i < 3; i++) {
            g.fillRoundRect(pad + (cell + gap) * i + 84, pad + cell + 14, 88, 8, 8, 8);
        }
        g.dispose();
        write(img, out);
    }
}
