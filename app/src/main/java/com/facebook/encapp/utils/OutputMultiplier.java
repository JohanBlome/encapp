package com.facebook.encapp.utils;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;
import com.facebook.encapp.utils.grafika.EglCore;
import com.facebook.encapp.utils.grafika.EglSurfaceBase;
import com.facebook.encapp.utils.grafika.FullFrameRect;
import com.facebook.encapp.utils.grafika.GlUtil;
import com.facebook.encapp.utils.grafika.Texture2dProgram;

import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Buffer objects for queuing render work to renderer thread
 */
abstract class RenderBufferObject {
    long mTimestampUs;
    int mFrameCount;
    Statistics mStats;  // For encoding measurement

    RenderBufferObject(long timestampUs, int frameCount, Statistics stats) {
        mTimestampUs = timestampUs;
        mFrameCount = frameCount;
        mStats = stats;
    }

    long getTimestampUs() {
        return mTimestampUs;
    }

    int getFrameCount() {
        return mFrameCount;
    }

    Statistics getStats() {
        return mStats;
    }
}

class RenderFrameBuffer extends RenderBufferObject {
    MediaCodec mCodec;
    int mBufferId;
    MediaCodec.BufferInfo mInfo;

    RenderFrameBuffer(MediaCodec codec, int id, MediaCodec.BufferInfo info, int frameCount, Statistics stats) {
        super(info.presentationTimeUs, frameCount, stats);
        mCodec = codec;
        mBufferId = id;
        mInfo = info;
    }
}

class RenderBitmapBuffer extends RenderBufferObject {
    Bitmap mBitmap;

    RenderBitmapBuffer(Bitmap bitmap, long timestampUs, int frameCount, Statistics stats) {
        super(timestampUs, frameCount, stats);
        mBitmap = bitmap;
    }
}

class RenderGLPatternBuffer extends RenderBufferObject {
    FakeGLRenderer mGLRenderer;

    RenderGLPatternBuffer(FakeGLRenderer glRenderer, long timestampUs, int frameCount, Statistics stats) {
        super(timestampUs, frameCount, stats);
        mGLRenderer = glRenderer;
    }
}

public class OutputMultiplier {
    final static int WAIT_TIME_SHORT_MS = 3000;  // 3 sec
    private static final String TAG = "encapp.mult";
    private final float[] mTmpMatrix = new float[16];
    final private Object mLock = new Object();
    private final Vector<FrameswapControl> mOutputSurfaces = new Vector<>();
    VsyncHandler mMessageHandler;
    int LATE_LIMIT_NS = 15 * 1000000; // ms
    Texture2dProgram.ProgramType mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT;
    private Renderer mRenderer;
    private EglCore mEglCore;
    private SurfaceTexture mInputTexture;
    private FullFrameRect mFullFrameBlit;
    private FullFrameRect mBitmapBlit;  // Separate blit for 2D textures (bitmaps)
    private Surface mInputSurface;
    private int mTextureId;
    private int mBitmapTextureId = -1;  // Separate 2D texture for bitmap input
    private FrameswapControl mMasterSurface = null;
    private String mName = "OutputMultiplier";
    private int mWidth = -1;
    private int mHeight = -1;
    private boolean mVsynchWait = true;
    boolean mHighPrio = false;

    public OutputMultiplier(Texture2dProgram.ProgramType type, VsyncHandler vsyncHandler) {
        mProgramType = type;
        mMessageHandler = vsyncHandler;
    }

    public OutputMultiplier(VsyncHandler vsyncHandler) {
        mMessageHandler = vsyncHandler;
    }

