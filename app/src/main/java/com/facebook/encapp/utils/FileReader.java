package com.facebook.encapp.utils;

import android.graphics.ImageFormat;
import android.media.Image;
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
import com.facebook.encapp.utils.Assert;

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

    // Copy a full frame from the BufferedInputStream into an Image.
    // Inspired in android-13/cts/tests/mediapc/src/android/mediapc/cts/CodecTestBase.java
    public int fillImage(Image image) {
        synchronized (this) {
            if (isClosed()) {
                return 0;
            }
        }
        // make sure we support the pixel (source) and Image (destination) format
        Assert.assertTrue(mPixFmt.getNumber() == PixFmt.yuv420p_VALUE ||
                          mPixFmt.getNumber() == PixFmt.nv12_VALUE ||
                          mPixFmt.getNumber() == PixFmt.nv21_VALUE,
                          "Invalid PixFmt: " + mPixFmt);
        Assert.assertTrue(image.getFormat() == ImageFormat.YUV_420_888, "Invalid ImageFormat: " + image.getFormat());
        // YUV420
        int bytesPerComponent = (ImageFormat.getBitsPerPixel(image.getFormat()) * 2) / (8 * 3);
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        // read the full frame from input (BufferedInputStream) into a byte array
        int lumaLength = imageWidth * imageHeight * bytesPerComponent;
        int chromaLength = imageWidth * imageHeight * bytesPerComponent / 4;
        int frameLength = lumaLength + 2 * chromaLength;
        final byte[] bytes = new byte[frameLength];
        try {
            int actually_read = mBis.read(bytes, 0, frameLength);
            if (actually_read < frameLength) {
                // file is finished
                return -1;
            }
        } catch (IOException e) {
            Log.e(TAG, "error IOException: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
        // Y, U, V pixel strides and input plane offsets
        int[] inputPixelStride = {bytesPerComponent, bytesPerComponent, bytesPerComponent};
        if (mPixFmt.getNumber() == PixFmt.nv12_VALUE || mPixFmt.getNumber() == PixFmt.nv21_VALUE) {
            inputPixelStride[1] = 2 * bytesPerComponent;
            inputPixelStride[2] = 2 * bytesPerComponent;
        }

        int[] inputPlaneOffset = {0, lumaLength, lumaLength};
        if (mPixFmt.getNumber() == PixFmt.yuv420p_VALUE) {
            inputPlaneOffset[2] = lumaLength + chromaLength;
        }

        for (int planeid = 0; planeid < planes.length; ++planeid) {
            ByteBuffer buf = planes[planeid].getBuffer();
            int width = imageWidth;
            int height = imageHeight;
            int outputRowStride = planes[planeid].getRowStride();
            int outputPixelStride = planes[planeid].getPixelStride();
            // chromas are subsampled (4:2:0)
            if (planeid != 0) {
                width = imageWidth / 2;
                height = imageHeight / 2;
            }
            try {
                if ((inputPixelStride[planeid] == bytesPerComponent) && (outputPixelStride == bytesPerComponent) && (width == outputRowStride)) {
                    // 1. optimized copy: full plane
                    //Log.i(TAG, "FileReader::fillImage(): plane: " + planeid + " full plane copy: resolution: " + width + "x" + height + " outputPixelStride: " + outputPixelStride + " outputRowStride: " + outputRowStride);
                    int inputOffset = inputPlaneOffset[planeid];
                    buf.put(bytes, inputOffset, width * height * bytesPerComponent);
                } else if ((inputPixelStride[planeid] == bytesPerComponent) && (outputPixelStride == bytesPerComponent)) {
                    // 2. optimized copy: full row
                    //Log.i(TAG, "FileReader::fillImage(): plane: " + planeid + " full row copy: resolution: " + width + "x" + height + " outputPixelStride: " + outputPixelStride + " outputRowStride: " + outputRowStride);
                    for (int row = 0; row < height; row += 1) {
                        int inputOffset = inputPlaneOffset[planeid] + row * width * bytesPerComponent;
                        int outputOffset = row * outputRowStride * bytesPerComponent;
                        buf.position(outputOffset);
                        buf.put(bytes, inputOffset, width * bytesPerComponent);
                    }
                } else {
                    // 3. non-optimized copy: pixel-by-pixel
                    //Log.i(TAG, "FileReader::fillImage(): plane: " + planeid + " pixel-by-pixel copy: resolution: " + width + "x" + height + " outputPixelStride: " + outputPixelStride + " outputRowStride: " + outputRowStride);
                    for (int row = 0; row < height; row += 1) {
                        for (int x = 0; x < width; x += 1) {
                            int inputOffset = inputPlaneOffset[planeid] + (row * width + x) * inputPixelStride[planeid] * bytesPerComponent;
                            if ((planeid == 2 && mPixFmt.getNumber() == PixFmt.nv12_VALUE) ||
                                (planeid == 1 && mPixFmt.getNumber() == PixFmt.nv21_VALUE)) {
                                inputOffset += bytesPerComponent;
                            }
                            int outputOffset = (row * outputRowStride + x * outputPixelStride) * bytesPerComponent;
                            buf.position(outputOffset);
                            buf.put(bytes, inputOffset, bytesPerComponent);
                        }
                    }
                }
            } catch (BufferOverflowException e) {
                Log.e(TAG, "error BufferOverflowException: " + e.getMessage());
                e.printStackTrace();
                return 0;
            } catch (ReadOnlyBufferException e) {
                Log.e(TAG, "error ReadOnlyBufferException: " + e.getMessage());
                e.printStackTrace();
                return 0;
            }
        }
        // Log.i(TAG, "FileReader::fillImage(): frameLength: " + frameLength);
        return frameLength;
    }
}
