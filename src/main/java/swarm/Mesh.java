package swarm;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Konum(3) + normal(3) içeren basit üçgen mesh. */
final class Mesh {
    final int vao, vbo, count;

    Mesh(float[] data) {
        count = data.length / 6;
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    void draw() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, count);
    }
}
