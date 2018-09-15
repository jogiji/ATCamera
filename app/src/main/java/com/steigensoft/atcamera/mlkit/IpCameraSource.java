
package com.steigensoft.atcamera.mlkit;

import android.content.Context;
import android.view.SurfaceHolder;

import com.google.android.gms.common.images.Size;

public class IpCameraSource {
    private static final String TAG = IpCameraSource.class.getSimpleName();
    private final Context mContext;
    private final GraphicOverlay mGraphicOverlay;
    private final String mStreamPath;
    private final FrameProcessingRunnable processingRunnable;
    private Size previewSize;
    private int cameraFacing;
    private Thread processingThread;
    private VisionImageProcessor frameProcessor;
    private final Object processorLock = new Object();

    public IpCameraSource(Context context, GraphicOverlay graphicOverlay, String streamPath) {
        this.mContext = context;
        this.mGraphicOverlay = graphicOverlay;
        mGraphicOverlay.clear();
        processingRunnable = new FrameProcessingRunnable();
        this.mStreamPath = streamPath;
    }

    public void stop() {

    }

    public void release() {

    }

    public void start(SurfaceHolder holder) {

    }

    public Size getPreviewSize() {
        return previewSize;
    }

    public void setPreviewSize(Size previewSize) {
        this.previewSize = previewSize;
    }

    public int getCameraFacing() {
        return cameraFacing;
    }

    public void setCameraFacing(int cameraFacing) {
        this.cameraFacing = cameraFacing;
    }

    public void setRequestedPreviewSize(int width, int height) {

    }

    public void setMachineLearningFrameProcessor(VisionImageProcessor machineLearningFrameProcessor) {
        synchronized (processorLock) {
            cleanScreen();
            if (frameProcessor != null) {
                frameProcessor.stop();
            }
            frameProcessor = machineLearningFrameProcessor;
        }
    }



    /** Cleans up graphicOverlay and child classes can do their cleanups as well . */
    private void cleanScreen() {
        mGraphicOverlay.clear();
    }

    private class FrameProcessingRunnable implements Runnable{
        @Override
        public void run() {

        }
    }
}
