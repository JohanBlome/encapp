package com.facebook.encapp.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;

import java.util.Enumeration;
import java.util.Vector;

public class VsyncHandler extends Thread implements Choreographer.FrameCallback {
    private static String TAG = "encapp.vsynchandler";
    Vector<VsyncListener> mListeners = new Vector<>();
    private Handler mHandler;

    public VsyncHandler() {
    }

    @Override
    public void run() {
        Looper.prepare();
        mHandler = new Handler(msg -> {
            Log.d(TAG, "Message");
            return false;
        });
        Choreographer.getInstance().postFrameCallback(this);
        Looper.loop();
        Log.d(TAG, "Exit");
    }


    public void addListener(VsyncListener listener) {
        mListeners.add(listener);
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
        for (Object listener: mListeners.toArray()) {
            ((VsyncListener)(listener)).vsync(frameTimeNanos);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
}
