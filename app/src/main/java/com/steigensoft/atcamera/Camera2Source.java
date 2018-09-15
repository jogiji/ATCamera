package com.steigensoft.atcamera;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class Camera2Source {
    private static final String TAG = Camera2Source.class.getSimpleName();
    private ImageReader.OnImageAvailableListener mImageAvailableListener;
    private Handler mBackgroundHandler;
    private AutoFitTextureView mTextureView;
    private String mCameraId;
    private ImageReader mImageReader;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CameraCaptureSession mCaptureSessionForImage;
    private Size mPreviewSize;
    private boolean mFlashSupported = false;
    private int[] mSupportedAFModes;
    private int[] mSupportedAEModes;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;
    private STATE mState = STATE.STATE_PREVIEW;
    private int mSensorOrientation = 0;
    private static int MAX_IMAGES = 2;
    // Max preview width and height is guaranteed by Camera2 API
    private static int MAX_PREVIEW_WIDTH = 1920;
    private static int MAX_PREVIEW_HEIGHT = 1080;

    public Camera2Source(@NonNull ImageReader.OnImageAvailableListener mOnImageAvailableListener, @NonNull Handler mCameraHandler) {
        this.mImageAvailableListener = mOnImageAvailableListener;
        this.mBackgroundHandler = mCameraHandler;
    }

    enum STATE {
        STATE_PREVIEW,
        STATE_PICTURE_TAKEN,
    }


    public Camera2Source(ImageReader.OnImageAvailableListener mImageAvailableListener,
                         Handler mBackgroundHandler,
                         AutoFitTextureView mTextureView) {

        this.mImageAvailableListener = mImageAvailableListener;
        this.mBackgroundHandler = mBackgroundHandler;
        this.mTextureView = mTextureView;
    }

    class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight();
        }
    }

    private CompareSizeByArea compareSizeByArea = new CompareSizeByArea();


    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        private void progress(CaptureResult result, CameraCaptureSession session) {
            switch (mState) {
                case STATE_PREVIEW: //do nothing
                    break;
                case STATE_PICTURE_TAKEN:
                    if (mCaptureSessionForImage == session) {
                        Log.d(TAG, "Close take picture session: " + session.toString());
                        session.close();
                    }
                    mCaptureSession = null;
                    // Reset to preview state
                    mState = STATE.STATE_PREVIEW;
                    createPreviewSession();
                    break;
                default:
                    break;


            }
        }


        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            super.onCaptureProgressed(session, request, partialResult);
            progress(partialResult, session);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            progress(result, session);
        }
    };


    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera opened");
            mCameraDevice = camera;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera disconnected");
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.d(TAG, "Camera error: $error");
            camera.close();
            mCameraDevice = null;
        }

    };


    /**
     * Should be called before call openCamera(context: Context).
     */
    public void setUpCameraOutputs(AppCompatActivity activity, int width, int height) {
        Log.d(TAG, "Begin setUpCameraOutputs");
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        String[] camIds = new String[0];
        try {
            camIds = manager.getCameraIdList();
        } catch (CameraAccessException e) {
            Log.d(TAG, "Camera access exception getting IDs", e);
        }
        if (camIds.length == 0) {
            Log.d(TAG, "No cameras found");
            return;
        } else {
            try {
                String id = camIds[0];

                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    Log.d(TAG, "Stream configuration map is null");
                    return;
                }

                Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
                for (Size size : outputSizes) {
                    Log.d(TAG, "Camera support output size: " + size.toString());

                }

                Size largest = Collections.max(Arrays.asList(outputSizes), compareSizeByArea);
                Log.d(TAG, "Camera largest size: $largest");

                // Initialize image processor
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, MAX_IMAGES);
                if (mImageReader != null) {
                    mImageReader.setOnImageAvailableListener(mImageAvailableListener, mBackgroundHandler);
                }

                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                Log.d(TAG, "Display rotation: " + displayRotation);
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Log.d(TAG, "Sensor rotation: " + mSensorOrientation);

                boolean swappedDimensions = false;

                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.d(TAG, "Display rotation is invalid");
                        break;

                }


                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                Log.d(TAG, "Display size: " + displaySize.toString());
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                Size[] choices = map.getOutputSizes(SurfaceTexture.class);
                Log.d(TAG, "Choice size of SurfaceTexture for output size: ");
                for (Size choice : choices) {
                    Log.d(TAG, "Choice for output size: " + choice.toString());
                }
                mPreviewSize = chooseOptimalSize(choices, rotatedPreviewWidth, rotatedPreviewHeight,
                        maxPreviewWidth, maxPreviewHeight, largest);
                Log.d(TAG, "Camera preview size: " + mPreviewSize.toString());

                int orientation = activity.getResources().getConfiguration().orientation;
                Log.d(TAG, "Context orientation: " + orientation);
                if (mTextureView != null && mPreviewSize != null) {
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                    } else {
                        mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                    }
                }

                mFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                Log.d(TAG, "Camera flash supported: " + mFlashSupported);

                mSupportedAFModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                for (int mode : mSupportedAFModes) {
                    Log.d(TAG, "Supported camera AF MODE: " + mode);
                }

                mSupportedAEModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
                for (int mode : mSupportedAEModes) {
                    Log.d(TAG, "Supported camera AE MODE: " + mode);
                }


                mCameraId = id;
            } catch (CameraAccessException e) {
                Log.e(TAG, Log.getStackTraceString(e));
            }

            Log.d(TAG, "End setUpCameraOutputs");
        }
    }

    /**
     * Should be called after setUpCameraOutputs(...) if use TextureView to show camera preview.
     */
    public void configureTransform(AppCompatActivity activity, int viewWidth, int viewHeight) {
        Log.d(TAG, "Begin configureTransform");
        if (activity == null || mPreviewSize == null || mTextureView == null) {
            Log.d(TAG, "End configureTransform");
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0F, 0F, (float) viewWidth, (float) viewHeight);
        RectF buffRect = new RectF(0F, 0F, (float) mPreviewSize.getWidth(), (float) mPreviewSize.getHeight());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            buffRect.offset(centerX - buffRect.centerX(), centerY - buffRect.centerY());
            matrix.setRectToRect(viewRect, buffRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / (float) mPreviewSize.getHeight(), (float) viewWidth / (float) mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (float) (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180F, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
        Log.d(TAG, "End configureTransform");
    }


    public void openCamera(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        // Open the camera resource
        try {
            Log.d(TAG, "Try open camera...");
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "Open camera error " + Log.getStackTraceString(e));
        }
    }

    public void shutDown() {
        mImageReader.close();
        mCaptureSession.close();
        mCameraDevice.close();
    }

    public void tackPicture() {
        if (mCameraDevice == null || mImageReader == null) {
            Log.d(TAG, "Cannot capture image. Camera not initialized");
            return;
        }

        try {
            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            mCameraDevice.createCaptureSession(Collections.singletonList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onClosed(@NonNull CameraCaptureSession session) {
                            super.onClosed(session);
                            Log.d(TAG, "Take picture session closed, session: " + session.toString());
                            mCaptureSessionForImage = null;
                        }

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (mCameraDevice == null) {
                                return;
                            }
                            mCaptureSession = session;
                            mCaptureSessionForImage = session;
                            Log.d(TAG, "Take picture session initialized, session: " + session.toString());
                            triggerImageCapture();

                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "Failed to configure take picture session:  " + session.toString());
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.d(TAG, "Access exception while preparing picture " + e.getMessage());
        }
    }


    private void triggerImageCapture() {
        try {
            CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());
            setAutoFlash(captureBuilder);
            if (Arrays.asList(mSupportedAEModes).contains(CaptureRequest.CONTROL_AE_MODE)) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            mState = STATE.STATE_PICTURE_TAKEN;
            Log.d(TAG, "Use session to capture picture, session: " + mCaptureSession.toString());
            mCaptureSession.capture(captureBuilder.build(), mCaptureCallback, null);
        } catch (CameraAccessException ex) {
            Log.d(TAG, "Camera capture exception" + ex.getMessage());
        }
    }


    private void createPreviewSession() {
        if (mTextureView == null || mPreviewSize == null || mCameraDevice == null) {
            return;
        }
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if (texture != null)
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        final Surface surface = new Surface(texture);

        //  V4L2CameraHAL: setupStreams:384: V4L2 only supports 1 stream configuration at a time
        try {
            mCameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onClosed(@NonNull CameraCaptureSession session) {
                            super.onClosed(session);
                            Log.d(TAG, "Camera preview session closed, session: " + session.toString());
                        }

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                if (mCameraDevice == null) {
                                    return;
                                }
                                mCaptureSession = session;
                                Log.d(TAG, "Camera preview session initialized, session: " + session.toString());


                                mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                                mPreviewRequestBuilder.addTarget(surface);
                                setAutoFlash(mPreviewRequestBuilder);
                                if (Arrays.asList(mSupportedAFModes).contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                }
                                mPreviewRequest = mPreviewRequestBuilder.build();

                                mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);

                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                        }
                    }
                    , null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }



    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }



    private Size chooseOptimalSize(Size[] choices, int textureViewWidth,int textureViewHeight,
                          int maxWidth, int maxHeight, Size aspectRatio){
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBitEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for(Size it : choices){
            if (it.getWidth() <= maxWidth && it.getHeight() <= maxHeight
                    && it.getHeight() == it.getWidth() * h / w) {
                if (it.getWidth() >= textureViewWidth && it.getHeight() >= textureViewHeight) {
                    bigEnough.add(it);
                } else {
                    notBitEnough.add(it);
                }
            }
        }

        if(!bigEnough.isEmpty()){
            return Collections.min(bigEnough, compareSizeByArea);
        }else if(!notBitEnough.isEmpty()){
            return Collections.max(notBitEnough, compareSizeByArea);
        }else{
            Log.d(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }
}
