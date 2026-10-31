import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 把 seedream 出的一张方图整理成两件素材：
 *   art/icon.png    1024 奶油底 + 缩放 88% 居中，给系统图标留出遮罩安全区
 *   art/mascot.png  1024 透明底圆形头像（应用内吉祥物）
 */
public class PrepareArt {
    static final Color CREAM = new Color(0xFF, 0xF7, 0xEF);

    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File outDir = new File(a[1]);
        outDir.mkdirs();
        BufferedImage art = ImageIO.read(src);

        // 图标：奶油底 + 88% 居中
        int S = 1024;
        BufferedImage icon = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gi = high(icon);
        gi.setPaint(CREAM);
        gi.fillRect(0, 0, S, S);
        int inner = (int) (S * 0.88);
        int off = (S - inner) / 2;
        gi.drawImage(art, off, off, inner, inner, null);
        gi.dispose();
        ImageIO.write(icon, "png", new File(outDir, "icon.png"));

        // 吉祥物：圆形头像，圆形外透明
        BufferedImage mascot = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gm = high(mascot);
        gm.setClip(new Ellipse2D.Float(0, 0, S, S));
        gm.setPaint(CREAM);
        gm.fillRect(0, 0, S, S);
        // 头部略上移，圆形裁切更居中
        gm.drawImage(art, 0, (int) (-S * 0.05), S, S, null);
        gm.dispose();
        // 圆形外清 alpha
        BufferedImage cut = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gc = high(cut);
        gc.setClip(new Ellipse2D.Float(0, 0, S, S));
        gc.drawImage(mascot, 0, 0, null);
        gc.dispose();
        ImageIO.write(cut, "png", new File(outDir, "mascot.png"));

        System.out.println("icon:   " + new File(outDir, "icon.png").getAbsolutePath());
        System.out.println("mascot: " + new File(outDir, "mascot.png").getAbsolutePath());
    }

    static Graphics2D high(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }
}
