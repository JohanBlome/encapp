package com.facebook.encapp.utils;

import android.opengl.GLES20;
import android.util.Log;

import com.facebook.encapp.utils.grafika.EglCore;
import com.facebook.encapp.utils.grafika.WindowSurface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * FakeGLRenderer generates synthetic video frames using OpenGL ES shaders.
 * This renders directly to GL surfaces with zero CPU overhead - patterns are
 * generated entirely on the GPU using fragment shaders.
 *
 */
public class FakeGLRenderer {
    private static final String TAG = "encapp.fake_gl_renderer";

    // Vertex shader - simple passthrough for full-screen quad
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    // Fragment shader - generates animated gradient pattern
    private static final String FRAGMENT_SHADER_GRADIENT =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform float uTime;\n" +
            "void main() {\n" +
            "    // Animated gradient that moves and pulses\n" +
            "    float wave = sin(uTime * 2.0);\n" +
            "    float r = 0.5 + 0.3 * vTexCoord.x + 0.2 * sin(uTime + vTexCoord.y * 3.14);\n" +
            "    float g = 0.5 + 0.3 * vTexCoord.y + 0.2 * cos(uTime + vTexCoord.x * 3.14);\n" +
            "    float b = 0.5 + 0.2 * (vTexCoord.x + vTexCoord.y) * 0.5 + 0.15 * wave;\n" +
            "    gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}\n";

    // Fragment shader - solid gray (for testing - most compressible)
    private static final String FRAGMENT_SHADER_SOLID =
            "precision mediump float;\n" +
            "uniform float uTime;\n" +
            "void main() {\n" +
            "    // Pulsating gray\n" +
            "    float gray = 0.5 + 0.3 * sin(uTime * 2.0);\n" +
            "    gl_FragColor = vec4(gray, gray, gray, 1.0);\n" +
            "}\n";

    // Fragment shader - animated checkerboard pattern
    private static final String FRAGMENT_SHADER_TEXTURE =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform float uTime;\n" +
            "void main() {\n" +
            "    // Animated checkerboard pattern that moves and changes color\n" +
            "    float scale = 40.0;\n" +
            "    \n" +
            "    // Scroll the pattern over time\n" +
            "    vec2 coord = vTexCoord * scale + vec2(uTime * 2.0, uTime * 1.5);\n" +
            "    \n" +
            "    // Create checkerboard\n" +
            "    float check = mod(floor(coord.x) + floor(coord.y), 2.0);\n" +
            "    \n" +
            "    // Animated colors that pulse\n" +
            "    float pulse = sin(uTime * 2.0) * 0.2;\n" +
            "    float baseR = 0.4 + check * 0.2 + pulse;\n" +
            "    float baseG = 0.5 + check * 0.15 + pulse * 0.8;\n" +
            "    float baseB = 0.45 + check * 0.1 + pulse * 0.6;\n" +
            "    \n" +
            "    gl_FragColor = vec4(baseR, baseG, baseB, 1.0);\n" +
            "}\n";

    // Full-screen quad vertices
    private static final float[] VERTICES = {
        // Position (x, y)    TexCoord (s, t)
        -1.0f, -1.0f,         0.0f, 0.0f,  // bottom-left
         1.0f, -1.0f,         1.0f, 0.0f,  // bottom-right
        -1.0f,  1.0f,         0.0f, 1.0f,  // top-left
         1.0f,  1.0f,         1.0f, 1.0f   // top-right
    };

    private static final int COORDS_PER_VERTEX = 2;
    private static final int TEXCOORDS_PER_VERTEX = 2;
    private static final int VERTEX_STRIDE = (COORDS_PER_VERTEX + TEXCOORDS_PER_VERTEX) * 4; // 4 bytes per float

    private FloatBuffer mVertexBuffer;
    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mTimeHandle;

    private long mFrameCount = 0;
    private boolean mInitialized = false;

