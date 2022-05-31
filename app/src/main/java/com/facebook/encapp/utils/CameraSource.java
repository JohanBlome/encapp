package com.facebook.encapp.utils;

import static android.content.Context.CAMERA_SERVICE;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
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
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Executor;


public class CameraSource {
    private static final String TAG = "encapp.camera";
    Context mContext;
    CameraManager mCameraManager;
    CameraDevice mCameraDevice;
    Handler mHandler;
    Vector<OutputConfiguration> mOutputConfigs;
    CameraCaptureSession mSession;
    int mSensitivityTarget = -1;
    int mFrameDurationTargetUsec = -1;
    int mFrameExposureTimeTargetUsec = -1;
    float mFramerateTarget = -1;
    boolean mHasAwbLock = true;
    boolean mAWBconverged = false;
    boolean mCameraReady = false;
    boolean mManualSettings = false;
    final static int WAIT_TIME_SHORT_MS = 3000;  // 3 sec


    private static CameraSource mCameraSource = null;
    private static int mClients = 0;
    static Object lock = new Object();
    Vector<SurfaceData> mSurfaces = new Vector<>();

    int mHwLevel = -1;

    public static CameraSource getCamera(Context theContext) {
        synchronized (lock) {
            if (mCameraSource == null) {
                mCameraSource = new CameraSource(theContext);
            }
            mClients += 1;
        }
        return mCameraSource;
    }

    private CameraSource(Context context) {
        mContext = context;
    }

    public void closeCamera() {
        synchronized (mCameraSource) {
            mClients -= 1;
            Log.d(TAG, "Clients is: " + mClients);

            try {
                if (mSession != null) {
                    mSession.abortCaptures();
                }
                mSession = null;
                if (mOutputConfigs != null)
                    mOutputConfigs.clear();
                if (mSurfaces != null)
                    mSurfaces.clear();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            if (mClients == 0) {
                mCameraDevice.close();
            }
        }
    }


    private boolean openCamera() {
        mCameraManager = (CameraManager) mContext.getSystemService(CAMERA_SERVICE);
        if (mCameraManager == null) {
            Log.e(TAG, "No camera");
            return false;
        }

        try {
            String[] cams = mCameraManager.getCameraIdList();
            for (String cam : cams) {
                Log.d(TAG, "Camera: " + cam);
                CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cam);
                List<CameraCharacteristics.Key<?>> keys = chars.getKeys();
                Iterator<CameraCharacteristics.Key<?>> iter = keys.iterator();
                while (iter.hasNext()) {
                    CameraCharacteristics.Key<?> key = iter.next();

                    Object obj = chars.get(key);
                    if (obj instanceof Range[]) {
                        Log.d(TAG, "Key: " + key.getName());
                        for (Range range : (Range[]) obj) {
                            Log.d(TAG, range.getLower() + "->" + range.getUpper());
                        }
                    } else if (obj instanceof MandatoryStreamCombination[]) {
                        Log.d(TAG, key.getName());
                        for (MandatoryStreamCombination comb : (MandatoryStreamCombination[]) obj) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Log.d(TAG, "descr: " + comb.getDescription());
                            }
                        }
                    } else {
                        Log.d(TAG, key.getName() + " - " + chars.get(key));

                    }

                }
            }

            HandlerThread handlerThread = new HandlerThread("camera");
            handlerThread.start();
            mHandler = new Handler(handlerThread.getLooper());
            ///Choose first
            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            CameraCharacteristics characs = mCameraManager.getCameraCharacteristics(cams[0]);
            StreamConfigurationMap streamConfigurationMap = characs.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            int[] formats = streamConfigurationMap.getOutputFormats();
            for (int format : formats) {
                Log.d(TAG, "Pixel format: " + format);
                Size[] previewSizes = streamConfigurationMap.getOutputSizes(format);
                for (Size previewSize : previewSizes) {
                    Log.d(TAG, "Preview size for the format: " + previewSize);
                }
            }
            Size[] previewSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
            for (Size previewSize : previewSizes) {
                Log.d(TAG, "Preview size for surface class: " + previewSize);
            }

