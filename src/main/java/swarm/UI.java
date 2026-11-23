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

/** 2D arayüz: dikdörtgenler ve yazılar. Koordinatlar pencere pikseli, sol üst (0,0). */
final class UI {
    private static final String VS = """
            #version 330 core
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            uniform mat4 uProj;
            out vec2 vUV;
            void main() { vUV = aUV; gl_Position = uProj * vec4(aPos, 0.0, 1.0); }
            """;
    private static final String FS = """
            #version 330 core
            in vec2 vUV;
            uniform vec4 uColor;
            uniform int uTex;
            uniform sampler2D uSampler;
            out vec4 frag;
            void main() {
                vec4 c = uColor;
                if (uTex == 1) c.a *= texture(uSampler, vUV).a;
                frag = c;
            }
            """;

    private final Shader sh;
    private final int vao, vbo, uColor, uTex;
    private final FloatBuffer fb = BufferUtils.createFloatBuffer(4 * 6 * 512);
    private final Matrix4f ortho = new Matrix4f();
    final Font font;
    float W, H;

    UI() {
        sh = new Shader(VS, FS);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) fb.capacity() * 4L, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
        uColor = sh.loc("uColor");
        uTex = sh.loc("uTex");
        font = new Font(48);
    }

    void begin(float w, float h) {
        W = w;
        H = h;
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        sh.use();
        ortho.setOrtho(0, w, h, 0, -1, 1);
        sh.mat4("uProj", ortho);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, font.tex);
        sh.i("uSampler", 0);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
    }

    private void color(int argb) {
        glUniform4f(uColor, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
    }

    private void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
        fb.put(x).put(y).put(u0).put(v0);
        fb.put(x + w).put(y).put(u1).put(v0);
        fb.put(x + w).put(y + h).put(u1).put(v1);
        fb.put(x).put(y).put(u0).put(v0);
        fb.put(x + w).put(y + h).put(u1).put(v1);
        fb.put(x).put(y + h).put(u0).put(v1);
    }

    private void flush() {
        fb.flip();
        int n = fb.limit() / 4;
        if (n > 0) {
            glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
            glDrawArrays(GL_TRIANGLES, 0, n);
        }
        fb.clear();
    }

    void rect(float x, float y, float w, float h, int argb) {
        if (w <= 0 || h <= 0) return;
        color(argb);
        glUniform1i(uTex, 0);
        fb.clear();
        quad(x, y, w, h, 0, 0, 0, 0);
        flush();
    }

    /** Çerçeve (kenarlık). */
    void frame(float x, float y, float w, float h, float t, int argb) {
        rect(x, y, w, t, argb);
        rect(x, y + h - t, w, t, argb);
        rect(x, y, t, h, argb);
        rect(x + w - t, y, t, h, argb);
    }

    void text(String s, float x, float y, float size, int argb) {
        float sc = size / font.px;
        color(argb);
        glUniform1i(uTex, 1);
        fb.clear();
        for (int i = 0; i < s.length(); i++) {
            float[] g = font.glyphs.get(s.charAt(i));
            if (g == null) g = font.glyphs.get('?');
            quad(x - 2 * sc, y - 2 * sc, font.cellW * sc, font.cellH * sc, g[0], g[1], g[2], g[3]);
            x += g[4] * sc;
            if (fb.remaining() < 24) flush();
        }
        flush();
    }

    float width(String s, float size) {
        float sc = size / font.px, w = 0;
        for (int i = 0; i < s.length(); i++) {
            float[] g = font.glyphs.get(s.charAt(i));
            if (g == null) g = font.glyphs.get('?');
            w += g[4] * sc;
        }
        return w;
    }

    /** Gölgeli yazı. */
    void textS(String s, float x, float y, float size, int argb) {
        int a = (argb >>> 24) & 255;
        text(s, x + size * 0.06f, y + size * 0.06f, size, ((a * 3 / 4) << 24));
        text(s, x, y, size, argb);
    }

    void textC(String s, float cx, float y, float size, int argb) { textS(s, cx - width(s, size) / 2, y, size, argb); }

    List<String> wrap(String s, float maxW, float size) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (width(test, size) > maxW && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
