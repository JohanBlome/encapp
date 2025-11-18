package com.facebook.encapp.utils.codec;

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Base class for codec writers with common I/O utilities.
 */
public abstract class BaseCodecWriter implements CodecWriter {
    private static final String TAG = "encapp.codec";
    
    protected final CodecType codecType;
    
    protected BaseCodecWriter(CodecType codecType) {
        this.codecType = codecType;
    }
    
    @Override
    public CodecType getCodecType() {
        return codecType;
    }
    
    @Override
    public String getItemType() {
        return codecType.getItemType();
    }
    
    protected void writeInt8(FileOutputStream file, int value) throws IOException {
        file.write(value & 0xFF);
    }

    protected void writeInt16(FileOutputStream file, int value) throws IOException {
        file.write((value >> 8) & 0xFF);
        file.write(value & 0xFF);
    }

    protected void writeInt32(FileOutputStream file, int value) throws IOException {
        file.write((value >> 24) & 0xFF);
        file.write((value >> 16) & 0xFF);
        file.write((value >> 8) & 0xFF);
        file.write(value & 0xFF);
    }

    protected void writeString(FileOutputStream file, String s) throws IOException {
        file.write(s.getBytes("US-ASCII"));
    }

    protected void writeBytes(FileOutputStream file, byte[] data) throws IOException {
        file.write(data);
    }

    protected long getFilePosition(FileOutputStream file) throws IOException {
        return file.getChannel().position();
    }

    protected void seek(FileOutputStream file, long position) throws IOException {
        file.getChannel().position(position);
    }

    protected long startBox(FileOutputStream file, String type) throws IOException {
        long position = getFilePosition(file);
        writeInt32(file, 0);
        writeString(file, type);
        return position;
    }

    protected void endBox(FileOutputStream file, long position) throws IOException {
        long currentPos = getFilePosition(file);
        int size = (int) (currentPos - position);
        seek(file, position);
        writeInt32(file, size);
        seek(file, currentPos);
    }
    
    protected void log(String message) {
        Log.d(TAG, "[" + codecType.name() + "] " + message);
    }
    
    protected void logError(String message) {
        Log.e(TAG, "[" + codecType.name() + "] " + message);
    }
}
