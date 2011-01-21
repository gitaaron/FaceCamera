package com.alu.vc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.media.FaceDetector;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.media.FaceDetector;
import android.hardware.Camera.Size;

public class VCSurfaceView extends SurfaceView implements SurfaceHolder.Callback, PreviewCallback{
	SurfaceHolder mHolder;
	Camera mCamera;
	Context mContext;
	
	private Boolean cameraActive;
	
	public VCSurfaceView(Context context, AttributeSet attrs) {
		super(context, attrs);
		Log.i("VCSurfaceView", "init");
		mContext = context;
		cameraActive = false;
		
		mHolder = getHolder();
		mHolder.addCallback(this);
		mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		
		handler_ = new Handler();
		faces_ = new FaceResult[MAX_FACE];
		for (int i=0; i < MAX_FACE; i++) 
			faces_[i] = new FaceResult();
		
	}
	
	Camera.PictureCallback mPictureCallback = new Camera.PictureCallback() {
		@Override
		public void onPictureTaken(byte[] data, Camera camera) {
			if (data != null) {
				
			}
		}
		
	};
	
	public void surfaceCreated(SurfaceHolder holder) {
		Log.i("VCSurfaceView", "surface changed");
		mCamera = Camera.open();
		try {
			mCamera.setPreviewDisplay(holder);
		} catch (IOException exception) {
			mCamera.release();
			mCamera = null;
		}
	}
	
