package swarm;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL20.*;

/** Işıklandırmalı 3D çizici. Tek shader, renk/ışıma/alfa uniform'ları. */
final class Renderer {
    private static final String VS = """
            #version 330 core
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec3 aNormal;
            uniform mat4 uProj;
            uniform mat4 uView;
            uniform mat4 uModel;
            out vec3 vWorld;
            out vec3 vNormal;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vWorld = w.xyz;
                vNormal = mat3(transpose(inverse(uModel))) * aNormal;
                gl_Position = uProj * uView * w;
            }
            """;

    private static final String FS = """
            #version 330 core
            in vec3 vWorld;
            in vec3 vNormal;
            uniform vec3 uColor;
            uniform float uEmissive;
            uniform float uAlpha;
            uniform float uFlash;
            uniform int uMode;          // 0 normal, 1 zemin, 2 ışıksız
            uniform vec3 uCamPos;
            uniform vec3 uFogColor;
            uniform float uArena;
            out vec4 frag;

            void main() {
                vec3 n = normalize(vNormal);
                vec3 base = uColor;
                if (uMode == 1) {
                    vec2 p = vWorld.xz;
                    vec2 g = abs(fract(p / 4.0 - 0.5) - 0.5) * 4.0;
                    float line = 1.0 - smoothstep(0.0, 0.09, min(g.x, g.y));
                    float checker = mod(floor(p.x / 4.0) + floor(p.y / 4.0), 2.0);
                    base = mix(vec3(0.13, 0.17, 0.21), vec3(0.15, 0.20, 0.24), checker);
                    base += 0.035 * sin(p.x * 0.31) * sin(p.y * 0.23) + 0.02 * sin(p.x * 1.7 + p.y * 1.3);
                    base = mix(base, vec3(0.28, 0.45, 0.58), line * 0.30);
                    float edge = max(abs(p.x), abs(p.y));
                    base = mix(base, vec3(0.40, 0.08, 0.28), smoothstep(uArena - 8.0, uArena, edge));
                    float center = 1.0 - smoothstep(0.0, 10.0, length(p));
                    base += vec3(0.05, 0.08, 0.10) * center;
                }
                vec3 L = normalize(vec3(0.45, 1.0, 0.35));
                float diff = max(dot(n, L), 0.0);
                float hemi = 0.5 + 0.5 * n.y;
                vec3 col = base * (0.30 + 0.25 * hemi + 0.70 * diff);
                vec3 V = normalize(uCamPos - vWorld);
                float rim = pow(1.0 - max(dot(n, V), 0.0), 3.0);
                col += rim * 0.30 * base;
                if (uMode == 2) col = base;
                col = mix(col, base * 1.6 + vec3(0.05), uEmissive);
                col = mix(col, vec3(1.0), uFlash);
                float d = length(uCamPos - vWorld);
                float fog = smoothstep(40.0, 80.0, d);
                col = mix(col, uFogColor, fog * (1.0 - uEmissive * 0.5));
                frag = vec4(col, uAlpha);
            }
            """;

    final Shader sh;
    final Mesh cube, sphere, cyl, cone, disc, ring, plane;
    private final Matrix4f scratch = new Matrix4f();
    private final FloatBuffer mbuf = BufferUtils.createFloatBuffer(16);
    private final int uModel, uColor, uEmissive, uAlpha, uFlash, uMode;
    private float curAlpha = -1, curFlash = -1;
    private int curMode = -1;

    float alpha = 1, flash = 0;
    int mode = 0;

    Renderer() {
        sh = new Shader(VS, FS);
        cube = MeshFactory.cube();
        sphere = MeshFactory.sphere(16, 10);
        cyl = MeshFactory.cylinder(14);
        cone = MeshFactory.cone(14);
        disc = MeshFactory.disc(32);
        ring = MeshFactory.ring(48, 0.42f);
        plane = MeshFactory.plane();
        uModel = sh.loc("uModel");
        uColor = sh.loc("uColor");
        uEmissive = sh.loc("uEmissive");
        uAlpha = sh.loc("uAlpha");
        uFlash = sh.loc("uFlash");
        uMode = sh.loc("uMode");
    }

    void begin(Matrix4f proj, Matrix4f view, Vector3f cam, float arena, int fogRgb) {
        sh.use();
        sh.mat4("uProj", proj);
        sh.mat4("uView", view);
        sh.vec3("uCamPos", cam.x, cam.y, cam.z);
        sh.vec3("uFogColor", ((fogRgb >> 16) & 255) / 255f, ((fogRgb >> 8) & 255) / 255f, (fogRgb & 255) / 255f);
        sh.f("uArena", arena);
        curAlpha = curFlash = -1;
        curMode = -1;
        alpha = 1;
        flash = 0;
        mode = 0;
    }

    /** base matrisinin kopyası (anında draw'a verilmeli). */
    Matrix4f m(Matrix4f base) { return scratch.set(base); }

    Matrix4f at(float x, float y, float z) { return scratch.translation(x, y, z); }

    void draw(Mesh mesh, Matrix4f m, int rgb) { draw(mesh, m, rgb, 0f); }

    void draw(Mesh mesh, Matrix4f m, int rgb, float emissive) {
        m.get(mbuf);
        glUniformMatrix4fv(uModel, false, mbuf);
        glUniform3f(uColor, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
        glUniform1f(uEmissive, emissive);
        if (alpha != curAlpha) { glUniform1f(uAlpha, alpha); curAlpha = alpha; }
        if (flash != curFlash) { glUniform1f(uFlash, flash); curFlash = flash; }
        if (mode != curMode) { glUniform1i(uMode, mode); curMode = mode; }
        mesh.draw();
    }
}
