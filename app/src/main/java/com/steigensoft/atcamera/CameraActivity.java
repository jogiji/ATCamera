package com.steigensoft.atcamera;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;

import com.steigensoft.atcamera.mlkit.DefaultExecutorSupplier;
import com.steigensoft.atcamera.mlkit.GraphicOverlay;
import com.steigensoft.atcamera.mlkit.VisionImageProcessor;
import com.steigensoft.atcamera.mlkit.facedetection.FaceDetectionProcessor;

import java.lang.ref.WeakReference;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Skeleton of an Android Things activity.
 * <p>
 * Android Things peripheral APIs are accessible through the class
 * PeripheralManagerService. For example, the snippet below will open a GPIO pin and
 * set it to HIGH:
 *
 * <pre>{@code
 * PeripheralManagerService service = new PeripheralManagerService();
 * mLedGpio = service.openGpio("BCM6");
 * mLedGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
 * mLedGpio.setValue(true);
 * }</pre>
 * <p>
 * For more complex peripherals, look for an existing user-space driver, or implement one if none
 * is available.
 *
 * @see <a href="https://github.com/androidthings/contrib-drivers#readme">https://github.com/androidthings/contrib-drivers#readme</a>
 */
public class CameraActivity extends AppCompatActivity {
    private Handler  mCameraHandler;
    private HandlerThread mCameraThread;
    private static final String TAG = CameraActivity.class.getSimpleName();
    private Camera2Source  mCamera;
    private Object processorLock = new Object();
    private Thread processingThread = null;
    private FrameProcessingRunnable processingRunnable = null;
    private AutoFitTextureView  mTextureView;
    private GraphicOverlay fireFaceOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        fireFaceOverlay = (GraphicOverlay) findViewById(R.id.fireFaceOverlay);
        mTextureView = (AutoFitTextureView) findViewById(R.id.texture);
    }




    private class FrameProcessingRunnable implements Runnable {
        private final GraphicOverlay mGraphicOverlay;
        private VisionImageProcessor mProcessor;
        private long mStartTimeMillis = SystemClock.elapsedRealtime();

        // This lock guards all of the member variables below.
        private final Object mLock = new Object();
        private boolean mActive = true;

        // These pending variables hold the state associated with the new frame awaiting processing.
        private long mPendingTimeMillis;
        private int mPendingFrameId = 0;
        private Bitmap mPendingBitmap;

        FrameProcessingRunnable(VisionImageProcessor processor, GraphicOverlay overlay) {
            this.mProcessor = processor;
            this.mGraphicOverlay = overlay;
        }

        /**
         * Releases the underlying receiver.  This is only safe to do after the associated thread
         * has completed, which is managed in camera source's release method above.
         */
        @SuppressLint("Assert")
        void release() {
            assert (processingThread.getState() == Thread.State.TERMINATED);
            mProcessor = null;
        }

        /**
         * Marks the runnable as active/not active.  Signals any blocked threads to continue.
         */
        void setActive(boolean active) {
            synchronized (mLock) {
                mActive = active;
                mLock.notifyAll();
            }
        }

        /**
         * Sets the frame data received from the camera.
         */
        void setNextFrame(Bitmap bmp) {
            synchronized (mLock) {
                if (mPendingBitmap != null) {
                    mPendingBitmap = null;
                }

                // Timestamp and frame ID are maintained here, which will give downstream code some
                // idea of the timing of frames received and when frames were dropped along the way.
                mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
                mPendingFrameId++;
                mPendingBitmap = bmp;

                // Notify the processor thread if it is waiting on the next frame (see below).
                mLock.notifyAll();
            }
        }

        /**
         * As long as the processing thread is active, this executes detection on frames
         * continuously.  The next pending frame is either immediately available or hasn't been
         * received yet.  Once it is available, we transfer the frame info to local variables and
         * run detection on that frame.  It immediately loops back for the next frame without
         * pausing.
         * <p/>
         * If detection takes longer than the time in between new frames from the camera, this will
         * mean that this loop will run without ever waiting on a frame, avoiding any context
         * switching or frame acquisition time latency.
         * <p/>
         * If you find that this is using more CPU than you'd like, you should probably decrease the
         * FPS setting above to allow for some idle time in between frames.
         */
        @Override
        public void run() {
            Bitmap outputFrame;

            while (true) {
                synchronized (mLock) {
                    while (mActive && (mPendingBitmap == null)) {
                        try {
                            // Wait for the next frame to be received from the camera, since we
                            // don't have it yet.
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Frame processing loop terminated.", e);
                            return;
                        }
                    }

                    if (!mActive) {
                        // Exit the loop once this camera source is stopped or released.  We check
                        // this here, immediately after the wait() above, to handle the case where
                        // setActive(false) had been called, triggering the termination of this
                        // loop.
                        return;
                    }

                    outputFrame = mPendingBitmap;
                    // We need to clear mPendingFrameData to ensure that this buffer isn't
                    // recycled back to the camera before we are done using that data.
                    mPendingBitmap = null;
                }

                // The code below needs to run outside of synchronization, because this will allow
                // the camera to add pending frame(s) while we are running detection on the current
                // frame.

                try {
                    mProcessor.process(outputFrame, mGraphicOverlay);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception thrown from receiver.", t);
                }
            }
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        if (fireFaceOverlay != null)
            fireFaceOverlay.setCameraInfo(mTextureView.getWidth(), mTextureView.getHeight());

        processingRunnable = new FrameProcessingRunnable(new FaceDetectionProcessor(), fireFaceOverlay);
        processingThread = new Thread(processingRunnable);
        processingRunnable.setActive(true);
        processingThread.start();
        startBackgroundThread();
        mCamera = new Camera2Source(mOnImageAvailableListener, mCameraHandler, mTextureView);

        if (mTextureView.isAvailable()) {
            startCameraPreview(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }



    private void startBackgroundThread() {
        mCameraThread = new HandlerThread("CameraBackground");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    private void stopBackgroundThread() {
        mCameraThread.quitSafely();
        try {
            mCameraThread.join();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    private ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener(){

        @Override
        public void onImageAvailable(ImageReader reader) {

        }
    };


    private  TextureView.SurfaceTextureListener mSurfaceTextureListener = new  TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            startCameraPreview(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            mCamera.configureTransform(CameraActivity.this, width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            Log.d(TAG, "onSurfaceTextureUpdated");
            DefaultExecutorSupplier.getInstance().forBackgroundTasks().execute(new Runnable() {
                @Override
                public void run() {
                    WeakReference<Bitmap> weakWidget = new WeakReference<Bitmap>(mTextureView.getBitmap());
                    processingRunnable.setNextFrame(weakWidget.get());
                }
            });
        }
    };

    private void startCameraPreview( int width, int height) {
        mCamera.setUpCameraOutputs(this, width, height);
        mCamera.configureTransform(this, width, height);
        mCamera.openCamera(this);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");

        mCamera.shutDown();
        stopBackgroundThread();
        super.onPause();
    }


}
