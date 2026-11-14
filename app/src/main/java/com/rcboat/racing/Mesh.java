package com.rcboat.racing;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * A vertex buffer: position(3) normal(3) color(3), optionally indexed. Static unless made
 * with dynamic = true, which allows {@link #update} every frame.
 */
final class Mesh {
    static final int FLOATS = 9;
    static final int STRIDE = FLOATS * 4;

    private final int vbo;
    private final int ibo;
    private final int count;
    private final boolean indexed;

    private FloatBuffer stage;

    Mesh(float[] data, int vertexCount, short[] indices) {
        this(data, vertexCount, indices, false);
    }

    Mesh(float[] data, int vertexCount, short[] indices, boolean dynamic) {
        FloatBuffer fb = ByteBuffer.allocateDirect(vertexCount * STRIDE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data, 0, vertexCount * FLOATS).position(0);
        int[] ids = new int[2];
        GLES20.glGenBuffers(2, ids, 0);
        vbo = ids[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexCount * STRIDE, fb,
                dynamic ? GLES20.GL_DYNAMIC_DRAW : GLES20.GL_STATIC_DRAW);
        if (dynamic) stage = fb;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        if (indices != null) {
            ShortBuffer sb = ByteBuffer.allocateDirect(indices.length * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(indices).position(0);
            ibo = ids[1];
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.length * 2, sb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            count = indices.length;
            indexed = true;
        } else {
            ibo = 0;
            count = vertexCount;
            indexed = false;
        }
    }

    /** Replaces the vertices of a dynamic mesh (same count as when it was made). */
    void update(float[] data, int vertexCount) {
        stage.position(0);
        stage.put(data, 0, vertexCount * FLOATS).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, vertexCount * STRIDE, stage);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    void draw(int aPos, int aNormal, int aColor) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0);
        if (aNormal >= 0) {
            GLES20.glEnableVertexAttribArray(aNormal);
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12);
        }
        if (aColor >= 0) {
            GLES20.glEnableVertexAttribArray(aColor);
            GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, STRIDE, 24);
        }
        if (indexed) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        } else {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }
}
