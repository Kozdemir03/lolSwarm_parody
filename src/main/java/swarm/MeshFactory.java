package swarm;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Prosedürel ilkel şekiller: hepsi birim boyutta (yarıçap 0.5) ve merkezde. */
final class MeshFactory {
    private static final class B {
        float[] d = new float[2048];
        int n;

        void v(float x, float y, float z, float nx, float ny, float nz) {
            if (n + 6 > d.length) d = Arrays.copyOf(d, d.length * 2);
            d[n++] = x; d[n++] = y; d[n++] = z;
            d[n++] = nx; d[n++] = ny; d[n++] = nz;
        }

        Mesh build() { return new Mesh(Arrays.copyOf(d, n)); }
    }

    private static void quad(B o, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz) {
        o.v(ax, ay, az, nx, ny, nz); o.v(bx, by, bz, nx, ny, nz); o.v(cx, cy, cz, nx, ny, nz);
        o.v(ax, ay, az, nx, ny, nz); o.v(cx, cy, cz, nx, ny, nz); o.v(dx, dy, dz, nx, ny, nz);
    }

    static Mesh cube() {
        B o = new B();
        float h = 0.5f;
        quad(o, h, -h, -h, h, h, -h, h, h, h, h, -h, h, 1, 0, 0);
        quad(o, -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, -1, 0, 0);
        quad(o, -h, h, -h, -h, h, h, h, h, h, h, h, -h, 0, 1, 0);
        quad(o, -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h, 0, -1, 0);
        quad(o, -h, -h, h, h, -h, h, h, h, h, -h, h, h, 0, 0, 1);
        quad(o, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, -h, 0, 0, -1);
        return o.build();
    }

    static Mesh sphere(int seg, int rings) {
        B o = new B();
        for (int i = 0; i < rings; i++) {
            double t0 = Math.PI * i / rings, t1 = Math.PI * (i + 1) / rings;
            for (int j = 0; j < seg; j++) {
                double p0 = 2 * Math.PI * j / seg, p1 = 2 * Math.PI * (j + 1) / seg;
                sv(o, t0, p0); sv(o, t1, p0); sv(o, t1, p1);
                sv(o, t0, p0); sv(o, t1, p1); sv(o, t0, p1);
            }
        }
        return o.build();
    }

    private static void sv(B o, double t, double p) {
        float x = (float) (Math.sin(t) * Math.cos(p)), y = (float) Math.cos(t), z = (float) (Math.sin(t) * Math.sin(p));
        o.v(x * 0.5f, y * 0.5f, z * 0.5f, x, y, z);
    }

    static Mesh cylinder(int seg) {
        B o = new B();
        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg, a1 = 2 * Math.PI * (j + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            o.v(c0 * .5f, -.5f, s0 * .5f, c0, 0, s0); o.v(c1 * .5f, -.5f, s1 * .5f, c1, 0, s1); o.v(c1 * .5f, .5f, s1 * .5f, c1, 0, s1);
            o.v(c0 * .5f, -.5f, s0 * .5f, c0, 0, s0); o.v(c1 * .5f, .5f, s1 * .5f, c1, 0, s1); o.v(c0 * .5f, .5f, s0 * .5f, c0, 0, s0);
            o.v(0, .5f, 0, 0, 1, 0); o.v(c0 * .5f, .5f, s0 * .5f, 0, 1, 0); o.v(c1 * .5f, .5f, s1 * .5f, 0, 1, 0);
            o.v(0, -.5f, 0, 0, -1, 0); o.v(c1 * .5f, -.5f, s1 * .5f, 0, -1, 0); o.v(c0 * .5f, -.5f, s0 * .5f, 0, -1, 0);
        }
        return o.build();
    }

    static Mesh cone(int seg) {
        B o = new B();
        float k = (float) (1 / Math.sqrt(1.25));
        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg, a1 = 2 * Math.PI * (j + 1) / seg, am = (a0 + a1) / 2;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float cm = (float) Math.cos(am), sm = (float) Math.sin(am);
            o.v(c0 * .5f, -.5f, s0 * .5f, c0 * k, .5f * k, s0 * k);
            o.v(c1 * .5f, -.5f, s1 * .5f, c1 * k, .5f * k, s1 * k);
            o.v(0, .5f, 0, cm * k, .5f * k, sm * k);
            o.v(0, -.5f, 0, 0, -1, 0); o.v(c1 * .5f, -.5f, s1 * .5f, 0, -1, 0); o.v(c0 * .5f, -.5f, s0 * .5f, 0, -1, 0);
        }
        return o.build();
    }

    static Mesh disc(int seg) {
        B o = new B();
        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg, a1 = 2 * Math.PI * (j + 1) / seg;
            o.v(0, 0, 0, 0, 1, 0);
            o.v((float) Math.cos(a0) * .5f, 0, (float) Math.sin(a0) * .5f, 0, 1, 0);
            o.v((float) Math.cos(a1) * .5f, 0, (float) Math.sin(a1) * .5f, 0, 1, 0);
        }
        return o.build();
    }

    static Mesh ring(int seg, float inner) {
        B o = new B();
        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg, a1 = 2 * Math.PI * (j + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            quad(o, c0 * inner, 0, s0 * inner, c0 * .5f, 0, s0 * .5f, c1 * .5f, 0, s1 * .5f, c1 * inner, 0, s1 * inner, 0, 1, 0);
        }
        return o.build();
    }

    static Mesh plane() {
        B o = new B();
        quad(o, -.5f, 0, -.5f, -.5f, 0, .5f, .5f, 0, .5f, .5f, 0, -.5f, 0, 1, 0);
        return o.build();
    }
}
