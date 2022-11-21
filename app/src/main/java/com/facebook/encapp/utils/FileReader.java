package com.facebook.encapp.utils;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

import com.facebook.encapp.proto.Input.PixFmt;

public class FileReader {
    private static final String TAG = "encapp.filereader";
    File mFile;
    BufferedInputStream mBis;
    PixFmt mPixFmt;

    public FileReader() {
    }

    public boolean openFile(String name, PixFmt pixFmt) {
        try {
            Log.i(TAG, "FileReader.openFile: name: " + name + " pix_fmt: " + pixFmt);
            mPixFmt = pixFmt;
            mFile = new File(name);
            mBis = new BufferedInputStream(new FileInputStream(mFile));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Failed to open file: " + name + ", " + e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean isClosed() {
        synchronized (this) {
            return (mBis == null);
        }
    }

    public void closeFile() {
        try {
            synchronized (this) {
                Log.i(TAG, "Close file");
                if (mBis != null) {
                    mBis.close();
                    mBis = null;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int fillBuffer(ByteBuffer byteBuffer, int size){
        synchronized (this) {
            if (isClosed()) {
                return 0;
            }
        }
        if (byteBuffer.hasArray()) {
            byte[] bytes = byteBuffer.array();
            try {
                int read = mBis.read(bytes, 0, bytes.length);
                return read;
            } catch (IOException e) {
                e.printStackTrace();
                return 0;
            }
        } else {
            // check there is enough capacity in the byteBuffer
            if (byteBuffer.capacity() < size) {
                Log.e(TAG, "error: not enough space in ByteBuffer (capacity: " + byteBuffer.capacity() + ") to copy size: " + size + " bytes");
            }

            byte[] bytes = new byte[size];
            try {
                int read = mBis.read(bytes, 0, bytes.length);
                byteBuffer.put(bytes);
                return read;
            } catch (BufferOverflowException e) {
                Log.e(TAG, "error BufferOverflowException: " + e.getMessage());
                e.printStackTrace();
                return 0;
            } catch (ReadOnlyBufferException e) {
                Log.e(TAG, "error ReadOnlyBufferException: " + e.getMessage());
                e.printStackTrace();
                return 0;
            } catch (IOException e) {
                Log.e(TAG, "error: " + e.getMessage());
                e.printStackTrace();
                return 0;
            }
        }
    }
}
