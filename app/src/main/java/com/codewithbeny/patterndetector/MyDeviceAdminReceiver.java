package com.codewithbeny.patterndetector;

import android.annotation.SuppressLint;
import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

public class MyDeviceAdminReceiver extends DeviceAdminReceiver {
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    Context context;

    @SuppressLint("MissingPermission")
    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        Log.d("Password Status", "Failed");
        this.context = context;
        HandlerThread thread = new HandlerThread("CameraBackground");
        thread.start();
        mBackgroundHandler = new Handler(thread.getLooper());

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Size[] jpegSizes = null;
            if (characteristics != null) {
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(ImageFormat.JPEG);
            }
            int imageWidth = 640;  // Specify the desired image width
            int imageHeight = 480; // Specify the desired image height
            if (jpegSizes != null && jpegSizes.length > 0) {
                imageWidth = jpegSizes[0].getWidth();
                imageHeight = jpegSizes[0].getHeight();
            }

            mImageReader = ImageReader.newInstance(imageWidth, imageHeight,
                    ImageFormat.JPEG, 1);
            mImageReader.setOnImageAvailableListener(mImageAvailableListener, mBackgroundHandler);


            cameraManager.openCamera(cameraId, mCameraStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    private final ImageReader.OnImageAvailableListener mImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    // Process the captured image here
                     saveImageToGallery(image);
                    // Close the image and release resources
                    image.close();

                    // Clean up the camera resources
                    mCaptureSession.close();
                    mCameraDevice.close();
                    mImageReader.close();
                }
            };

    @Override
    public void onPasswordSucceeded(@NonNull Context context, @NonNull Intent intent) {
        super.onPasswordSucceeded(context, intent);
        Log.d("Password Status", "Passed");
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];

            cameraManager.openCamera(cameraId, mCameraStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = new SurfaceTexture(1);
            texture.setDefaultBufferSize(120, 120);
            Surface surface = new Surface(texture);

            CaptureRequest.Builder captureRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (mCameraDevice == null) {
                                return;
                            }
                            mCaptureSession = cameraCaptureSession;
                            try {
                                mCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // Handle configuration failure
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void captureImage() {
        try {
            if (mCameraDevice == null) {
                return;
            }
            CaptureRequest.Builder captureRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.addTarget(mImageReader.getSurface());

            CaptureRequest captureRequest = captureRequestBuilder.build();
            mCaptureSession.capture(captureRequest, null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void saveImageToGallery(Image image) {
        // Create a file name for the image
        String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";

        // Get the directory to store the image
        File storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

        // Create the file
        File imageFile = new File(storageDir, fileName);

        // Create an output stream to write the image data
        try (OutputStream outputStream = new FileOutputStream(imageFile)) {
            // Get the image data from the Image object
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            // Write the image data to the output stream
            outputStream.write(data);
            outputStream.flush();

            // Notify the gallery to scan for the saved image
            MediaScannerConnection.scanFile(
                    context,
                    new String[]{imageFile.getAbsolutePath()},
                    null,
                    null
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}