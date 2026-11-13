package io.postfx;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the PostFX icon (orange tile, white braces, dark lightning bolt) and writes every asset:
 * PNGs for the JavaFX windows, a multi-size .ico for Windows, an .icns for macOS and a PNG for Linux.
 * <p>
 * Run after changing the design:
 * {@code mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=io.postfx.IconGenerator -Dexec.args=.}
 */
public final class IconGenerator {
    static final int[] PNG_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};
    static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private IconGenerator() {}

    /** The icon, drawn on a 256 x 256 design grid. */
    static void paint(Graphics2D g) {
        RoundRectangle2D tile = new RoundRectangle2D.Double(8, 8, 240, 240, 60, 60);
        g.setPaint(new GradientPaint(0, 8, new Color(0xFF8A4C), 0, 248, new Color(0xE8452C)));
        g.fill(tile);
        g.setPaint(new GradientPaint(0, 8, new Color(255, 255, 255, 60), 0, 120, new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(8, 8, 240, 120, 60, 60));

        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(17, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D left = new Path2D.Double();
        left.moveTo(88, 56);
        left.curveTo(70, 56, 66, 64, 66, 80);
        left.lineTo(66, 108);
        left.curveTo(66, 120, 60, 128, 48, 128);
        left.curveTo(60, 128, 66, 136, 66, 148);
        left.lineTo(66, 176);
        left.curveTo(66, 192, 70, 200, 88, 200);
        g.draw(left);
        AffineTransform mirror = AffineTransform.getTranslateInstance(256, 0);
        mirror.scale(-1, 1);
        g.draw(mirror.createTransformedShape(left));

        Path2D bolt = new Path2D.Double();
        bolt.moveTo(142, 58);
        bolt.lineTo(102, 136);
        bolt.lineTo(128, 136);
        bolt.lineTo(114, 198);
        bolt.lineTo(156, 116);
        bolt.lineTo(130, 116);
        bolt.lineTo(146, 58);
        bolt.closePath();
        g.setColor(new Color(0x1F2937));
        g.fill(bolt);
    }

    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.scale(size / 256.0, size / 256.0);
        paint(g);
        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Windows .ico: 32-bit BMP entries for small sizes, PNG for 256 (Vista+ format). */
    static byte[] ico(int[] sizes) throws IOException {
        List<byte[]> entries = new ArrayList<>();
        for (int s : sizes) entries.add(s >= 256 ? png(render(s)) : dib(render(s)));
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            byte[] data = entries.get(i);
            header.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            header.putShort((short) 1).putShort((short) 32).putInt(data.length).putInt(offset);
            offset += data.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header.array());
        for (byte[] e : entries) out.write(e);
        return out.toByteArray();
    }

    /** BITMAPINFOHEADER + bottom-up BGRA pixels + AND mask, as stored inside .ico files. */
    static byte[] dib(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int maskRow = ((w + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + w * h * 4 + maskRow * h).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(w).putInt(h * 2).putShort((short) 1).putShort((short) 32).putInt(0)
                .putInt(w * h * 4 + maskRow * h).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        b.put(new byte[maskRow * h]); // alpha channel is used; the AND mask stays empty
        return b.array();
    }

    /** macOS .icns with PNG payloads (ic07 = 128, ic08 = 256, ic09 = 512, ic10 = 1024). */
    static byte[] icns() throws IOException {
        String[] types = {"ic07", "ic08", "ic09", "ic10"};
        int[] sizes = {128, 256, 512, 1024};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        for (int i = 0; i < types.length; i++) {
            byte[] data = png(render(sizes[i]));
            d.writeBytes(types[i]);
            d.writeInt(data.length + 8);
            d.write(data);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(out);
        o.writeBytes("icns");
        o.writeInt(body.size() + 8);
        o.write(body.toByteArray());
        return out.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        Path project = Path.of(args.length > 0 ? args[0] : ".");
        Path icons = project.resolve("src/main/resources/io/postfx/ui/icons");
        Path jpackage = project.resolve("src/main/jpackage");
        Files.createDirectories(icons);
        Files.createDirectories(jpackage);
        for (int s : PNG_SIZES) Files.write(icons.resolve("postfx-" + s + ".png"), png(render(s)));
        Files.write(jpackage.resolve("postfx.ico"), ico(ICO_SIZES));
        Files.write(jpackage.resolve("postfx.png"), png(render(512)));
        Files.write(jpackage.resolve("postfx.icns"), icns());
        System.out.println("Icons written to " + icons.toAbsolutePath().normalize() + " and " + jpackage.toAbsolutePath().normalize());
    }
}
