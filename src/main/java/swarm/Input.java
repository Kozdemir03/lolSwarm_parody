package swarm;

import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.*;

final class Input {
    final boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] hits = new boolean[GLFW_KEY_LAST + 1];
    final boolean[] btn = new boolean[8];
    private final boolean[] clicks = new boolean[8];
    double mx, my;
    /** Lobi ekranındaki yazı kutusu için. */
    boolean textMode;
    final StringBuilder typed = new StringBuilder();

    void attach(long w) {
        glfwSetKeyCallback(w, (win, key, sc, action, mods) -> {
            if (key < 0 || key > GLFW_KEY_LAST) return;
            if (action == GLFW_PRESS) { keys[key] = true; hits[key] = true; }
            else if (action == GLFW_REPEAT && key == GLFW_KEY_BACKSPACE) hits[key] = true;
            else if (action == GLFW_RELEASE) keys[key] = false;
        });
        glfwSetCharCallback(w, (win, cp) -> {
            if (textMode && cp >= 32 && cp < 0x10000 && typed.length() < 48 && !mod()) typed.append((char) cp);
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

    /** Cmd (Mac) veya Ctrl (Windows) basılı mı. */
    boolean mod() {
        return keys[GLFW_KEY_LEFT_SUPER] || keys[GLFW_KEY_RIGHT_SUPER] || keys[GLFW_KEY_LEFT_CONTROL] || keys[GLFW_KEY_RIGHT_CONTROL];
    }

    void endFrame() {
        Arrays.fill(hits, false);
        Arrays.fill(clicks, false);
    }
}
