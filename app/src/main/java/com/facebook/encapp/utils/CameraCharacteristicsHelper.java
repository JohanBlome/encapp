package com.facebook.encapp.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MandatoryStreamCombination;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RecommendedStreamConfigurationMap;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import java.lang.Byte;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Executor;

import static android.content.Context.CAMERA_SERVICE;


public class CameraCharacteristicsHelper {
    final static int mIndentWidth = 2;

    public static String getIndentation(int indent) {
        String tab = "";
        if (indent > 0){
            tab = String.format("%" + (indent * mIndentWidth) + "s", ' ');
        }
        return tab;
    }

    static String hardwareLevelToString(int hardwareLevel) {
        switch (hardwareLevel) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "INFO_SUPPORTED_HARDWARE_LEVEL_FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "INFO_SUPPORTED_HARDWARE_LEVEL_3";
            default:
                return "unknown";
        }
    }

    static String faceDetectModeToString(int faceDetectMode) {
        switch (faceDetectMode) {
            case CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF:
                return "STATISTICS_FACE_DETECT_MODE_OFF";
            case CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE:
                return "STATISTICS_FACE_DETECT_MODE_SIMPLE";
            case CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL:
                return "STATISTICS_FACE_DETECT_MODE_FULL";
            default:
                return "unknown";
        }
    }

    static String imageFormatToString(int format) {
        switch (format) {
            case ImageFormat.DEPTH16:
                return "DEPTH16";
            case ImageFormat.DEPTH_JPEG:
                return "DEPTH_JPEG";
            case ImageFormat.DEPTH_POINT_CLOUD:
                return "DEPTH_POINT_CLOUD";
            case ImageFormat.FLEX_RGBA_8888:
                return "FLEX_RGBA_8888";
            case ImageFormat.FLEX_RGB_888:
                return "FLEX_RGB_888";
            case ImageFormat.HEIC:
                return "HEIC";
            case ImageFormat.JPEG:
                return "JPEG";
            case ImageFormat.NV16:
                return "NV16";
            case ImageFormat.NV21:
                return "NV21";
            case ImageFormat.PRIVATE:
                return "PRIVATE";
            case ImageFormat.RAW10:
                return "RAW10";
            case ImageFormat.RAW12:
                return "RAW12";
            case ImageFormat.RAW_PRIVATE:
                return "RAW_PRIVATE";
            case ImageFormat.RAW_SENSOR:
                return "RAW_SENSOR";
            case ImageFormat.RGB_565:
                return "RGB_565";
            case ImageFormat.UNKNOWN:
                return "UNKNOWN";
            case ImageFormat.Y8:
                return "Y8";
            // TODO(chema): requires Build.VERSION.SDK_INT >= 31
            //case ImageFormat.YCBCR_P010:
            //    return "YCBCR_P010";
            case ImageFormat.YUV_420_888:
                return "YUV_420_888";
            case ImageFormat.YUV_422_888:
                return "YUV_422_888";
            case ImageFormat.YUV_444_888:
                return "YUV_444_888";
            case ImageFormat.YUY2:
                return "YUY2";
            case ImageFormat.YV12:
                return "YV12";
            default:
                return "unknown";
        }
    }

    public static String toText(CameraCharacteristics camera_characteristics, String cameraId, int indent) {
        String tab = getIndentation(indent);

        StringBuilder str = new StringBuilder();
        str.append(tab + "CameraCharacteristics {\n");
        indent += 1;
        tab = getIndentation(indent);
        str.append(tab + "cameraId: " + cameraId + "\n");

        // list all the keys
        List<CameraCharacteristics.Key<?>> keys = camera_characteristics.getKeys();
        Iterator<CameraCharacteristics.Key<?>> iter = keys.iterator();
        while (iter.hasNext()) {
            CameraCharacteristics.Key<?> key = iter.next();
            Object obj = camera_characteristics.get(key);
            if (obj instanceof Integer) {
                Integer value = (Integer)obj;
                str.append(tab + key.getName() + ": " + value + "\n");
                if (key.getName() == "android.info.supportedHardwareLevel") {
                    str.append(tab + key.getName() + ".string: " + hardwareLevelToString(value) + "\n");
                } else if (key.getName() == "android.statistics.info.availableFaceDetectModes") {
                    str.append(tab + key.getName() + ".string: " + faceDetectModeToString(value) + "\n");
                }
            } else if (obj instanceof Long) {
                Long value = (Long)obj;
                str.append(tab + key.getName() + ": " + value + "\n");
            } else if (obj instanceof Boolean) {
                Boolean value = (Boolean)obj;
                str.append(tab + key.getName() + ": " + value + "\n");
            } else if (obj instanceof Float) {
                Float value = (Float)obj;
                str.append(tab + key.getName() + ": " + value + "\n");
            } else if (obj instanceof Rational) {
                Rational value = (Rational)obj;
                str.append(tab + key.getName() + ": " + value.getNumerator() + "/" + value.getDenominator() + "\n");
            } else if (obj instanceof Size) {
                Size value = (Size)obj;
                str.append(tab + key.getName() + ": " + value.toString() + "\n");
            } else if (obj instanceof SizeF) {
                SizeF value = (SizeF)obj;
                str.append(tab + key.getName() + ": " + value.toString() + "\n");
            } else if (obj instanceof Byte) {
                Byte value = (Byte)obj;
                str.append(tab + key.getName() + ": " + value.toString() + "\n");
            } else if (obj instanceof Rect) {
                Rect value = (Rect)obj;
                str.append(tab + key.getName() + ": " + value.toString() + "\n");
            } else if (obj instanceof Range) {
                Range value = (Range)obj;
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                str.append(tab + "range_lower: " + value.getLower() + "\n");
                str.append(tab + "range_upper: " + value.getUpper() + "\n");
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof StreamConfigurationMap) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                StreamConfigurationMap value = (StreamConfigurationMap)obj;
                str.append(streamConfigurationMapToString(value, indent) + "\n");
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof int[] ) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                for (int value: (int[])obj) {
                    str.append(tab + "value: " + value + "\n");
                }
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof long[] ) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                for (long value: (long[])obj) {
                    str.append(tab + "value: " + value + "\n");
                }
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof boolean[] ) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                for (boolean value: (boolean[])obj) {
                    str.append(tab + "value: " + value + "\n");
                }
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof float[] ) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                for (float value: (float[])obj) {
                    str.append(tab + "value: " + value + "\n");
                }
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof Size[] ) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                for (Size value: (Size[])obj) {
                    str.append(tab + "value: " + value + "\n");
                }
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof Range[] ) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                for (Range range: (Range[])obj) {
                    str.append(tab + "range {\n");
                    indent += 1;
                    tab = getIndentation(indent);
                    str.append(tab + "range_lower: " + range.getLower() + "\n");
                    str.append(tab + "range_upper: " + range.getUpper() + "\n");
                    indent -= 1;
                    tab = getIndentation(indent);
                    str.append(tab + "}\n");
                }
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");
            } else if (obj instanceof MandatoryStreamCombination[]) {
                str.append(tab + key.getName() + " {\n");
                indent += 1;
                tab = getIndentation(indent);
                if (Build.VERSION.SDK_INT >= 29) {
                    for (MandatoryStreamCombination comb: (MandatoryStreamCombination[])obj) {
                        str.append(tab + "description: " + comb.getDescription() + "\n");
                    }
                }
                indent -= 1;
                tab = getIndentation(indent);
                str.append(tab + "}\n");

            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && obj instanceof RecommendedStreamConfigurationMap[]) {
                RecommendedStreamConfigurationMap recommended = camera_characteristics.getRecommendedStreamConfigurationMap(RecommendedStreamConfigurationMap.USECASE_PREVIEW);
                // TODO(chema): print this

            } else {
                str.append(tab + key.getName() + ": UNKNOWN_TYPE " + obj + "\n");
            }
        }

        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}\n");
        return str.toString();
    }

    // https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
    public static String streamConfigurationMapToString(StreamConfigurationMap streamConfigurationMap, int indent) {
        String tab = getIndentation(indent);

        StringBuilder str = new StringBuilder();
        str.append(tab + "StreamConfigurationMap {\n");
        indent += 1;
        tab = getIndentation(indent);

        // output formats
        int[] outputFormats = streamConfigurationMap.getOutputFormats();
        for (int outputFormat : outputFormats) {
            str.append(tab + "outputFormat {\n");
            indent += 1;
            tab = getIndentation(indent);
            str.append(tab + "pixel_format: " + outputFormat + "\n");
            str.append(tab + "pixel_format_name: " + imageFormatToString(outputFormat) + "\n");
            Size[] previewSizes = streamConfigurationMap.getOutputSizes(outputFormat);
            for (Size previewSize : previewSizes) {
                str.append(tab + "preview_size: " + previewSize + "\n");
            }
            indent -= 1;
            tab = getIndentation(indent);
            str.append(tab + "}\n");
        }

        Size[] previewSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
        str.append(tab + "surface_class {\n");
        for (Size previewSize : previewSizes) {
            indent += 1;
            tab = getIndentation(indent);
            str.append(tab + "preview_size: " + previewSize + "\n");
            indent -= 1;
            tab = getIndentation(indent);
        }
        str.append(tab + "}\n");

        // input formats
        int[] inputFormats = streamConfigurationMap.getInputFormats();
        for (int inputFormat : inputFormats) {
            str.append(tab + "inputFormat {\n");
            indent += 1;
            tab = getIndentation(indent);
            str.append(tab + "pixel_format: " + inputFormat + "\n");
            str.append(tab + "pixel_format_name: " + imageFormatToString(inputFormat) + "\n");
            Size[] inputsizes = streamConfigurationMap.getInputSizes(inputFormat);
            for (Size size : inputsizes) {
                str.append(tab + "size: " + size + "\n");
            }
            indent -= 1;
            tab = getIndentation(indent);
            str.append(tab + "}\n");
        }

        indent -= 1;
        tab = getIndentation(indent);
        str.append(tab + "}");
        return str.toString();
    }

    // Returns true if the device supports the required hardware level, or better.
    boolean isHardwareLevelSupported(CameraCharacteristics cameraCharacteristics, int requiredLevel) {
        final int[] sortedHwLevels = {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
        };
        int deviceLevel = cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (requiredLevel == deviceLevel) {
            return true;
        }

        for (int sortedlevel : sortedHwLevels) {
            if (sortedlevel == requiredLevel) {
                return true;
            } else if (sortedlevel == deviceLevel) {
                return false;
            }
        }
        return false; // Should never reach here
    }

}
