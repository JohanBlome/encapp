package com.facebook.encapp.utils;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;


import com.facebook.encapp.utils.grafika.EglCore;
import com.facebook.encapp.utils.grafika.EglSurfaceBase;
import com.facebook.encapp.utils.grafika.FullFrameRect;
import com.facebook.encapp.utils.grafika.Texture2dProgram;
import com.facebook.encapp.utils.grafika.WindowSurface;

import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;


public class OutputMultiplier {
    private static final String TAG = "encapp.mult";
    private Renderer mRenderer;
    private EglCore mEglCore;
    private SurfaceTexture mInputTexture;
    private FullFrameRect mFullFrameBlit;
    private Surface mInputsurface;
    private final float[] mTmpMatrix = new float[16];
    private int mTextureId;
    private EglSurfaceBase mMasterSurface = null;
    final static int WAIT_TIME_SHORT_MS = 3000;  // 3 sec
    private String mName = "OutputMultiplier";
    MessageHandler mMessageHandler;

    private Object mLock = new Object();
    private Vector<EglSurfaceBase> mEglSurfaces = new Vector<>();
    Texture2dProgram.ProgramType mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT;

    public OutputMultiplier(Texture2dProgram.ProgramType type) {
        super();
        mProgramType = type;
    }


    public OutputMultiplier() {
        mMessageHandler= new MessageHandler();
        mMessageHandler.start();
    }

    public Surface getInputSurface(){
        return mInputsurface;
    }

    public EglSurfaceBase addSurface(Surface surface) {
        if (mRenderer != null) {
            return mRenderer.addSurface(surface);
        } else {
            Log.d(TAG, "Create render");
            mRenderer = new Renderer(surface);
            mRenderer.setName(mName);
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
        if (mRenderer != null) {
            return mRenderer.addSurfaceTexture(surfaceTexture);
        } else {
            // Start up the Renderer thread.  It'll sleep until the TextureView is ready.
            mRenderer = new Renderer(surfaceTexture);
            return mRenderer.setup();
        }
        
    }

    public void removeEglSurface(EglSurfaceBase surface) {
        synchronized (mLock) {
            mEglSurfaces.remove(surface);
        }
    }

    public long awaitNewImage() {
        return mRenderer.awaitNewImage();
    }


    public void newFrameAvailable() {
       mRenderer.newFrameAvailable();
    }

    public void stopAndRelease() {
        mRenderer.quit();
    }
    
    public void newFrameAvailableInBuffer(MediaCodec codec, int bufferId, MediaCodec.BufferInfo info) {
        if (mRenderer != null) { // it will be null if no surface is connected
            mRenderer.newFrameAvailableInBuffer(codec, bufferId, info);
        } else {
            codec.releaseOutputBuffer(bufferId, false);
        }
    }

    private class MessageHandler extends Thread implements  Choreographer.FrameCallback {
        private Handler mHandler;

        @Override
        public void run() {
            Looper.prepare();
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    // process incoming messages here
                    Log.d(TAG, "Message");
                }
            };
            Choreographer.getInstance().postFrameCallback(this);
            Looper.loop();
            Log.d(TAG, "Exit");
        }


