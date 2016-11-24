package de.handler.mobile.android.videobox;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


public class Camera2VideoFragment extends Fragment {
	private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
	private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
	private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
	private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

	static {
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
	}

	static {
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
	}

	/**
	 * An {@link CameraView} for camera preview.
	 */
	private CameraView mCameraView;
	/**
	 * A reference to the opened {@link android.hardware.camera2.CameraDevice}.
	 */
	private CameraDevice mCameraDevice;
	/**
	 * A reference to the current {@link android.hardware.camera2.CameraCaptureSession} for
	 * preview.
	 */
	private CameraCaptureSession mPreviewSession;
	/**
	 * The {@link android.util.Size} of camera preview.
	 */
	private Size mPreviewSize;
	/**
	 * The {@link android.util.Size} of video recording.
	 */
	private Size mVideoSize;
	/**
	 * MediaRecorder
	 */
	private MediaRecorder mMediaRecorder;
	/**
	 * Whether the app is recording video now
	 */
	private boolean mIsRecordingVideo;
	/**
	 * An additional thread for running tasks that shouldn't block the UI.
	 */
	private HandlerThread mBackgroundThread;
	/**
	 * A {@link Handler} for running tasks in the background.
	 */
	private Handler mBackgroundHandler;
	/**
	 * A {@link Semaphore} to prevent the app from exiting before closing the camera.
	 */
	private Semaphore mCameraOpenCloseLock = new Semaphore(1);
	private Integer mSensorOrientation;
	private String mNextVideoAbsolutePath;
	private CaptureRequest.Builder mPreviewBuilder;
	/**
	 * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
	 */
	private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
		@Override
		public void onOpened(@NonNull CameraDevice cameraDevice) {
			mCameraDevice = cameraDevice;
			startPreview();
			mCameraOpenCloseLock.release();
			if (null != mCameraView) {
				configureTransform(mCameraView.getWidth(), mCameraView.getHeight());
			}
		}

		@Override
		public void onDisconnected(@NonNull CameraDevice cameraDevice) {
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
		}

