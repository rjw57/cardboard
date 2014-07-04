package com.google.vrtoolkit.cardboard.samples.treasurehunt;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.TextureView;

import java.io.IOException;

/**
 * Created by rjw57 on 04/07/14.
 */
public class HandTracker implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {
    private static final String TAG = "HandTracker";

    private TextureView mTextureView;
    private Camera mCamera;

    public HandTracker(TextureView previewView) {
        mTextureView = previewView;
        mTextureView.setSurfaceTextureListener(this);
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

        mCamera.stopPreview();

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        /* NOP */
    }

    @Override
    public void onPreviewFrame(byte[] bytes, Camera camera) {
        Log.i(TAG, "GOT FRAME!!!");
    }
}
