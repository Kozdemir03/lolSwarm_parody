package swarm;

import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.*;

final class Input {
    final boolean[] keys = new boolean[GLFW_KEY_LAST + 1], hits = new boolean[GLFW_KEY_LAST + 1];
    final boolean[] btn = new boolean[8], clicks = new boolean[8];
    double mx, my;

    void attach(long w) {
        glfwSetKeyCallback(w, (win, key, sc, action, mods) -> {
            if (key < 0 || key > GLFW_KEY_LAST) return;
            if (action == GLFW_PRESS) { keys[key] = true; hits[key] = true; }
            else if (action == GLFW_RELEASE) keys[key] = false;
        });
        glfwSetMouseButtonCallback(w, (win, b, action, mods) -> {
            if (b < 0 || b >= 8) return;
            if (action == GLFW_PRESS) { btn[b] = true; clicks[b] = true; }
            else if (action == GLFW_RELEASE) btn[b] = false;
        });
        glfwSetCursorPosCallback(w, (win, x, y) -> { mx = x; my = y; });
    }

    boolean down(int k) { return keys[k]; }
    boolean hit(int k) { return hits[k]; }
    boolean mouseDown(int b) { return btn[b]; }
    boolean click(int b) { return clicks[b]; }

    void endFrame() {
        Arrays.fill(hits, false);
        Arrays.fill(clicks, false);
    }
}
