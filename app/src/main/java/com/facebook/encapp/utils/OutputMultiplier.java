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

public class OutputMultiplier {
    final static int WAIT_TIME_SHORT_MS = 3000;  // 3 sec
    private static final String TAG = "encapp.mult";
    private final float[] mTmpMatrix = new float[16];
    final private Object mLock = new Object();
    private final Vector<FrameswapControl> mOutputSurfaces = new Vector<>();
    VsyncHandler mMessageHandler;
    int LATE_LIMIT_NS = 15 * 1000000000; // ms
    Texture2dProgram.ProgramType mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT;
    private Renderer mRenderer;
    private EglCore mEglCore;
    private SurfaceTexture mInputTexture;
    private FullFrameRect mFullFrameBlit;
    private Surface mInputSurface;
    private int mTextureId;
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


    public void newBitmapAvailable(Bitmap bitmap, long timestampUsec) {
        mRenderer.newBitmapAvailable(bitmap, timestampUsec);
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

    public void newFrameAvailableInBuffer(MediaCodec codec, int bufferId, MediaCodec.BufferInfo info) {
        if (mRenderer != null) { // it will be null if no surface is connected
            mRenderer.newFrameAvailableInBuffer(codec, bufferId, info);
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
        ConcurrentLinkedQueue<BufferObject> mFrameBuffers = new ConcurrentLinkedQueue<>();
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
            mFullFrameBlit = new FullFrameRect(
                    new Texture2dProgram(mProgramType));
            mTextureId = mFullFrameBlit.createTextureObject();
            mInputTexture = new SurfaceTexture(mTextureId);

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
                    BufferObject buffer = mFrameBuffers.poll();
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
                        while ((diff - (mCurrentVsyncNs - mVsync0)) > LATE_LIMIT_NS) {
                            try {
                                mVSynchLock.wait(WAIT_TIME_SHORT_MS);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        // Drop frame if we have frame in the buffert and we are more than one frame late
                        if((diff - (mCurrentVsyncNs - mVsync0)) < -2 * LATE_LIMIT_NS && mFrameBuffers.size() > 0) {
                            FrameBuffer fb = (FrameBuffer)buffer;
                            fb.mCodec.releaseOutputBuffer(fb.mBufferId, false);
                            synchronized (mFrameDrawnLock) {
                                frameAvailable = (frameAvailable > 0) ? frameAvailable - 1 : 0;
                                mFrameDrawnLock.notifyAll();
                            }
                        }
                    }
                    try {
                        mLatestTimestampNsec = timeNs;
                        if (buffer instanceof FrameBuffer) {
                            // Draw texture
                            FrameBuffer fb = (FrameBuffer)buffer;
                            fb.mCodec.releaseOutputBuffer(fb.mBufferId, true);
                            mMasterSurface.makeCurrent();
                            mInputTexture.updateTexImage();
                            mInputTexture.getTransformMatrix(mTmpMatrix);
                        } else {
                            // Draw bitmap
                            drawBitmap(((BitmapBuffer)buffer).mBitmap);
                        }
                    } catch (IllegalStateException ise) {
                        // not important
                    }

                }
                mMasterSurface.setPresentationTime(mLatestTimestampNsec);

                synchronized (mLock) {
                    for (FrameswapControl surface : mOutputSurfaces) {
                        if (surface.keepFrame()) {
                            surface.makeCurrent();
                            int width = surface.getWidth();
                            int height = surface.getHeight();
                            GLES20.glViewport(0, 0, width, height);
                            mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
                            surface.setPresentationTime(mLatestTimestampNsec);
                            surface.swapBuffers();
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
                mMasterSurface.makeCurrent();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                        GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                        GLES20.GL_LINEAR);
                GlUtil.checkGlError("loadImageTexture");
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                GlUtil.checkGlError("loadImageTexture");
                mInputTexture.getTransformMatrix(mTmpMatrix);
                Matrix.rotateM(mTmpMatrix, 0, 180, 1f, 0, 0);
            } catch (Exception ex) {
                Log.e(TAG, "Exception: " + ex.getMessage());
            }
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (mInputFrameLock) {
                frameAvailable += 1;
                mInputFrameLock.notifyAll();
            }
        }

        public void newFrameAvailableInBuffer(MediaCodec codec, int id, MediaCodec.BufferInfo info) {
            synchronized (mInputFrameLock) {
                mFrameBuffers.offer(new FrameBuffer(codec, id, info));
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

        public void newBitmapAvailable(Bitmap bitmap, long timestampUsec) {
            synchronized (mInputFrameLock) {
                mFrameBuffers.offer(new BitmapBuffer(bitmap.copy(bitmap.getConfig(), true), timestampUsec));
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