    public void setHighPrio() {
        mHighPrio = true;
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public FrameswapControl addSurface(Surface surface) {
        Log.d(TAG, "ADD SURFACE: "+mRenderer);
        if (mRenderer != null) {
            Log.d(TAG, "Add surface");
            return mRenderer.addSurface(surface);
        } else {
            Log.d(TAG, "Create render");
            mRenderer = new Renderer(surface);
            mRenderer.setName(mName);
            mMessageHandler.addListener(mRenderer);
            return mRenderer.setup();
        }

    }

    public void setName(String name) {
        mName = name;
        if (mRenderer != null) {
            mRenderer.setName(mName);
        }
    }

    public EglSurfaceBase addSurfaceTexture(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "ADD SURFACETEXTURE: "+mRenderer);
        if (mRenderer != null) {
            Log.d(TAG, "Add surface texture");
            return mRenderer.addSurfaceTexture(surfaceTexture);
        } else {
            // Start up the Renderer thread.  It'll sleep until the TextureView is ready.
            mRenderer = new Renderer(surfaceTexture);
            mMessageHandler.addListener(mRenderer);
            return mRenderer.setup();
        }

    }

    public void removeFrameSwapControl(FrameswapControl control) {
        synchronized (mLock) {
            mOutputSurfaces.remove(control);
        }
    }

    public void confirmSize(int width, int height) {
        if (mRenderer != null) {
            Log.d(TAG, "Try to confirm size WxH = " + width + "x" + height);
            mRenderer.confirmSize(width, height);
        } else {
            Log.e(TAG, "No renderer exists");
            mWidth = width;
            mHeight = height;
        }
    }

    public void setRealtime(boolean realtime) {
        mVsynchWait = realtime;
    }

    public long awaitNewImage() {
        return mRenderer.awaitNewImage();
    }


    public void newBitmapAvailable(Bitmap bitmap, long timestampUsec, int frameCount, Statistics stats) {
        mRenderer.newBitmapAvailable(bitmap, timestampUsec, frameCount, stats);
    }

    /**
     * Signal that a new GL-rendered frame is available (for fake input).
     * This is used when rendering synthetic patterns directly with GL.
     */
    public void newGLPatternFrame(FakeGLRenderer glRenderer, long timestampUsec, int frameCount, Statistics stats) {
        mRenderer.newGLPatternFrame(glRenderer, timestampUsec, frameCount, stats);
    }

    public void newFrameAvailable() {
        mRenderer.newFrameAvailable();
    }

    public void stopAndRelease() {
        mMessageHandler.removeListener(mRenderer);
        if (mRenderer != null) {
            mRenderer.quit();
        }

        synchronized (mLock) {
            for (FrameswapControl swap : mOutputSurfaces) {
                swap.release();
            }
            mOutputSurfaces.removeAllElements();
        }
        if (mMasterSurface != null)
            mMasterSurface.release();
        if (mInputSurface != null) {
            mInputSurface.release();
        }
        if (mInputTexture != null) {
            mInputTexture.release();
        }
        if (mEglCore != null) {
            mEglCore.release();
        }
        Log.d(TAG, "Done stop and release");
    }

    public void newFrameAvailableInBuffer(MediaCodec codec, int bufferId, MediaCodec.BufferInfo info, int frameCount, Statistics stats) {
        if (mRenderer != null) { // it will be null if no surface is connected
            mRenderer.newFrameAvailableInBuffer(codec, bufferId, info, frameCount, stats);
        } else {
            try {
                codec.releaseOutputBuffer(bufferId, false);
            } catch (MediaCodec.CodecException mec) {
                Log.e(TAG, "Buffer release failed: " + mec.getMessage());
            } catch (IllegalStateException ise) {
                Log.e(TAG, "Buffer release failed, illegal state exception " + ise.getMessage());
            }
        }
    }

    private class Renderer extends Thread implements SurfaceTexture.OnFrameAvailableListener, VsyncListener {

        // Waiting for incoming frames on input surface
        private final Object mInputFrameLock = new Object();
        //Notify threads waiting for painted surfaces
        private final Object mFrameDrawnLock = new Object();
        // Wait for vsynch and to synch with display
        private final Object mVSynchLock = new Object();
        private final Object mSizeLock = new Object();
        boolean mDone = false;
        ConcurrentLinkedQueue<RenderBufferObject> mFrameBuffers = new ConcurrentLinkedQueue<>();
        private long mLatestTimestampNsec = 0;
        private long mTimestamp0Ns = -1;
        private long mCurrentVsyncNs = 0;
        private long mVsync0 = -1;
        // So, we can ignore sync...
        private int frameAvailable = 0;
        // temporary object
        private Object mSurfaceObject;
        //private Bitmap mBitmap = null;