	private int previewWidth_;
	private int previewHeight_;

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		// TODO Auto-generated method stub
		if(cameraActive==false) {
			Log.i("VCSurfaceView", "starting camera");
			Camera.Parameters parameters = mCamera.getParameters();
			List<Size> sizes = parameters.getSupportedPreviewSizes();
			Size optimalSize = getOptimalPreviewSize(sizes, w, h);
			
			parameters.setPreviewSize(optimalSize.width, optimalSize.height);
			
			mCamera.setParameters(parameters);
			
			cameraActive = true;
			
			
			previewWidth_ = optimalSize.width;
			previewHeight_ = optimalSize.height;
			bufflen_ = previewWidth_*previewHeight_;
			grayBuff_ = new byte[bufflen_];
			rgbs_ = new int[bufflen_];
			float aspect = (float) previewHeight_ / (float) previewWidth_;
			
			prevSettingWidth_ = 240;
			prevSettingHeight_ = 160;
			
			fdet_ = new FaceDetector(prevSettingWidth_, (int)(prevSettingWidth_*aspect), MAX_FACE);
			mCamera.setPreviewCallback(this);
			mCamera.startPreview();
		}
		
	}
	

	private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
		
		Log.i("Preview", "getOptimalPreviewSize");
		final double ASPECT_TOLERANCE = 0.05;
		double targetRatio = (double) w / h;
		if (sizes == null) return null;
		
		Size optimalSize = null;
		double minDiff = Double.MAX_VALUE;
		
		int targetHeight = h;
		
		// Try to find a size match aspect ratio and size
		for (Size size : sizes) {
			double ratio = (double) size.width / size.height;
			if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
			
			if (Math.abs(size.height - targetHeight) < minDiff) {
				optimalSize = size;
				minDiff = Math.abs(size.height - targetHeight);
			}
		}
		
		// Cannot find the one match the aspect ratio, ignore the requirement
		if (optimalSize == null) {
			minDiff = Double.MAX_VALUE;
			for (Size size : sizes) {
				if (Math.abs(size.height - targetHeight) < minDiff) {
					optimalSize = size;
					minDiff = Math.abs(size.height - targetHeight);
				}
			}
		}
		
		return optimalSize;
	}
	

	@Override
	public void surfaceDestroyed(SurfaceHolder arg0) {
		// TODO Auto-generated method stub

		
		releaseCamera();
		
	}
	
	private void releaseCamera() {
		mCamera.setPreviewCallback(null);
		mCamera.stopPreview();
		waitForFdetThreadComplete();
		mCamera.release();
		mCamera = null;
		cameraActive = false;
	}
	
	
	
	private static final int MAX_FACE = 1;
	private boolean isThreadWorking_ = false;
	private byte[] grayBuff_;
	private int bufflen_;
	private Handler handler_;
	private FaceDetectThread detectThread_ = null;
	private int[] rgbs_;
	private int prevSettingWidth_;
	private int prevSettingHeight_;
	private FaceDetector fdet_;
	private FaceResult faces_[];
	

	@Override
	public void onPreviewFrame(byte[] _data, Camera _camera) {
		
		if(!isThreadWorking_) {
			isThreadWorking_ = true;
			ByteBuffer bbuffer = ByteBuffer.wrap(_data);
			bbuffer.get(grayBuff_, 0, bufflen_);
			waitForFdetThreadComplete();
			detectThread_ = new FaceDetectThread(handler_, this.mContext);
			detectThread_.setBuffer(grayBuff_);
			detectThread_.start();
		}
		
	}
	
	private void waitForFdetThreadComplete() {
		if(detectThread_ == null) {
			return;
		}
		
		if (detectThread_.isAlive() ){ 
			try {
				detectThread_.join();
				detectThread_ = null;
			} catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
		
	}
	
	private class FaceDetectThread extends Thread {
		private Handler handler_;
		private byte[] graybuff_ = null;
		private Context ctx;
		
		public FaceDetectThread(Handler handler, Context ctx) {
			this.ctx = ctx;
			handler_ = handler;
		}
		
		public void setBuffer(byte[] graybuff) {
			graybuff_ = graybuff;
		}
		
		public void run() {
			Log.i("FaceDetectThread", "running");
			gray8toRGB32(graybuff_, previewWidth_, previewHeight_, rgbs_);
			float aspect = (float) previewHeight_/(float)previewWidth_;
			int w = prevSettingWidth_;
			int h = (int)(prevSettingWidth_*aspect);
			float xScale = (float) previewWidth_/(float)prevSettingWidth_;
			float yScale = (float) previewHeight_ / (float)prevSettingHeight_;
			
			Bitmap bmp = Bitmap.createScaledBitmap(Bitmap.createBitmap(rgbs_, previewWidth_, previewHeight_, Bitmap.Config.RGB_565), w, h, false);
			
			FaceDetector.Face[] fullResults = new FaceDetector.Face[MAX_FACE];
			fdet_.findFaces(bmp, fullResults);
			for (int i=0; i < MAX_FACE; i++) {
				if(fullResults[i]==null) {
					faces_[i].clear();
				} else {
					PointF mid = new PointF();
					fullResults[i].getMidPoint(mid);
					mid.x *= xScale;
					mid.y *= yScale;
					float eyedist = fullResults[i].eyesDistance()*xScale;
					faces_[i].setFace(mid, eyedist);
					Log.i("FaceDetectThread", "found!");
					// Launch new activity
					Intent li = new Intent(this.ctx, FaceDetected.class);
					this.ctx.startActivity(li);
				}
				
			}
		
			
			handler_.post(new Runnable() {
				public void run() {
					isThreadWorking_ = false;
				}
			});
			
		}
		
		private void gray8toRGB32(byte[] gray8, int width, int height, int[] rgb_32s) {
			final int endPtr = width * height;
			int ptr = 0;
			while (true) {
				if (ptr == endPtr) 
					break;
				
				final int Y = gray8[ptr] & 0xff;
				rgb_32s[ptr] = 0xff000000 + (Y << 16) + (Y << 8) + Y;
				ptr++;
			}
		}
		
	}
	
	
	private class FaceResult extends Object {
		private PointF midEye_;
		private float eyeDist_;
		public FaceResult() {
			midEye_ = new PointF(0.0f, 0.0f);
			eyeDist_ = 0.0f;
		}
		
		
		public void setFace(PointF midEye, float eyeDist) {
			set_(midEye, eyeDist);
		}
		
		public void clear() {
			set_(new PointF(0.0f, 0.0f), 0.0f);
		}
		
		private synchronized void set_(PointF midEye, float eyeDist) {
			midEye_.set(midEye);
			eyeDist_ = eyeDist;
		}
		
		public float eyesDistance() {
			return eyeDist_;
		}
		
		public void getMidPoint(PointF pt) {
			pt.set(midEye_);
		}
		
		
	}

}
