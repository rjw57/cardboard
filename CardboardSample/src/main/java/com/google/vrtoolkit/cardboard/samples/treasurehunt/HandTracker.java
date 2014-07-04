package com.google.vrtoolkit.cardboard.samples.treasurehunt;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.Matrix;
import android.util.Log;
import android.view.TextureView;

import com.google.vrtoolkit.cardboard.HeadTransform;

import org.bytedeco.javacpp.ARToolKitPlus;
import org.bytedeco.javacpp.ARToolKitPlus.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Created by rjw57 on 04/07/14.
 */
public class HandTracker implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {
    private static final String TAG = "HandTracker";

    private class CapturedImage {
        public float[] headTransform;
        public byte[] imageData;
    }

    public interface HandMatrixCallback {
        public void onMarkerMatrix(float[] matrix);
    }

    private TextureView mTextureView;
    private Camera mCamera;
    private MultiTracker mTracker;
    private Camera.Size mPreviewSize;
    private ARToolKitPlus.Camera mTrackerCamera;
    private float[] mHeadTransform;
    private HandMatrixCallback mCallback;

    // push images onto this queue when they arrive
    private ArrayBlockingQueue<CapturedImage> mLumImageQueue;

    private DetectorThread mDetectorThread;

    public HandTracker(TextureView previewView) {
        mTextureView = previewView;
        mTextureView.setSurfaceTextureListener(this);
        mHeadTransform = new float[4*4];
        Matrix.setIdentityM(mHeadTransform, 0);
    }

    public void setLastHeadTransform(HeadTransform headTransform) {
        headTransform.getHeadView(mHeadTransform, 0);
    }

    public void setHandMatrixCallback(HandMatrixCallback handMatrixCallback) {
        mCallback = handMatrixCallback;
        if(mDetectorThread != null) {
            mDetectorThread.setCallback(handMatrixCallback);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i2) {
        Log.d(TAG, "onSurfaceTextureAvailable");

        // Initialise the camera preview
        mCamera = Camera.open();
        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.setPreviewCallback(this);

        // Initialise the marker tracker
        Camera.Parameters cameraParameters = mCamera.getParameters();
        List<int[]> supportedPreviewFpsRange = cameraParameters.getSupportedPreviewFpsRange();

        cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        cameraParameters.setPreviewSize(1280, 720);
        for(int j=0; j<supportedPreviewFpsRange.size(); ++j) {
            int[] range = supportedPreviewFpsRange.get(j);

            if((range[1] < 20000) && (range[0] > 10000)) {
                Log.i(TAG, "Setting FPS range to " + range[0] + ", " + range[1]);
                cameraParameters.setPreviewFpsRange(range[0], range[1]);
            }
        }
        mCamera.setParameters(cameraParameters);
        cameraParameters = mCamera.getParameters();

        mPreviewSize = cameraParameters.getPreviewSize();
        mTracker = new MultiTracker(mPreviewSize.width, mPreviewSize.height);

        mTracker.setBorderWidth(0.25f);
        mTracker.setPixelFormat(ARToolKitPlus.PIXEL_FORMAT_LUM);
        mTracker.setUndistortionMode(ARToolKitPlus.UNDIST_NONE);
        mTracker.setMarkerMode(ARToolKitPlus.MARKER_ID_SIMPLE);
        mTracker.setImageProcessingMode(ARToolKitPlus.IMAGE_HALF_RES);
        // mTracker.activateAutoThreshold(true);

        ARToolKitPlus.Camera cam = new ARToolKitPlus.Camera();
        Log.i(TAG, "cam: " + cam);

        cam.xsize(1920);
        cam.ysize(1080);
        cam.mat(0,0,1665);  cam.mat(0,1,0);     cam.mat(0,2,959);   cam.mat(0,3,0);
        cam.mat(1,0,0);     cam.mat(1,1,1665);  cam.mat(1,2,539);   cam.mat(1,3,0);
        cam.mat(2,0,0);     cam.mat(2,1,0);     cam.mat(2,2,1);     cam.mat(2,3,0);

        for(int j=0; j<4; j++) {
            cam.kc(j, 0);
        }

        cam.changeFrameSize(mPreviewSize.width, mPreviewSize.height);

        Log.i(TAG, "xsize: " + cam.xsize());
        Log.i(TAG, "ysize: " + cam.ysize());
        Log.i(TAG, "[ " + cam.mat(0, 0) + ", " + cam.mat(0, 1) + ", " + cam.mat(0, 2) + ", " + cam.mat(0, 3) + " ]");
        Log.i(TAG, "[ " + cam.mat(1, 0) + ", " + cam.mat(1, 1) + ", " + cam.mat(1, 2) + ", " + cam.mat(1, 3) + " ]");
        Log.i(TAG, "[ " + cam.mat(2, 0) + ", " + cam.mat(2, 1) + ", " + cam.mat(2, 2) + ", " + cam.mat(2, 3) + " ]");

        mTrackerCamera = cam;
        mTracker.setCamera(mTrackerCamera);

        // Start detector thread
        mLumImageQueue = new ArrayBlockingQueue<CapturedImage>(1);
        mDetectorThread = new DetectorThread(mTracker, mLumImageQueue);
        mDetectorThread.setCallback(mCallback);
        mDetectorThread.start();

        // Start capturing video
        mCamera.startPreview();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i2) {
        /* NOP */
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "onSurfaceTextureDestroyed");

        // Stop grabbing pictures
        mCamera.stopPreview();

        // Signal detector thread to exit
        mDetectorThread.interrupt();
        try {
            mDetectorThread.join(100);
        } catch (InterruptedException e) {
            // oh well, we tried
            Log.e(TAG, "detector thread did not exit cleanly");
        }

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        /* NOP */
    }