        public Renderer(Object surface) {
            super("Outputmultiplier Renderer");
            mSurfaceObject = surface;
        }

        @Override
        public void run() {
            Log.d(TAG, "Start rend");
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            FrameswapControl windowSurface = null;
            if (mSurfaceObject instanceof SurfaceTexture) {
                mMasterSurface = new FrameswapControl(mEglCore, (SurfaceTexture) mSurfaceObject);
            } else if (mSurfaceObject instanceof Surface) {
                mMasterSurface = new FrameswapControl(mEglCore, (Surface) mSurfaceObject, false);
            } else {
                throw new RuntimeException("No surface or SurfaceTexture available: " + mSurfaceObject);
            }
            mSurfaceObject = null; // we do not need it anymore
            mOutputSurfaces.add(mMasterSurface);
            mMasterSurface.makeCurrent();

            // Create shader program for camera input (external OES texture)
            mFullFrameBlit = new FullFrameRect(
                    new Texture2dProgram(mProgramType));
            mTextureId = mFullFrameBlit.createTextureObject();
            mInputTexture = new SurfaceTexture(mTextureId);

            // Create shader program for bitmap input (2D texture)
            mBitmapBlit = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));

            // We need to know how big the texture should be
            synchronized (mSizeLock) {
                try {
                    if (mWidth == -1 && mHeight == -1) {
                        mSizeLock.wait(WAIT_TIME_SHORT_MS);
                    }
                } catch (InterruptedException e) {e.printStackTrace();
                }
            }
            Log.d(TAG, "Set source texture buffer size: WxH = " + mWidth + "x" + mHeight);
            if (mWidth > 0 && mHeight > 0) {
                mInputTexture.setDefaultBufferSize(mWidth, mHeight);
            }
            mInputTexture.setOnFrameAvailableListener(this);
            mInputSurface = new Surface(mInputTexture);

