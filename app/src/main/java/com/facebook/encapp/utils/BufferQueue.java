package com.facebook.encapp.utils;

import java.util.concurrent.ConcurrentLinkedQueue;
import android.util.Log;
public class BufferQueue {
    private static String TAG = "encapp.bufferq";
    long mNextPts = 0;
    BufferObject mNextObject = null;
    ConcurrentLinkedQueue<BufferObject> mBuffers = new ConcurrentLinkedQueue<>();

    int offerCount = 0;
    int pollCount = 0;
    public boolean offer(BufferObject buffer) {
        mBuffers.add(buffer);
        offerCount += 1;
        Log.d(TAG, "offer: " + buffer.getTimestampUs() + ", offercont = " + offerCount + ", poll: " + pollCount);
        return true;
    }

    public long nextPts() {
        return mNextPts;
    }

    public long size() {
        return mBuffers.size();
    }

    private BufferObject next() {
        long time = Long.MAX_VALUE;
        BufferObject next = null;
        for (BufferObject buffer : mBuffers) {
            if (buffer.getTimestampUs() < time) {
                next = buffer;
                time = buffer.getTimestampUs();
            }
        }

        return next;
    }


    public BufferObject poll() {
        BufferObject next = next();
        Log.d(TAG, "poll() size = " + size() + ", Next pts = " + next.getTimestampUs() + ", offer: " + offerCount + ", pollCount = " + pollCount + ", next in line: " + mBuffers.peek().getTimestampUs());
        pollCount += 1;
        mBuffers.remove(next);
        return next;
        //return mBuffers.poll();
    }


}
