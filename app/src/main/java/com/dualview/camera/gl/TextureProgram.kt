package com.dualview.camera.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the camera's external texture onto whatever surface is current.
 *
 * The interesting part is [draw]: the texture coordinates describe which slice of the
 * sensor frame to sample, which is how one camera frame becomes both a 9:16 and a 16:9
 * video at the same time. The look (mono / warm / vivid) is applied in the shader so it
 * costs nothing on the CPU.
 */
class TextureProgram {

    private val program: Int
    private val aPositionLoc: Int
    private val aTexCoordLoc: Int
    private val uTexMatrixLoc: Int
    private val uLookLoc: Int

    private val vertexBuffer: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
    )
    private val texBuffer: FloatBuffer = floatBuffer(FloatArray(8))

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link GL program: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uLookLoc = GLES20.glGetUniformLocation(program, "uLook")
    }

    fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return texId
    }

    /**
     * @param texCoords 8 floats: bottom-left, bottom-right, top-left, top-right (u,v each),
     *                  expressed in the upright image's 0..1 space.
     * @param texMatrix the SurfaceTexture transform, already combined with rotation/mirroring.
     */
    fun draw(texId: Int, texMatrix: FloatArray, texCoords: FloatArray, look: Int) {
        texBuffer.clear()
        texBuffer.put(texCoords)
        texBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uLookLoc, look)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader: $log")
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(values)
        fb.position(0)
        return fb
    }

    companion object {
        const val LOOK_NATURAL = 0
        const val LOOK_MONO = 1
        const val LOOK_WARM = 2
        const val LOOK_VIVID = 3

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform int uLook;
            void main() {
                vec4 color = texture2D(sTexture, vTexCoord);
                if (uLook == 1) {
                    float g = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                    color = vec4(g, g, g, 1.0);
                } else if (uLook == 2) {
                    color.r = min(color.r * 1.10 + 0.02, 1.0);
                    color.g = min(color.g * 1.02, 1.0);
                    color.b = max(color.b * 0.90, 0.0);
                } else if (uLook == 3) {
                    vec3 grey = vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114)));
                    color.rgb = clamp(mix(grey, color.rgb, 1.45) * 1.04, 0.0, 1.0);
                }
                gl_FragColor = color;
            }
        """
    }
}
