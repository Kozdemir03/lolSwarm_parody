package swarm;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** AWT ile bir kez çizilip dokuya yüklenen bitmap font (Türkçe karakterler dahil). */
final class Font {
    static final String CHARS;

    static {
        StringBuilder sb = new StringBuilder();
        for (char c = 32; c < 127; c++) sb.append(c);
        sb.append("ÇĞİÖŞÜçğıöşü•·×→←★♥");
        CHARS = sb.toString();
    }

    final int tex, cellW, cellH, atlasW, atlasH, px;
    final HashMap<Character, float[]> glyphs = new HashMap<>(); // u0 v0 u1 v1 advance

    Font(int px) {
        this.px = px;
        java.awt.Font f = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, px);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g0 = tmp.createGraphics();
        g0.setFont(f);
        FontMetrics fm = g0.getFontMetrics();
        int maxW = 1;
        for (char c : CHARS.toCharArray()) maxW = Math.max(maxW, fm.charWidth(c));
        cellW = maxW + 4;
        cellH = fm.getHeight() + 4;
        int ascent = fm.getAscent();
        g0.dispose();

        int cols = 16, rows = (CHARS.length() + cols - 1) / cols;
        atlasW = cols * cellW;
        atlasH = rows * cellH;
        BufferedImage img = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(f);
        g.setColor(Color.WHITE);
        for (int i = 0; i < CHARS.length(); i++) {
            char c = CHARS.charAt(i);
            int cx = (i % cols) * cellW, cy = (i / cols) * cellH;
            g.drawString(String.valueOf(c), cx + 2, cy + 2 + ascent);
            glyphs.put(c, new float[]{cx / (float) atlasW, cy / (float) atlasH,
                    (cx + cellW) / (float) atlasW, (cy + cellH) / (float) atlasH, fm.charWidth(c)});
        }
        g.dispose();

        int[] pixels = img.getRGB(0, 0, atlasW, atlasH, null, 0, atlasW);
        ByteBuffer buf = BufferUtils.createByteBuffer(atlasW * atlasH * 4);
        for (int p : pixels) buf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) ((p >>> 24) & 0xff));
        buf.flip();

        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, atlasW, atlasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
}