            if (mHighPrio) this.setPriority(Thread.MAX_PRIORITY);
            while (!mDone) {
                synchronized (mInputFrameLock) {
                    try {
                        if (frameAvailable <= 0) {
                            mInputFrameLock.wait(WAIT_TIME_SHORT_MS);
                        }

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (mDone) break;
                if (mFrameBuffers.size() > 0) {
                    while (mFrameBuffers.size() > 0) {
                        drawBufferSwap();
                    }
                }  else {
                    drawFrameImmediateSwap();
                }
            }
        }

        public void setString(String name) {
            this.setName(name);
        }

        public void vsync(long timeNs) {
            synchronized (mVSynchLock) {
                mCurrentVsyncNs = timeNs;
                mVSynchLock.notifyAll();
            }
        }

        public void releaseEgl() {
            mEglCore.release();
        }


        public FrameswapControl setup() {
            this.start();
            while (mMasterSurface == null) {
                try {
                    sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return mMasterSurface;
        }

        public FrameswapControl addSurface(Surface surface) {
            FrameswapControl windowSurface = null;
            synchronized (mLock) {
                windowSurface = new FrameswapControl(mEglCore, surface, true);

                mOutputSurfaces.add(windowSurface);
            }

            return windowSurface;
        }

        public FrameswapControl addSurfaceTexture(SurfaceTexture texture) {
            FrameswapControl windowSurface = null;
            synchronized (mLock) {
                windowSurface = new FrameswapControl(mEglCore, texture);

                mOutputSurfaces.add(windowSurface);
            }
            return windowSurface;
        }

        private long awaitNewImage() {
            long time = ClockTimes.currentTimeMs();
            synchronized (mFrameDrawnLock) {
                try {
                    mFrameDrawnLock.wait(WAIT_TIME_SHORT_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    long stuck = ClockTimes.currentTimeMs() - time;
                    Log.e(TAG, "Forced to release a timed wait indicates an error.");
                    Log.e(TAG, "Release me. I was stuck for " + stuck + " ms");
                    stopAndRelease();
                }
            }
            return mLatestTimestampNsec;
        }

        public void drawBufferSwap() {
            if (mEglCore == null) {
                Log.d(TAG, "Skipping drawFrame after shutdown");
                return;
            }
            try {
                synchronized (mVSynchLock) {
                    RenderBufferObject buffer = mFrameBuffers.poll();
                    if (buffer == null) {
                        return;
                    }
                    long diff = 0;
                    long timeNs = 0;
                    timeNs = buffer.getTimestampUs() * 1000;
                    if (mVsynchWait) {
                        if (mTimestamp0Ns == -1) {
                            mTimestamp0Ns = timeNs;
                            mVsync0 = mCurrentVsyncNs;
                        }
                        diff = timeNs - mTimestamp0Ns;


                        // Drop frame if we have frame in the buffert and we are more than one frame late
                        if((diff - (mCurrentVsyncNs - mVsync0) < -2L * LATE_LIMIT_NS) && mFrameBuffers.size() > 0) {
                            RenderFrameBuffer fb = (RenderFrameBuffer)buffer;
                            Log.d(TAG, "Drop late frame " + (diff - (mCurrentVsyncNs - mVsync0)/1000000) + " ms ");
                            fb.mCodec.releaseOutputBuffer(fb.mBufferId, false);
                            synchronized (mFrameDrawnLock) {
                                frameAvailable = (frameAvailable > 0) ? frameAvailable - 1 : 0;
                                mFrameDrawnLock.notifyAll();
                            }
                        }

                        // If further away than 15ms, wait for a new sync.
                        while ((diff - (mCurrentVsyncNs - mVsync0)) > LATE_LIMIT_NS) {
                            try {
                                mVSynchLock.wait(WAIT_TIME_SHORT_MS);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    boolean isGLPattern = false;
                    try {
                        mLatestTimestampNsec = timeNs;
                        if (buffer instanceof RenderFrameBuffer) {
                            // Draw texture from MediaCodec
                            RenderFrameBuffer fb = (RenderFrameBuffer)buffer;
                            fb.mCodec.releaseOutputBuffer(fb.mBufferId, true);
                            mMasterSurface.makeCurrent();
                            mInputTexture.updateTexImage();
                            mInputTexture.getTransformMatrix(mTmpMatrix);
                        } else if (buffer instanceof RenderGLPatternBuffer) {
                            // Render GL pattern directly - FAST PATH!
                            // This renders directly to surfaces, no texture blit needed
                            RenderGLPatternBuffer glBuffer = (RenderGLPatternBuffer)buffer;
                            renderGLPattern(glBuffer.mGLRenderer, glBuffer.mTimestampUs);
                            isGLPattern = true;
                        } else {
                            // Draw bitmap
                            drawBitmap(((RenderBitmapBuffer)buffer).mBitmap);
                        }
                    } catch (IllegalStateException ise) {
                        // not important
                    }


                    // For GL patterns, we've already rendered directly to surfaces
                    // For texture/bitmap, we need to blit to all surfaces
                    if (!isGLPattern) {
                    mMasterSurface.setPresentationTime(mLatestTimestampNsec);

                    synchronized (mLock) {
                        // Use the appropriate blitter and texture based on input type
                        FullFrameRect blitter = (mBitmapTextureId != -1) ? mBitmapBlit : mFullFrameBlit;
                        int textureToUse = (mBitmapTextureId != -1) ? mBitmapTextureId : mTextureId;

                        for (FrameswapControl surface : mOutputSurfaces) {
                            if (surface.keepFrame()) {
                                surface.makeCurrent();
                                int width = surface.getWidth();
                                int height = surface.getHeight();
                                GLES20.glViewport(0, 0, width, height);
                                blitter.drawFrame(textureToUse, mTmpMatrix);
                                surface.setPresentationTime(mLatestTimestampNsec);
                                surface.swapBuffers();
                            }
                        }

                        // NOW start encoding measurement - frame has been submitted to encoder!
                        // Called ONCE per frame after all surfaces are swapped, not once per surface.
                        // This measures only encoder time, not preparation/rendering time.
                        if (buffer.getStats() != null) {
                            buffer.getStats().startEncodingFrame(buffer.getTimestampUs(), buffer.getFrameCount());
                        }
                    }
                } else {
                    // GL pattern already rendered, just set timestamps and swap
                    synchronized (mLock) {
                        for (FrameswapControl surface : mOutputSurfaces) {
                            if (surface.keepFrame()) {
                                // Must make surface current before swap on some EGL implementations
                                surface.makeCurrent();
                                surface.setPresentationTime(mLatestTimestampNsec);
                                surface.swapBuffers();
                            }
                        }

                        // NOW start encoding measurement - frame has been submitted to encoder!
                        // Called ONCE per frame after all surfaces are swapped, not once per surface.
                        // This measures only encoder time, not GL rendering or queuing time.
                        if (buffer.getStats() != null) {
                            buffer.getStats().startEncodingFrame(buffer.getTimestampUs(), buffer.getFrameCount());
                        }
                    }
                }

                    synchronized (mFrameDrawnLock) {
                        frameAvailable = (frameAvailable > 0) ? frameAvailable - 1 : 0;
                        mFrameDrawnLock.notifyAll();
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "Exception: " + ex.getMessage());
            }
        }

        public void drawFrameImmediateSwap() {
            try {
                if (mEglCore == null) {
                    Log.d(TAG, "Skipping drawFrame after shutdown");
                    return;
                }
                mMasterSurface.makeCurrent();
                mInputTexture.updateTexImage();
                mInputTexture.getTransformMatrix(mTmpMatrix);
                mLatestTimestampNsec = mInputTexture.getTimestamp();

                synchronized (mLock) {
                    for (FrameswapControl surface : mOutputSurfaces) {

                        try {
                            if (surface.keepFrame()) {
                                surface.makeCurrent();
                                int width = surface.getWidth();
                                int height = surface.getHeight();
                                GLES20.glViewport(0, 0, width, height);
                                mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
                                surface.setPresentationTime(mLatestTimestampNsec);
                                surface.swapBuffers();
                            }
                        } catch (Exception ex) {
                            Log.e(TAG, "Exception when drawing: " + ex);
                        }
                    }
                }

                synchronized (mFrameDrawnLock) {
                    frameAvailable = (frameAvailable > 0) ? frameAvailable - 1 : 0;
                    mFrameDrawnLock.notifyAll();
                }
            } catch (Exception ex) {
                Log.e(TAG, "Exception: " + ex.getMessage());
            }
        }

        public void drawBitmap(Bitmap bitmap) {
            try {
                if (mEglCore == null) {
                    Log.d(TAG, "Skipping drawFrame after shutdown");
                    return;
                }
                if (mMasterSurface == null) {
                    Log.e(TAG, "Master surface is null, cannot draw bitmap!");
                    return;
                }
                mMasterSurface.makeCurrent();

                // Create a separate 2D texture for bitmap input (only once)
                if (mBitmapTextureId == -1) {
                    int[] textures = new int[1];
                    GLES20.glGenTextures(1, textures, 0);
                    mBitmapTextureId = textures[0];
                    Log.d(TAG, "Created 2D texture for bitmap input: " + mBitmapTextureId);

                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mBitmapTextureId);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    GlUtil.checkGlError("create 2D texture");
                } else {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mBitmapTextureId);
                }

                // Load bitmap into the 2D texture
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                GlUtil.checkGlError("GLUtils.texImage2D");

                // Set up transform matrix for bitmap (no transform from SurfaceTexture)
                Matrix.setIdentityM(mTmpMatrix, 0);
                Matrix.rotateM(mTmpMatrix, 0, 180, 1f, 0, 0);
            } catch (Exception ex) {
                Log.e(TAG, "Exception in drawBitmap: " + ex.getMessage());
                ex.printStackTrace();
            }
        }

        /**
         * Render GL pattern directly to all surfaces - FAST PATH!
         * No bitmap, no texture upload, just pure GL rendering.
         */
        public void renderGLPattern(FakeGLRenderer glRenderer, long timestampUs) {
            try {
                if (mEglCore == null) {
                    Log.d(TAG, "Skipping GL render after shutdown");
                    return;
                }

                // Render pattern to all surfaces
                synchronized (mLock) {
                    for (FrameswapControl surface : mOutputSurfaces) {
                        if (surface.keepFrame()) {
                            surface.makeCurrent();
                            int width = surface.getWidth();
                            int height = surface.getHeight();
                            GLES20.glViewport(0, 0, width, height);

                            // Render GL pattern directly - ZERO CPU overhead!
                            glRenderer.renderFrame(timestampUs);

                            // No need to set transform matrix - pattern fills viewport
                        }
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "Exception in renderGLPattern: " + ex.getMessage());
                ex.printStackTrace();
            }
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (mInputFrameLock) {
                frameAvailable += 1;
                mInputFrameLock.notifyAll();
            }
        }

        public void newFrameAvailableInBuffer(MediaCodec codec, int id, MediaCodec.BufferInfo info, int frameCount, Statistics stats) {
            synchronized (mInputFrameLock) {
                mFrameBuffers.offer(new RenderFrameBuffer(codec, id, info, frameCount, stats));
                frameAvailable += 1;
                mInputFrameLock.notifyAll();
            }
        }

        public void newFrameAvailable() {
            synchronized (mInputFrameLock) {
                frameAvailable += 1;
                mInputFrameLock.notifyAll();
            }
        }

        public void newBitmapAvailable(Bitmap bitmap, long timestampUsec, int frameCount, Statistics stats) {
            synchronized (mInputFrameLock) {
                mFrameBuffers.offer(new RenderBitmapBuffer(bitmap.copy(bitmap.getConfig(), true), timestampUsec, frameCount, stats));
                frameAvailable += 1;
                mInputFrameLock.notifyAll();
            }
            if (mVsynchWait) {
                synchronized (mFrameDrawnLock) {
                    try {
                        mFrameDrawnLock.wait(WAIT_TIME_SHORT_MS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        /**
         * Render a GL pattern frame - queues the work to be done on GL thread.
         * This is the fast path for fake input - no bitmap overhead!
         * OPTIMIZED: No blocking wait - just queue and return immediately.
         */
        public void newGLPatternFrame(FakeGLRenderer glRenderer, long timestampUsec, int frameCount, Statistics stats) {
            synchronized (mInputFrameLock) {
                // Queue a GL render request (will be processed on GL thread in main loop)
                mFrameBuffers.offer(new RenderGLPatternBuffer(glRenderer, timestampUsec, frameCount, stats));
                frameAvailable += 1;
                mInputFrameLock.notifyAll();
            }

            // REMOVED BLOCKING WAIT - GL rendering is async, no need to wait for frame drawn
            // The bitmap path needs to wait because it copies memory, but GL just queues work

            // NOTE: startEncodingFrame will be called AFTER swapBuffers() in drawBufferSwap()
            // to measure only the encoding time, not the GL rendering + queuing time.
        }

        public void quit() {
            mDone = true;
            synchronized (mInputFrameLock) {
                Log.e(TAG, "Release inputframe lock!");
                mInputFrameLock.notifyAll();
            }
            synchronized (mFrameDrawnLock) {
                Log.e(TAG, "Release frame drawn lock!");
                mFrameDrawnLock.notifyAll();
            }
        }

        public void confirmSize(int width, int height) {
            synchronized (mSizeLock) {
                mWidth = width;
                mHeight = height;
                mSizeLock.notifyAll();
            }
        }
    }
}
