package swarm;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;

import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL20.*;

final class Shader {
    final int id;
    private final HashMap<String, Integer> locs = new HashMap<>();

    Shader(String vs, String fs) {
        int v = compile(GL_VERTEX_SHADER, vs);
        int f = compile(GL_FRAGMENT_SHADER, fs);
        id = glCreateProgram();
        glAttachShader(id, v);
        glAttachShader(id, f);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader link hatası: " + glGetProgramInfoLog(id));
        glDeleteShader(v);
        glDeleteShader(f);
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader derleme hatası: " + glGetShaderInfoLog(s));
        return s;
    }

    void use() { glUseProgram(id); }

    int loc(String name) { return locs.computeIfAbsent(name, n -> glGetUniformLocation(id, n)); }

    void mat4(String name, Matrix4f m) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(loc(name), false, fb);
        }
    }

    void vec3(String name, float x, float y, float z) { glUniform3f(loc(name), x, y, z); }
    void vec4(String name, float x, float y, float z, float w) { glUniform4f(loc(name), x, y, z, w); }
    void f(String name, float v) { glUniform1f(loc(name), v); }
    void i(String name, int v) { glUniform1i(loc(name), v); }
}