    public enum PatternType {
        SOLID,      // Solid gray - most compressible
        GRADIENT,   // Simple gradient
        TEXTURE     // Textured pattern - similar to real video
    }

    private PatternType mPatternType = PatternType.TEXTURE;

    public FakeGLRenderer() {
    }

    /**
     * Initialize GL resources. Must be called on GL thread.
     */
    public void init() {
        if (mInitialized) {
            Log.w(TAG, "Already initialized");
            return;
        }

        // Create vertex buffer
        mVertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mVertexBuffer.put(VERTICES).position(0);

        // Create shader program
        mProgram = createProgram(VERTEX_SHADER, getFragmentShaderForPattern(mPatternType));
        if (mProgram == 0) {
            throw new RuntimeException("Failed to create GL program");
        }

        // Get attribute/uniform locations
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        checkGlError("glGetAttribLocation aPosition");

        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        checkGlError("glGetAttribLocation aTexCoord");

        mTimeHandle = GLES20.glGetUniformLocation(mProgram, "uTime");
        checkGlError("glGetUniformLocation uTime");

        mInitialized = true;
        Log.d(TAG, "FakeGLRenderer initialized with pattern: " + mPatternType);
    }

    /**
     * Set the pattern type. Must call before init() or call release() + init() to switch.
     */
    public void setPatternType(PatternType type) {
        mPatternType = type;
        if (mInitialized) {
            Log.w(TAG, "Pattern type changed after init - need to release() and init() again");
        }
    }

    /**
     * Render a frame directly to the current GL context.
     * Must be called on GL thread with proper context current.
     * Handles lazy initialization on first call.
     */
    public void renderFrame(long timestampUs) {
        // Lazy init on first frame (ensures we're on GL thread with context current)
        if (!mInitialized) {
            init();
        }

        // Use shader program
        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        // Set time uniform (for animation)
        float timeValue = (float) timestampUs / 1000000.0f; // Convert to seconds
        GLES20.glUniform1f(mTimeHandle, timeValue);
        checkGlError("glUniform1f uTime");

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        // Set vertex data
        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, mVertexBuffer);
        checkGlError("glVertexAttribPointer aPosition");

        mVertexBuffer.position(COORDS_PER_VERTEX);
        GLES20.glVertexAttribPointer(mTexCoordHandle, TEXCOORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, mVertexBuffer);
        checkGlError("glVertexAttribPointer aTexCoord");

        // Draw full-screen quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");

        // Cleanup
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);

        mFrameCount++;
    }

    /**
     * Release GL resources. Must be called on GL thread.
     */
    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mInitialized = false;
        Log.d(TAG, "FakeGLRenderer released after " + mFrameCount + " frames");
    }

    private String getFragmentShaderForPattern(PatternType type) {
        switch (type) {
            case SOLID:
                return FRAGMENT_SHADER_SOLID;
            case GRADIENT:
                return FRAGMENT_SHADER_GRADIENT;
            case TEXTURE:
                return FRAGMENT_SHADER_TEXTURE;
            default:
                return FRAGMENT_SHADER_TEXTURE;
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }

        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fragmentShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        checkGlError("glCreateProgram");

        GLES20.glAttachShader(program, vertexShader);
        checkGlError("glAttachShader vertex");

        GLES20.glAttachShader(program, fragmentShader);
        checkGlError("glAttachShader fragment");

        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }

        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        checkGlError("glCreateShader type=" + type);

        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String errorLog = GLES20.glGetShaderInfoLog(shader);
            Log.e(TAG, "Could not compile shader " + type + ":");
            Log.e(TAG, "Shader source:\n" + source);
            Log.e(TAG, "Error log: " + errorLog);
            GLES20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    private void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            String msg = op + ": glError 0x" + Integer.toHexString(error);
            Log.e(TAG, msg);
            throw new RuntimeException(msg);
        }
    }
}
