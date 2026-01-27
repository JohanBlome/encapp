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
            "varying vec2 vTexCoord;\n" +
            "uniform float uTime;\n" +
            "void main() {\n" +
            "    // Pulsating gray with imperceptible vTexCoord contribution to prevent optimization\n" +
            "    float gray = 0.5 + 0.3 * sin(uTime * 2.0) + vTexCoord.x * 0.001;\n" +
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

    // Fragment shader - analog clock with hour, minute, second hands and frame counter
    private static final String FRAGMENT_SHADER_CLOCK =
            "precision highp float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform float uTime;\n" +
            "uniform float uFrameCount;\n" +
            "uniform float uAspectRatio;\n" +
            "\n" +
            "#define PI 3.14159265359\n" +
            "#define TWO_PI 6.28318530718\n" +
            "\n" +
            "// Draw a clock hand as a line segment from center\n" +
            "float drawHand(vec2 uv, float angle, float length, float width) {\n" +
            "    // Rotate by -90 degrees so 0 angle points up (12 o'clock)\n" +
            "    float a = -angle + PI * 0.5;\n" +
            "    vec2 dir = vec2(cos(a), sin(a));\n" +
            "    \n" +
            "    // Project point onto the hand direction\n" +
            "    float proj = dot(uv, dir);\n" +
            "    \n" +
            "    // Only draw from center outward to length\n" +
            "    if (proj < 0.0 || proj > length) return 0.0;\n" +
            "    \n" +
            "    // Distance from the line\n" +
            "    vec2 closestPoint = dir * proj;\n" +
            "    float dist = length(uv - closestPoint);\n" +
            "    \n" +
            "    // Smooth anti-aliased line\n" +
            "    return 1.0 - smoothstep(width * 0.5 - 0.005, width * 0.5 + 0.005, dist);\n" +
            "}\n" +
            "\n" +
            "// Draw tick marks around the clock face\n" +
            "float drawTicks(vec2 uv, float radius) {\n" +
            "    float result = 0.0;\n" +
            "    float angle = atan(uv.y, uv.x);\n" +
            "    float dist = length(uv);\n" +
            "    \n" +
            "    // Hour ticks (12)\n" +
            "    for (int i = 0; i < 12; i++) {\n" +
            "        float tickAngle = float(i) * TWO_PI / 12.0;\n" +
            "        vec2 tickDir = vec2(cos(tickAngle), sin(tickAngle));\n" +
            "        \n" +
            "        // Draw thick tick from radius-0.08 to radius\n" +
            "        float proj = dot(uv, tickDir);\n" +
            "        if (proj > radius - 0.1 && proj < radius) {\n" +
            "            vec2 closestPoint = tickDir * proj;\n" +
            "            float d = length(uv - closestPoint);\n" +
            "            float tickWidth = (mod(float(i), 3.0) == 0.0) ? 0.025 : 0.015;\n" +
            "            result = max(result, 1.0 - smoothstep(tickWidth * 0.5, tickWidth * 0.5 + 0.005, d));\n" +
            "        }\n" +
            "    }\n" +
            "    return result;\n" +
            "}\n" +
            "\n" +
            "// Draw a single digit at position\n" +
            "float drawDigit(vec2 uv, int digit, vec2 pos, float size) {\n" +
            "    vec2 p = (uv - pos) / size;\n" +
            "    if (p.x < 0.0 || p.x > 0.6 || p.y < 0.0 || p.y > 1.0) return 0.0;\n" +
            "    \n" +
            "    // 7-segment display encoding\n" +
            "    // Segments: top, top-right, bottom-right, bottom, bottom-left, top-left, middle\n" +
            "    float w = 0.15;\n" +
            "    float result = 0.0;\n" +
            "    \n" +
            "    // Segment patterns for digits 0-9\n" +
            "    // Each digit encoded as which segments are on\n" +
            "    bool seg[7];\n" +
            "    \n" +
            "    if (digit == 0) { seg[0]=true; seg[1]=true; seg[2]=true; seg[3]=true; seg[4]=true; seg[5]=true; seg[6]=false; }\n" +
            "    else if (digit == 1) { seg[0]=false; seg[1]=true; seg[2]=true; seg[3]=false; seg[4]=false; seg[5]=false; seg[6]=false; }\n" +
            "    else if (digit == 2) { seg[0]=true; seg[1]=true; seg[2]=false; seg[3]=true; seg[4]=true; seg[5]=false; seg[6]=true; }\n" +
            "    else if (digit == 3) { seg[0]=true; seg[1]=true; seg[2]=true; seg[3]=true; seg[4]=false; seg[5]=false; seg[6]=true; }\n" +
            "    else if (digit == 4) { seg[0]=false; seg[1]=true; seg[2]=true; seg[3]=false; seg[4]=false; seg[5]=true; seg[6]=true; }\n" +
            "    else if (digit == 5) { seg[0]=true; seg[1]=false; seg[2]=true; seg[3]=true; seg[4]=false; seg[5]=true; seg[6]=true; }\n" +
            "    else if (digit == 6) { seg[0]=true; seg[1]=false; seg[2]=true; seg[3]=true; seg[4]=true; seg[5]=true; seg[6]=true; }\n" +
            "    else if (digit == 7) { seg[0]=true; seg[1]=true; seg[2]=true; seg[3]=false; seg[4]=false; seg[5]=false; seg[6]=false; }\n" +
            "    else if (digit == 8) { seg[0]=true; seg[1]=true; seg[2]=true; seg[3]=true; seg[4]=true; seg[5]=true; seg[6]=true; }\n" +
            "    else if (digit == 9) { seg[0]=true; seg[1]=true; seg[2]=true; seg[3]=true; seg[4]=false; seg[5]=true; seg[6]=true; }\n" +
            "    else { seg[0]=false; seg[1]=false; seg[2]=false; seg[3]=false; seg[4]=false; seg[5]=false; seg[6]=false; }\n" +
            "    \n" +
            "    // Draw each segment as a rectangle\n" +
            "    // Top horizontal\n" +
            "    if (seg[0] && p.y > 0.85 && p.x > 0.1 && p.x < 0.5) result = 1.0;\n" +
            "    // Top-right vertical\n" +
            "    if (seg[1] && p.x > 0.45 && p.y > 0.5 && p.y < 0.9) result = 1.0;\n" +
            "    // Bottom-right vertical\n" +
            "    if (seg[2] && p.x > 0.45 && p.y > 0.1 && p.y < 0.5) result = 1.0;\n" +
            "    // Bottom horizontal\n" +
            "    if (seg[3] && p.y < 0.15 && p.x > 0.1 && p.x < 0.5) result = 1.0;\n" +
            "    // Bottom-left vertical\n" +
            "    if (seg[4] && p.x < 0.15 && p.y > 0.1 && p.y < 0.5) result = 1.0;\n" +
            "    // Top-left vertical\n" +
            "    if (seg[5] && p.x < 0.15 && p.y > 0.5 && p.y < 0.9) result = 1.0;\n" +
            "    // Middle horizontal\n" +
            "    if (seg[6] && p.y > 0.45 && p.y < 0.55 && p.x > 0.1 && p.x < 0.5) result = 1.0;\n" +
            "    \n" +
            "    return result;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    // Center UV coordinates\n" +
            "    vec2 uv = vTexCoord - 0.5;\n" +
            "    \n" +
            "    // Correct aspect ratio to make clock round\n" +
            "    // If aspect > 1 (wide), stretch x to compensate\n" +
            "    // If aspect < 1 (tall), stretch y to compensate\n" +
            "    if (uAspectRatio > 1.0) {\n" +
            "        uv.x *= uAspectRatio;\n" +
            "    } else {\n" +
            "        uv.y /= uAspectRatio;\n" +
            "    }\n" +
            "    \n" +
            "    // Background gradient\n" +
            "    vec3 bgColor = mix(vec3(0.15, 0.15, 0.2), vec3(0.25, 0.25, 0.35), vTexCoord.y);\n" +
            "    \n" +
            "    // Clock face\n" +
            "    float clockRadius = 0.35;\n" +
            "    float dist = length(uv);\n" +
            "    \n" +
            "    // Clock face circle (white with gray border)\n" +
            "    vec3 faceColor = vec3(0.95, 0.95, 0.92);\n" +
            "    float faceMask = 1.0 - smoothstep(clockRadius - 0.01, clockRadius, dist);\n" +
            "    float borderMask = smoothstep(clockRadius - 0.025, clockRadius - 0.015, dist) * \n" +
            "                       (1.0 - smoothstep(clockRadius - 0.01, clockRadius, dist));\n" +
            "    \n" +
            "    // Calculate time components\n" +
            "    float totalSeconds = uTime;\n" +
            "    float hours = mod(totalSeconds / 3600.0, 12.0);\n" +
            "    float minutes = mod(totalSeconds / 60.0, 60.0);\n" +
            "    float seconds = mod(totalSeconds, 60.0);\n" +
            "    int frameNum = int(mod(uTime * 30.0, 10000.0)); // Assuming 30fps\n" +
            "    \n" +
            "    // Calculate hand angles (in radians, 0 = 12 o'clock, clockwise)\n" +
            "    float hourAngle = hours * TWO_PI / 12.0;\n" +
            "    float minuteAngle = minutes * TWO_PI / 60.0;\n" +
            "    float secondAngle = seconds * TWO_PI / 60.0;\n" +
            "    \n" +
            "    // Draw clock hands\n" +
            "    float hourHand = drawHand(uv, hourAngle, 0.15, 0.025);\n" +
            "    float minuteHand = drawHand(uv, minuteAngle, 0.25, 0.018);\n" +
            "    float secondHand = drawHand(uv, secondAngle, 0.28, 0.008);\n" +
            "    \n" +
            "    // Draw tick marks\n" +
            "    float ticks = drawTicks(uv, clockRadius - 0.02);\n" +
            "    \n" +
            "    // Center dot\n" +
            "    float centerDot = 1.0 - smoothstep(0.015, 0.02, dist);\n" +
            "    \n" +
            "    // Compose clock face\n" +
            "    vec3 clockColor = faceColor;\n" +
            "    clockColor = mix(clockColor, vec3(0.3, 0.3, 0.3), ticks);         // Tick marks\n" +
            "    clockColor = mix(clockColor, vec3(0.2, 0.2, 0.25), hourHand);     // Hour hand (dark)\n" +
            "    clockColor = mix(clockColor, vec3(0.15, 0.15, 0.2), minuteHand);  // Minute hand (darker)\n" +
            "    clockColor = mix(clockColor, vec3(0.8, 0.1, 0.1), secondHand);    // Second hand (red)\n" +
            "    clockColor = mix(clockColor, vec3(0.8, 0.1, 0.1), centerDot);     // Center dot (red)\n" +
            "    clockColor = mix(clockColor, vec3(0.4, 0.35, 0.3), borderMask);   // Border\n" +
            "    \n" +
            "    // Mix clock face with background\n" +
            "    vec3 color = mix(bgColor, clockColor, faceMask);\n" +
            "    \n" +
            "    // Draw frame counter at bottom\n" +
            "    float digitSize = 0.06;\n" +
            "    float digitSpacing = digitSize * 0.7;\n" +
            "    vec2 counterPos = vec2(0.35, 0.08);\n" +
            "    \n" +
            "    // Extract digits from frame number (show last 4 digits)\n" +
            "    int d0 = int(mod(float(frameNum), 10.0));\n" +
            "    int d1 = int(mod(float(frameNum / 10), 10.0));\n" +
            "    int d2 = int(mod(float(frameNum / 100), 10.0));\n" +
            "    int d3 = int(mod(float(frameNum / 1000), 10.0));\n" +
            "    \n" +
            "    // Draw digits\n" +
            "    float digitMask = 0.0;\n" +
            "    digitMask += drawDigit(vTexCoord, d3, counterPos, digitSize);\n" +
            "    digitMask += drawDigit(vTexCoord, d2, counterPos + vec2(digitSpacing, 0.0), digitSize);\n" +
            "    digitMask += drawDigit(vTexCoord, d1, counterPos + vec2(digitSpacing * 2.0, 0.0), digitSize);\n" +
            "    digitMask += drawDigit(vTexCoord, d0, counterPos + vec2(digitSpacing * 3.0, 0.0), digitSize);\n" +
            "    \n" +
            "    // Frame counter color (green digital display look)\n" +
            "    color = mix(color, vec3(0.2, 0.9, 0.3), min(digitMask, 1.0));\n" +
            "    \n" +
            "    // Add \"FRAME\" label using simple rectangles\n" +
            "    // (Simplified - just draw a small indicator)\n" +
            "    if (vTexCoord.x > 0.35 && vTexCoord.x < 0.55 && vTexCoord.y > 0.05 && vTexCoord.y < 0.065) {\n" +
            "        color = mix(color, vec3(0.1, 0.5, 0.2), 0.8);\n" +
            "    }\n" +
            "    \n" +
            "    gl_FragColor = vec4(color, 1.0);\n" +
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
    private int mAspectRatioHandle;
    private int mWidth = 1;
    private int mHeight = 1;

    private long mFrameCount = 0;
    private boolean mInitialized = false;

    public enum PatternType {
        SOLID,      // Solid gray - most compressible
        GRADIENT,   // Simple gradient
        TEXTURE,    // Textured pattern - similar to real video
        CLOCK       // Analog clock with hour, minute, second hands and frame counter
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
        String fragmentShader = getFragmentShaderForPattern(mPatternType);
        Log.d(TAG, "Creating shader program for pattern: " + mPatternType);
        mProgram = createProgram(VERTEX_SHADER, fragmentShader);
        if (mProgram == 0) {
            Log.e(TAG, "Failed to create GL program for pattern: " + mPatternType);
            throw new RuntimeException("Failed to create GL program");
        }
        Log.d(TAG, "Created GL program: " + mProgram);

        // Get attribute/uniform locations
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        if (mPositionHandle < 0) {
            Log.e(TAG, "Failed to get aPosition attribute location (got " + mPositionHandle + ")");
            throw new RuntimeException("Failed to get aPosition attribute location");
        }
        Log.d(TAG, "aPosition handle: " + mPositionHandle);

        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        if (mTexCoordHandle < 0) {
            Log.e(TAG, "Failed to get aTexCoord attribute location (got " + mTexCoordHandle + ")");
            throw new RuntimeException("Failed to get aTexCoord attribute location");
        }
        Log.d(TAG, "aTexCoord handle: " + mTexCoordHandle);

        mTimeHandle = GLES20.glGetUniformLocation(mProgram, "uTime");
        Log.d(TAG, "uTime handle: " + mTimeHandle);

        // Get aspect ratio uniform location (only used by clock shader, but safe to query)
        mAspectRatioHandle = GLES20.glGetUniformLocation(mProgram, "uAspectRatio");
        Log.d(TAG, "uAspectRatio handle: " + mAspectRatioHandle);

        mInitialized = true;
        Log.d(TAG, "FakeGLRenderer initialized with pattern: " + mPatternType);
    }

    /**
     * Set the video dimensions. Used to calculate aspect ratio for round clock.
     */
    public void setDimensions(int width, int height) {
        mWidth = width > 0 ? width : 1;
        mHeight = height > 0 ? height : 1;
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

        // Double-check handles are valid (defensive)
        if (mProgram == 0 || mPositionHandle < 0 || mTexCoordHandle < 0) {
            Log.e(TAG, "Invalid GL state: program=" + mProgram + 
                       ", positionHandle=" + mPositionHandle + 
                       ", texCoordHandle=" + mTexCoordHandle);
            // Try to re-initialize
            mInitialized = false;
            init();
        }

        // Use shader program
        GLES20.glUseProgram(mProgram);
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glUseProgram failed with error 0x" + Integer.toHexString(error) + ", program=" + mProgram);
            throw new RuntimeException("glUseProgram failed");
        }

        // Set time uniform (for animation)
        float timeValue = (float) timestampUs / 1000000.0f; // Convert to seconds
        if (mTimeHandle >= 0) {
            GLES20.glUniform1f(mTimeHandle, timeValue);
        }

        // Set aspect ratio uniform (for round clock)
        if (mAspectRatioHandle >= 0) {
            float aspectRatio = (float) mWidth / (float) mHeight;
            GLES20.glUniform1f(mAspectRatioHandle, aspectRatio);
        }

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        // Set vertex data - position
        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, mVertexBuffer);
        error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glVertexAttribPointer aPosition failed: error=0x" + Integer.toHexString(error) + 
                       ", handle=" + mPositionHandle + ", buffer=" + mVertexBuffer);
            throw new RuntimeException("glVertexAttribPointer aPosition: glError 0x" + Integer.toHexString(error));
        }

        // Set vertex data - texcoord
        mVertexBuffer.position(COORDS_PER_VERTEX);
        GLES20.glVertexAttribPointer(mTexCoordHandle, TEXCOORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, mVertexBuffer);
        error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glVertexAttribPointer aTexCoord failed: error=0x" + Integer.toHexString(error) + 
                       ", handle=" + mTexCoordHandle);
            throw new RuntimeException("glVertexAttribPointer aTexCoord: glError 0x" + Integer.toHexString(error));
        }

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
            case CLOCK:
                return FRAGMENT_SHADER_CLOCK;
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