            int[] inputFormats = streamConfigurationMap.getInputFormats();
            for (int format : inputFormats) {
                Log.d(TAG, "Input format: " + format);
                Size[] inputsizes = streamConfigurationMap.getInputSizes(format);
                for (Size size : inputsizes) {
                    Log.d(TAG, format + " - " + size);
                }
            }


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RecommendedStreamConfigurationMap recomended = characs.getRecommendedStreamConfigurationMap(RecommendedStreamConfigurationMap.USECASE_PREVIEW);
            }

            Range<Integer>[] fpsRanges = characs.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Log.d(TAG, "_____\n");
            if (fpsRanges != null) {
                Log.d(TAG, "fps ranges: " + fpsRanges.length);
                for (Range range : fpsRanges) {
                    Log.d(TAG, range.getLower() + " -> " + range.getUpper());
                }
            } else {
                Log.d(TAG, "no fps ranges");
            }
            Log.d(TAG, "_____\n");
            Range<Integer> sensorRange = characs.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (sensorRange != null)
                Log.d(TAG, "Sensor range: " + sensorRange.getLower() + " -> " + sensorRange.getUpper());
            else
                Log.d(TAG, "No sensor range");

            Log.d(TAG, "_____\n");
            Range<Long> exposureRange = characs.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (exposureRange != null)
                Log.d(TAG, "Exposure range: " + exposureRange.getLower() + " -> " + exposureRange.getUpper());
            else
                Log.d(TAG, "No exposure range");
            Log.d(TAG, "_____\n");
            Long maxDuration = characs.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
            Log.d(TAG, "Max frame duration: " + maxDuration);

            Log.d(TAG, "_____\n");
            int[] facedetectionModes = characs.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            if (facedetectionModes != null) {
                Log.d(TAG, "facedetectionModes: " + facedetectionModes.length);
                for (int mode : facedetectionModes) {
                    switch (mode) {
                        case CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF:
                            Log.d(TAG, "Mode off available");
                            break;
                        case CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE:
                            Log.d(TAG, "Mode simple available");
                            break;
                        case CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL:
                            Log.d(TAG, "Mode full available");
                            break;
                    }
                }

            } else
                Log.d(TAG, "No face detection modes");

            mCameraManager.openCamera(cams[0],
                    new StateHolder(),
                    mHandler);

            int orientation = characs.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Log.d(TAG, "Sensor orientation: " + orientation);

            mHwLevel = characs.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            Log.d(TAG, "Hw level: " + hwLevelToText(mHwLevel));
            Log.d(TAG, "_____\n");
        } catch (CameraAccessException cameraAccessException) {
            cameraAccessException.printStackTrace();
        }
        return true;
    }

    public void registerSurface(Surface output, int width, int height) {
        mSurfaces.add(new SurfaceData(output, width, height));
    }

    public static void start() {
        mCameraSource.openCamera();
    }

    private boolean startCapture() {
        try {
            mOutputConfigs = new Vector<>();


            for (SurfaceData data : mSurfaces) {
                Log.d(TAG, "Add config surface: " + data.mSurface + ", " + data.mHeight);
                OutputConfiguration outconfig = new OutputConfiguration(data.mSurface);
                mOutputConfigs.add(outconfig);
            }
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    mOutputConfigs,
                    new CamExec(),
                    new CamState());

            mCameraDevice.createCaptureSession(config);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private void updateParameters() {
        try {
            mSession.abortCaptures();
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    mOutputConfigs,
                    new CamExec(),
                    new CamState());
            mCameraDevice.createCaptureSession(config);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    class StateHolder extends CameraDevice.StateCallback {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera opened: " + camera.getId());
            mCameraDevice = camera;
            startCapture();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera disconnected: " + camera.getId());
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.d(TAG, "Camera error: " + camera.getId() + ", Error: " + error);
        }
    }


    class CamExec implements Executor {

        @Override
        public void execute(Runnable command) {
            //   Log.d(TAG, "Exec: " + command);
            command.run();
        }
    }


    public Range<Integer> getRange(float target, Range<Integer>[] ranges) {
        Range<Integer> range = null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (target == r.getLower() && target == r.getUpper()) {
                    return r;
                } else if (target >= r.getLower() && target <= r.getUpper()) {
                    range = r;
                }
            }
        }
        return range;
    }

    public CameraCharacteristics getCameraCharacteristics() {
        if (mCameraManager != null && mCameraDevice != null) {
            try {
                return mCameraManager.getCameraCharacteristics(mCameraDevice.getId());
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    Object mRequestLock = new Object();

    class CamState extends CameraCaptureSession.StateCallback {
        public CamState() {
            super();
        }

        public void fillCaptureRequest(@NonNull CaptureRequest.Builder captureRequest, Range<Integer>[] fpsRanges) {
            boolean turnOffAE = false;
            if (mSensitivityTarget > 0) {
                Log.d(TAG, "sensor_sensitivity: " + mSensitivityTarget);
                captureRequest.set(CaptureRequest.SENSOR_SENSITIVITY, mSensitivityTarget);
                turnOffAE = true;
            }
            if (mFrameExposureTimeTargetUsec > 0) {
                long sensorExposureTime = mFrameExposureTimeTargetUsec * 1000;  // ns
                Log.d(TAG, "sensor_exposure_time: " + sensorExposureTime);
                Log.d(TAG, "sensor_exposure_time_ms: " + sensorExposureTime / 1000000.0);
                captureRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Long.valueOf(sensorExposureTime));
                turnOffAE = true;
            }
            if (mFrameDurationTargetUsec > 0) {
                long sensorFrameDuration = mFrameDurationTargetUsec * 1000;
                Log.d(TAG, "sensor_frame_duration: " + sensorFrameDuration);
                Log.d(TAG, "sensor_frame_duration_ms: " + sensorFrameDuration / 1000000.0);
                captureRequest.set(CaptureRequest.SENSOR_FRAME_DURATION, Long.valueOf(sensorFrameDuration));
                turnOffAE = true;
            }
            if (mFramerateTarget > 0) {
                Range fps = getRange(mFramerateTarget, fpsRanges);
                Log.d(TAG, "control_ae_target_fps_range: " + fps);
                captureRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            }
            if (turnOffAE) {
                captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                Log.d(TAG, "control_ae_mode: off");
            }
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "CameraCapture configured: " + session.toString());

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean aeTriggered = false;
                    boolean awbLockTriggered = false;
                    try {
                        CaptureRequest.Builder captureRequest
                                = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        CapResult capRes = new CapResult();
                        CameraCharacteristics characs = mCameraManager.getCameraCharacteristics(mCameraDevice.getId());
                        Range<Integer>[] fpsRanges = characs.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        Range<Integer> sensorRange = characs.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

                        mHasAwbLock = characs.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);

                        while (!mCameraReady && mHasAwbLock) {

                            for (SurfaceData data : mSurfaces) {
                                captureRequest.addTarget(data.mSurface);
                            }
                            if (mAWBconverged && !awbLockTriggered) {
                                Log.d(TAG, "Lock awb");
                                awbLockTriggered = true;
                                captureRequest.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                            } else if (!aeTriggered) {
                                aeTriggered = true;
                                captureRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                            }
                            int capture = session.capture(captureRequest.build(), capRes, mHandler);
                            synchronized (mRequestLock) {
                                try {
                                    mRequestLock.wait(WAIT_TIME_SHORT_MS);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        for (SurfaceData data : mSurfaces) {
                            Log.d(TAG, "Add target surface: " + data.mSurface + ", " + data.mHeight);
                            captureRequest.addTarget(data.mSurface);
                        }

                        if (mManualSettings) {
                            fillCaptureRequest(captureRequest, fpsRanges);
                        }
                        captureRequest.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
                        Log.d(TAG, "Capture continuously!");
                        int capture = session.setRepeatingRequest(captureRequest.build(), capRes, mHandler);

                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    mSession = session;
                }
            });
            t.start();

        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "CameraCapture config failed: " + session.toString());

        }

        @Override
        public void onReady(@NonNull CameraCaptureSession session) {
            super.onReady(session);
            Log.d(TAG, "onReady!!!");
        }


        @Override
        public void onActive(@NonNull CameraCaptureSession session) {
            super.onActive(session);
            Log.d(TAG, "onActive" + session);
        }

        @Override
        public void onCaptureQueueEmpty(@NonNull CameraCaptureSession session) {
            super.onCaptureQueueEmpty(session);
            Log.d(TAG, "onCaptureQueueEmpty");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            super.onClosed(session);
            Log.d(TAG, "onClosed");
        }


        @Override
        public void onSurfacePrepared(@NonNull CameraCaptureSession session, @NonNull Surface surface) {
            super.onSurfacePrepared(session, surface);
            Log.d(TAG, "onSurfacePrepared");
        }

    }

    class CapResult extends CameraCaptureSession.CaptureCallback {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest captureRequest, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, captureRequest, timestamp, frameNumber);
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest captureRequest, @NonNull CaptureResult partialResult) {
            super.onCaptureProgressed(session, captureRequest, partialResult);
            Log.d(TAG, "onCaptureProgressed");
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest captureRequest, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, captureRequest, result);
            if (!mCameraReady && mHasAwbLock) {
                switch (result.get(CaptureResult.CONTROL_AWB_STATE)) {
                    case CaptureResult.CONTROL_AWB_STATE_CONVERGED:
                        Log.d(TAG, "awb converged.");
                        mAWBconverged = true;
                        break;
                    case CaptureResult.CONTROL_AWB_STATE_LOCKED:
                        Log.d(TAG, "awb locked.");
                        mCameraReady = true;
                        break;

                }
                synchronized (mRequestLock) {
                    mRequestLock.notifyAll();
                }
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest captureRequest, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, captureRequest, failure);
            Log.d(TAG, "onCaptureFailed, reason:" + failure.getReason() +", "+failure.toString());
        }
    }

    String hwLevelToText(int deviceLevel) {

        switch (deviceLevel) {
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
                return "Whoops, what is this?";
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    boolean isHardwareLevelSupported(CameraCharacteristics c, int requiredLevel) {
        final int[] sortedHwLevels = {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
        };
        int deviceLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
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

    class SurfaceData {
        Surface mSurface;
        int mWidth, mHeight;

        public SurfaceData(Surface surface, int width, int height) {
            mSurface = surface;
            mWidth = width;
            mHeight = height;
        }
    }

    public static int getClientCount() {
        return mClients;
    }

    public void setFps(float fps) {
        mManualSettings = true;
        mFramerateTarget = fps;
    }

    public void setFrameDurationUsec(int usec) {
        mManualSettings = true;
        mFrameDurationTargetUsec = usec;
    }

    public void setFrameExposureTimeTargetUsec(int usec) {
        mManualSettings = true;
        mFrameExposureTimeTargetUsec = usec;
    }

    public void setSensitivity(int iso) {
        mManualSettings = true;
        mSensitivityTarget = iso;
    }
}