        /**
         * Called when a new display frame is being rendered.
         * <p>
         * This method provides the time in nanoseconds when the frame started being rendered.
         * The frame time provides a stable time base for synchronizing animations
         * and drawing.  It should be used instead of {@link SystemClock#uptimeMillis()}
         * or {@link System#nanoTime()} for animations and drawing in the UI.  Using the frame
         * time helps to reduce inter-frame jitter because the frame time is fixed at the time
         * the frame was scheduled to start, regardless of when the animations or drawing
         * callback actually runs.  All callbacks that run as part of rendering a frame will
         * observe the same frame time so using the frame time also helps to synchronize effects
         * that are performed by different callbacks.
         * </p><p>
         * Please note that the framework already takes care to process animations and
         * drawing using the frame time as a stable time base.  Most applications should
         * not need to use the frame time information directly.
         * </p>
         *
         * @param frameTimeNanos The time in nanoseconds when the frame started being rendered,
         *                       in the {@link System#nanoTime()} timebase.  Divide this value by {@code 1000000}
         *                       to convert it to the {@link SystemClock#uptimeMillis()} time base.
         */
        @Override
        public void doFrame(long frameTimeNanos) {
            if (mRenderer != null)
                mRenderer.vSync(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private class Renderer extends Thread implements SurfaceTexture.OnFrameAvailableListener {

        private long mLatestTimestamp = 0;
        private long mTimestamp0 = -1;
        private long mCurrentVsync = 0;
        private long mVsync0 = -1;

        // So, we can ignore sync...
        private int frameAvailable = 0;
        // Waiting for incoming frames on input surface
        private Object mInputFrameLock = new Object();
        //Notify threads waiting for painted surfaces
        private Object mFrameDrawnLock = new Object();
        // Wait for vsynch and to synch with display
        private Object mVSynchLock = new Object();
        // temporary object
        private Object mSurfaceObject;
        boolean mDone = false;

        ConcurrentLinkedQueue<FrameBuffer> mFrameBuffers = new ConcurrentLinkedQueue<>();

        public Renderer(Object surface) {
            super("Outputmultiplier Renderer");
            mSurfaceObject = surface;
        }

        @Override
        public void run() {
            Log.d(TAG, "Start rend");
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            WindowSurface windowSurface = null;
            if (mSurfaceObject instanceof SurfaceTexture){
                mMasterSurface = new WindowSurface(mEglCore, (SurfaceTexture)mSurfaceObject);
            } else if (mSurfaceObject instanceof Surface) {
                mMasterSurface = new WindowSurface(mEglCore, (Surface)mSurfaceObject, false);
            } else {
                throw new RuntimeException("No surface of SurfaceTexture available: " + mSurfaceObject);
            }
            mSurfaceObject = null; // we do not need it anymore
            mEglSurfaces.add(mMasterSurface);
            mMasterSurface.makeCurrent();
            mFullFrameBlit = new FullFrameRect(
                    new Texture2dProgram(mProgramType));
            mTextureId = mFullFrameBlit.createTextureObject();
            mInputTexture = new SurfaceTexture(mTextureId);
            mInputTexture.setOnFrameAvailableListener(this);
            mInputsurface = new Surface(mInputTexture);
            this.setPriority(Thread.MAX_PRIORITY);
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
                    drawFrameFromBuffer();
                } else {
                    drawFrameImmediate();
                }
            }
        }

        public void setString(String name) {
            this.setName(name);
        }
        
        public void vSync(long time) {
            synchronized (mVSynchLock) {
                if (mVsync0 == -1) {
                    mVsync0 = time;
                }
                mCurrentVsync = time - mVsync0;
                mVSynchLock.notifyAll();
            }
        }
        
        public void releaseEgl() {
            mEglCore.release();
        }


        public EglSurfaceBase setup(){
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

        public EglSurfaceBase addSurface(Surface surface) {
            WindowSurface windowSurface = null;
            synchronized (mLock) {
                windowSurface = new WindowSurface(mEglCore, surface, true);

                mEglSurfaces.add(windowSurface);
            }

            return windowSurface;
        }

        public EglSurfaceBase addSurfaceTexture(SurfaceTexture texture) {
            WindowSurface windowSurface = null;
            synchronized (mLock) {
                windowSurface = new WindowSurface(mEglCore, texture);

                mEglSurfaces.add(windowSurface);
            }
            return windowSurface;
        }

        private long awaitNewImage() {
            synchronized (mFrameDrawnLock) {
                try {
                    mFrameDrawnLock.wait(WAIT_TIME_SHORT_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return mLatestTimestamp;
        }
        
        public void drawFrameFromBuffer(){
            if (mEglCore == null) {
                Log.d(TAG, "Skipping drawFrame after shutdown");
                return;
            }
            while (mFrameBuffers.size() > 0) {
                synchronized (mVSynchLock) {
                    FrameBuffer buffer = mFrameBuffers.poll();
                    if (buffer ==  null) {
                        break;
                    }

                    if (mTimestamp0 == -1) {
                        mTimestamp0 = buffer.mInfo.presentationTimeUs;
                    }
                    long diff = (buffer.mInfo.presentationTimeUs - mTimestamp0) * 1000;
                    while (diff - mCurrentVsync > 0) {
                        try {
                            mVSynchLock.wait(WAIT_TIME_SHORT_MS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    try {
                        buffer.mCodec.releaseOutputBuffer(buffer.mBufferId, true);
                    } catch (IllegalStateException ise){
                        // not important
                        break;
                    }
                    mMasterSurface.makeCurrent();
                    mInputTexture.updateTexImage();
                    mInputTexture.getTransformMatrix(mTmpMatrix);
                    mLatestTimestamp = mInputTexture.getTimestamp();

                }

                synchronized (mLock) {
                    for (EglSurfaceBase surface : mEglSurfaces) {

                        surface.makeCurrent();
                        int width = surface.getWidth();
                        int height = surface.getHeight();
                        GLES20.glViewport(0, 0, width, height);
                        mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
                        surface.setPresentationTime(mLatestTimestamp);
                        surface.swapBuffers();
                    }
                }
            }
            synchronized (mFrameDrawnLock) {
                frameAvailable = (frameAvailable > 0)? frameAvailable - 1: 0;
                mFrameDrawnLock.notifyAll();
            }
        }

        public void drawFrameImmediate(){
            if (mEglCore == null) {
                Log.d(TAG, "Skipping drawFrame after shutdown");
                return;
            }

            mMasterSurface.makeCurrent();
            mInputTexture.updateTexImage();
            mInputTexture.getTransformMatrix(mTmpMatrix);
            mLatestTimestamp = mInputTexture.getTimestamp();

            synchronized (mLock) {
                int counter = 0;
                for (EglSurfaceBase surface : mEglSurfaces) {
                    surface.makeCurrent();
                    int width = surface.getWidth();
                    int height = surface.getHeight();
                    GLES20.glViewport(0, 0, width, height);
                    mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
                    counter += 1;
                    surface.setPresentationTime(mLatestTimestamp);
                    surface.swapBuffers();
                }
            }

            synchronized (mFrameDrawnLock) {
                frameAvailable = (frameAvailable > 0)? frameAvailable - 1: 0;
                mFrameDrawnLock.notifyAll();
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
                //codec.releaseOutputBuffer(id, false); // Frames are dropped in the decoder input, test with this
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
        public void quit() {
            mDone = true;
            synchronized (mInputFrameLock) {
                mInputFrameLock.notifyAll();
            }
        }
    }
}