    @Override
    public void onPreviewFrame(byte[] bytes, Camera camera) {
        // Dont't do anything if we don't have a current head transform
        if(mHeadTransform == null) {
            return;
        }

        // Sanity check the camera parameters
        Camera.Parameters cameraParameters = camera.getParameters();

        if(cameraParameters.getPreviewFormat() != ImageFormat.NV21) {
            Log.e(TAG, "cannot handle image with format: " + cameraParameters.getPreviewFormat());
            return;
        }

        if(!cameraParameters.getPreviewSize().equals(mPreviewSize)) {
            Log.e(TAG, "camera preview changed size from " + mPreviewSize.toString() + " to " +
                cameraParameters.getPreviewSize().toString());
            return;
        }

        // Create new image structure
        CapturedImage newImg = new CapturedImage();
        newImg.headTransform = mHeadTransform;
        newImg.imageData = Arrays.copyOf(bytes, mPreviewSize.width * mPreviewSize.height);

        // Copy luminance portion of image and attempt to add to detector queue.
        // We drain any existing images first.
        mLumImageQueue.drainTo(new Vector<CapturedImage>());
        mLumImageQueue.add(newImg);
    }

    private class DetectorThread extends Thread {
        private ArrayBlockingQueue<CapturedImage> mLumImageQueue;
        private Tracker mTracker;
        private HandMatrixCallback mCallback;

        public DetectorThread(Tracker tracker, ArrayBlockingQueue<CapturedImage> lumImageQueue) {
            super();
            mTracker = tracker;
            mLumImageQueue = lumImageQueue;
        }

        public void setCallback(HandMatrixCallback callback) {
            mCallback = callback;
        }

        @Override
        public void run() {
            boolean shouldExit = false;

            while(!shouldExit) {
                try {
                    CapturedImage image = mLumImageQueue.take();

                    ARMarkerInfo markers = new ARMarkerInfo(null);
                    int[] markerNumArray = { -1, };

                    mTracker.arDetectMarker(image.imageData, 128, markers, markerNumArray);
                    int nMarkers = markerNumArray[0];

                    float[] markerOpenGLMatrix = null;

                    for(int i=0; (i<nMarkers) && (markerOpenGLMatrix == null); i++) {
                        // A bit strange, but advance the pointer to position "i"
                        markers.position(i);

                        // Skip markers we're not interested in
                        int id = markers.id();
                        if(id != 20) {  // FIXME: this is a hard coded ID
                            continue;
                        }

                        // Compute marker transformation matrix (i.e. matrix transforming from
                        // marker frame to camera frame).
                        float[] markerMatrix = new float[3*4];
                        float[] centre = {0.f, 0.f};
                        mTracker.rppGetTransMat(markers, centre, 0.035f, markerMatrix);

                        // Convert matrix to OpenGL-style matrix extending it with a 0,0,0,1 row
                        markerOpenGLMatrix = new float[4*4];
                        Matrix.setIdentityM(markerOpenGLMatrix, 0);
                        for(int r=0; r<3; r++) {
                            for(int c=0; c<4; c++) {
                                markerOpenGLMatrix[r + c*4] = markerMatrix[c + r*4];
                            }
                        }

                        // Add in head transform
                        Matrix.multiplyMM(markerOpenGLMatrix, 0,
                                image.headTransform, 0, markerOpenGLMatrix, 0);
                    }

                    // Inform the callback
                    if(mCallback != null) {
                        mCallback.onMarkerMatrix(markerOpenGLMatrix);
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "detector thread interrupted");
                    shouldExit = true;
                }
            }
        }
    }
}