		@Override
		public void onError(@NonNull CameraDevice cameraDevice, int error) {
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
			AbstractActivity activity = ((AbstractActivity) getActivity());
			switch (error) {
				case ERROR_CAMERA_IN_USE:
					activity.showInfo(R.string.error_camera_in_use);
					break;
				case ERROR_MAX_CAMERAS_IN_USE:
					activity.showInfo(R.string.error_camera_max_in_use);
					break;
				case ERROR_CAMERA_DISABLED:
					activity.showInfo(R.string.error_camera_disabled);
					break;
				case ERROR_CAMERA_DEVICE:
					activity.showInfo(R.string.error_camera_fatal);
					break;
				case ERROR_CAMERA_SERVICE:
					activity.showInfo(R.string.error_camera_service);
					break;
			}
		}

	};

	/**
	 * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
	 * {@link TextureView}.
	 */
	private TextureView.SurfaceTextureListener mSurfaceTextureListener
			= new TextureView.SurfaceTextureListener() {
		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
											  int width, int height) {
			openCamera(width, height);
		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
												int width, int height) {
			configureTransform(width, height);
		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
			return true;
		}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
		}

	};

	/**
	 * In this app, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
	 * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
	 *
	 * @param choices The list of available sizes
	 * @return The video size
	 */
	private static Size chooseVideoSize(Size[] choices) {
		for (Size size : choices) {
			if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
				return size;
			}
		}
		return choices[choices.length - 1];
	}

	/**
	 * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
	 * width and height are at least as large as the respective requested values, and whose aspect
	 * ratio matches with the specified value.
	 *
	 * @param choices     The list of sizes that the camera supports for the intended output class
	 * @param width       The minimum desired width
	 * @param height      The minimum desired height
	 * @param aspectRatio The aspect ratio
	 * @return The optimal {@code Size}, or an arbitrary one if none were big enough
	 */
	private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
		// Collect the supported resolutions that are at least as big as the preview Surface
		List<Size> bigEnough = new ArrayList<>();
		int w = aspectRatio.getWidth();
		int h = aspectRatio.getHeight();
		for (Size option : choices) {
			if (option.getHeight() == option.getWidth() * h / w &&
					option.getWidth() >= width && option.getHeight() >= height) {
				bigEnough.add(option);
			}
		}

		// Pick the smallest of those, assuming we found any
		if (bigEnough.size() > 0) {
			return Collections.min(bigEnough, new CompareSizesByArea());
		} else {
			return choices[0];
		}
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
							 Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_camera2_video, container, false);
	}

	@Override
	public void onViewCreated(final View view, Bundle savedInstanceState) {
		mCameraView = (CameraView) view.findViewById(R.id.texture);
	}

	@Override
	public void onResume() {
		super.onResume();
		startBackgroundThread();
		if (mCameraView.isAvailable()) {
			openCamera(mCameraView.getWidth(), mCameraView.getHeight());
		} else {
			mCameraView.setSurfaceTextureListener(mSurfaceTextureListener);
		}
	}

	@Override
	public void onPause() {
		closeCamera();
		stopBackgroundThread();
		super.onPause();
	}

	/**
	 * Starts a background thread and its {@link Handler}.
	 */
	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
	}

	/**
	 * Stops the background thread and its {@link Handler}.
	 */
	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		try {
			mBackgroundThread.join();
			mBackgroundThread = null;
			mBackgroundHandler = null;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
	 */
	private void openCamera(int width, int height) {
		final AbstractActivity activity = (AbstractActivity) getActivity();
		if (activity.isFinishing()) {
			return;
		}
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		try {
			if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
				throw new RuntimeException("Time out waiting to lock camera opening.");
			}

			String cameraId = manager.getCameraIdList()[0];
			// Choose the sizes for camera preview and video recording
			CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
			StreamConfigurationMap map = characteristics
					.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
			mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
			if (map != null) {
				mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
				mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
						width, height, mVideoSize);
			}

			int orientation = getResources().getConfiguration().orientation;
			if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
				mCameraView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
			} else {
				mCameraView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
			}
			configureTransform(width, height);
			mMediaRecorder = new MediaRecorder();

			if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
				return;
			}
			manager.openCamera(cameraId, mStateCallback, null);
		} catch (CameraAccessException e) {
			activity.showInfo(R.string.error_camera_not_accessible);
			activity.finish();
		} catch (NullPointerException e) {
			// Currently an NPE is thrown when the Camera2API is used but not supported on the
			// device this code runs.
			activity.showInfo(R.string.error_camera2_support);
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera opening.");
		}
	}

	private void closeCamera() {
		try {
			mCameraOpenCloseLock.acquire();
			closePreviewSession();
			if (null != mCameraDevice) {
				mCameraDevice.close();
				mCameraDevice = null;
			}
			if (null != mMediaRecorder) {
				mMediaRecorder.release();
				mMediaRecorder = null;
			}
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera closing.");
		} finally {
			mCameraOpenCloseLock.release();
		}
	}

	/**
	 * Start the camera preview.
	 */
	private void startPreview() {
		if (null == mCameraDevice || !mCameraView.isAvailable() || null == mPreviewSize) {
			return;
		}
		try {
			closePreviewSession();
			SurfaceTexture texture = mCameraView.getSurfaceTexture();
			assert texture != null;
			texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
			mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

			Surface previewSurface = new Surface(texture);
			mPreviewBuilder.addTarget(previewSurface);

			mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface), new CameraCaptureSession.StateCallback() {
				@Override
				public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
					mPreviewSession = cameraCaptureSession;
					updatePreview();
				}

				@Override
				public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
					Activity activity = getActivity();
					if (null != activity) {
						Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
					}
				}
			}, mBackgroundHandler);
		} catch (CameraAccessException e) {
			((AbstractActivity) getActivity()).showInfo(R.string.error_camera_not_accessible);
		}
	}

	/**
	 * Update the camera preview. {@link #startPreview()} needs to be called in advance.
	 */
	private void updatePreview() {
		if (null == mCameraDevice) {
			return;
		}
		try {
			setUpCaptureRequestBuilder(mPreviewBuilder);
			HandlerThread thread = new HandlerThread("CameraPreview");
			thread.start();
			mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
		} catch (CameraAccessException e) {
			((AbstractActivity) getActivity()).showInfo(R.string.error_camera_not_accessible);
		}
	}

	private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
		builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
	}

	/**
	 * Configures the necessary {@link android.graphics.Matrix} transformation to `mCameraView`.
	 * This method should not to be called until the camera preview size is determined in
	 * openCamera, or until the size of `mCameraView` is fixed.
	 *
	 * @param viewWidth  The width of `mCameraView`
	 * @param viewHeight The height of `mCameraView`
	 */
	private void configureTransform(int viewWidth, int viewHeight) {
		Activity activity = getActivity();
		if (mCameraView == null || mPreviewSize == null) {
			return;
		}
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		Matrix matrix = new Matrix();
		RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
		RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
		float centerX = viewRect.centerX();
		float centerY = viewRect.centerY();
		if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
			float scale = Math.max(
					(float) viewHeight / mPreviewSize.getHeight(),
					(float) viewWidth / mPreviewSize.getWidth());
			matrix.postScale(scale, scale, centerX, centerY);
			matrix.postRotate(90 * (rotation - 2), centerX, centerY);
		}
		mCameraView.setTransform(matrix);
	}

	private void setUpMediaRecorder() throws IOException {
		final Activity activity = getActivity();
		if (null == activity) {
			return;
		}
		mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
		mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
			mNextVideoAbsolutePath = getVideoFilePath(getActivity());
		}
		mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
		mMediaRecorder.setVideoEncodingBitRate(10000000);
		mMediaRecorder.setVideoFrameRate(30);
		mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
		mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
		mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		switch (mSensorOrientation) {
			case SENSOR_ORIENTATION_DEFAULT_DEGREES:
				mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
				break;
			case SENSOR_ORIENTATION_INVERSE_DEGREES:
				mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
				break;
		}
		mMediaRecorder.prepare();
	}

	private String getVideoFilePath(Context context) {
		return context.getExternalFilesDir(null).getAbsolutePath() + "/"
				+ System.currentTimeMillis() + ".mp4";
	}

	private void startRecordingVideo() {
		if (mCameraDevice == null || !mCameraView.isAvailable() || mPreviewSize == null) {
			return;
		}
		try {
			closePreviewSession();
			setUpMediaRecorder();
			SurfaceTexture texture = mCameraView.getSurfaceTexture();
			assert texture != null;
			texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
			mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
			List<Surface> surfaces = new ArrayList<>();

			// Set up Surface for the camera preview
			Surface previewSurface = new Surface(texture);
			surfaces.add(previewSurface);
			mPreviewBuilder.addTarget(previewSurface);

			// Set up Surface for the MediaRecorder
			Surface recorderSurface = mMediaRecorder.getSurface();
			surfaces.add(recorderSurface);
			mPreviewBuilder.addTarget(recorderSurface);

			// Start a capture session
			// Once the session starts, we can update the UI and start recording
			mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
				@Override
				public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
					mPreviewSession = cameraCaptureSession;
					updatePreview();
					getActivity().runOnUiThread(() -> {
						// UI
						mIsRecordingVideo = true;

						// Start recording
						mMediaRecorder.start();
					});
				}

				@Override
				public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
					AbstractActivity activity = (AbstractActivity) getActivity();
					activity.showInfo(R.string.error_nearby_connection_failed);
				}
			}, mBackgroundHandler);
		} catch (CameraAccessException e) {
			((AbstractActivity) getActivity()).showInfo(R.string.error_camera_not_accessible);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void stopRecordingVideo() {
		// UI
		mIsRecordingVideo = false;
		// Stop recording
		mMediaRecorder.stop();
		mMediaRecorder.reset();

		Toast.makeText(getContext(), "Video saved: " + mNextVideoAbsolutePath,
				Toast.LENGTH_SHORT).show();
		mNextVideoAbsolutePath = null;
		startPreview();
	}

	private void closePreviewSession() {
		if (mPreviewSession != null) {
			mPreviewSession.close();
			mPreviewSession = null;
		}
	}

	/**
	 * Compares two {@code Size}s based on their areas.
	 */
	static class CompareSizesByArea implements Comparator<Size> {
		@Override
		public int compare(Size lhs, Size rhs) {
			// We cast here to ensure the multiplications won't overflow
			return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
					(long) rhs.getWidth() * rhs.getHeight());
		}

	}
}