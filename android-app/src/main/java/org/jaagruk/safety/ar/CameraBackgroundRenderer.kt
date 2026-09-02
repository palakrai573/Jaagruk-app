package org.jaagruk.safety.ar

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the ARCore camera image as a full-screen quad.
 *
 * This is the only OpenGL in the app, and it is here because ARCore gives no alternative: the camera
 * image is only ever handed to a GL external texture. Markers are drawn as a Compose overlay instead,
 * which is why this class is a hundred lines rather than a renderer framework.
 *
 * Two details are load-bearing and easy to get wrong:
 *
 *  * **The texture coordinates come from ARCore, not from a constant.** `transformCoordinates2d`
 *    accounts for display rotation, aspect fill and the sensor's own orientation. Hard-coding them
 *    produces an image that is subtly stretched or 90 degrees out on some handsets — and on a drill
 *    where a worker points at a real doorway, a rotated camera feed makes every answer wrong.
 *  * **Depth testing and writing are off.** The background is drawn first and must never occlude
 *    anything; leaving depth on is how the camera feed ends up hiding the scene on some drivers.
 */
class CameraBackgroundRenderer {

    private var programId = 0
    private var textureId = 0
    private var positionAttribute = 0
    private var texCoordAttribute = 0
    private var textureUniform = 0

    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    private var initialised = false
    private var texCoordsConfigured = false

    /** The external texture name ARCore should render the camera image into. */
    val cameraTextureId: Int get() = textureId

    /** Must be called on the GL thread. */
    fun createOnGlThread(): Boolean {
        if (initialised) return true

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR,
        )

        quadCoords = allocateFloats(QUAD_COORDS)
        // Overwritten from the first ARCore frame; these are only a sane starting value so the very
        // first frame is not undefined.
        quadTexCoords = allocateFloats(DEFAULT_TEX_COORDS)

        val vertexShader = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return false
        val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return false

        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertexShader)
        GLES30.glAttachShader(programId, fragmentShader)
        GLES30.glLinkProgram(programId)

        val linked = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "camera background program failed to link: ${GLES30.glGetProgramInfoLog(programId)}")
            GLES30.glDeleteProgram(programId)
            programId = 0
            return false
        }

        // Shaders are only needed until the program is linked.
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        positionAttribute = GLES30.glGetAttribLocation(programId, "a_Position")
        texCoordAttribute = GLES30.glGetAttribLocation(programId, "a_TexCoord")
        textureUniform = GLES30.glGetUniformLocation(programId, "u_Texture")

        initialised = true
        return true
    }

    /**
     * Recomputes texture coordinates when the display geometry changes.
     *
     * Cheap, but not free, and ARCore only reports a change on the frame it happens. Guarding on
     * `hasDisplayGeometryChanged` is what keeps this off the per-frame path.
     */
    fun onFrame(frame: Frame) {
        if (!initialised) return
        if (frame.hasDisplayGeometryChanged() || !texCoordsConfigured) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords,
            )
            texCoordsConfigured = true
        }
    }

    fun draw() {
        if (!initialised || programId == 0) return

        quadCoords.position(0)
        quadTexCoords.position(0)

        // The background must not write depth or be depth-tested: it is the furthest thing in the scene
        // by definition, and letting the driver decide has produced a black screen on more than one
        // Mali part.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(textureUniform, 0)

        GLES30.glVertexAttribPointer(positionAttribute, 2, GLES30.GL_FLOAT, false, 0, quadCoords)
        GLES30.glVertexAttribPointer(texCoordAttribute, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)
        GLES30.glEnableVertexAttribArray(positionAttribute)
        GLES30.glEnableVertexAttribArray(texCoordAttribute)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(positionAttribute)
        GLES30.glDisableVertexAttribArray(texCoordAttribute)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Releases GL objects. Must be called on the GL thread. */
    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        initialised = false
        texCoordsConfigured = false
    }

    private fun compile(type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun allocateFloats(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private companion object {
        const val TAG = "CameraBackground"

        /** Full-screen quad in normalised device coordinates, as a triangle strip. */
        val QUAD_COORDS = floatArrayOf(
            -1f, -1f,
            +1f, -1f,
            -1f, +1f,
            +1f, +1f,
        )

        val DEFAULT_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )

        val VERTEX_SHADER = """
            #version 300 es
            in vec2 a_Position;
            in vec2 a_TexCoord;
            out vec2 v_TexCoord;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        /**
         * `samplerExternalOES` needs the OES extension declared explicitly in ES 3.0 shaders. Omitting
         * it compiles on some drivers and fails on others, which is the worst of both worlds.
         */
        val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(u_Texture, v_TexCoord);
            }
        """.trimIndent()
    }
}
