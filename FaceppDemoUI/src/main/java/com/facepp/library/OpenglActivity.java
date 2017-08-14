package com.facepp.library;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLSurfaceView.Renderer;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.facepp.library.util.CameraMatrix;
import com.facepp.library.util.ConUtil;
import com.facepp.library.util.DialogUtil;
import com.facepp.library.util.FaceArObj;
import com.facepp.library.util.ICamera;
import com.facepp.library.util.MediaRecorderUtil;
import com.facepp.library.util.OpenGLDrawRect;
import com.facepp.library.util.OpenGLUtil;
import com.facepp.library.util.PointsMatrix;
import com.facepp.library.util.Screen;
import com.facepp.library.util.SensorEventUtil;
import com.megvii.facepp.sdk.Facepp;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static java.lang.Math.sqrt;

public class OpenglActivity extends Activity
		implements PreviewCallback, Renderer, SurfaceTexture.OnFrameAvailableListener {

	private boolean isStartRecorder, is3DPose, isDebug, isROIDetect, is106Points, isBackCamera, isFaceProperty,
			isOneFaceTrackig;
	private String trackModel;
	private boolean isTiming = true; // 是否是定时去刷新界面;
	private int printTime = 31;
	private GLSurfaceView mGlSurfaceView;
	private ICamera mICamera;
	private Camera mCamera;
	private DialogUtil mDialogUtil;
	private TextView debugInfoText, debugPrinttext, AttriButetext;
	private HandlerThread mHandlerThread = new HandlerThread("facepp");
	private Handler mHandler;
	private Facepp facepp;
	private MediaRecorderUtil mediaRecorderUtil;
	private int min_face_size = 200;
	private int detection_interval = 25;
	private HashMap<String, Integer> resolutionMap;
	private SensorEventUtil sensorUtil;
	private float roi_ratio = 0.8f;

    // 畫臉部AR imageView用
	private RelativeLayout imageParentLayout;
    final ArrayList<ImageView> noseImgViews = new ArrayList<>();
	final ArrayList<TextView> noseTxtViews = new ArrayList<>();
	Bitmap noseBitmap;
	BitmapDrawable bd;
	boolean NOSE_IMG_ACTIVATE = false;
	// AR用 ArrayList<FaceArObj> faceARs = new ArrayList<>();
	ArrayList<FaceArObj> faceARs = new ArrayList<>();
    // 拍照用
    byte[] mostRecentPic;
    Button snapshotButton;

    @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Screen.initialize(this);
		setContentView(R.layout.activity_opengl);

		init();
		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				startRecorder();
			}
		}, 2000);
	}

	private void init() {
		if (android.os.Build.MODEL.equals("PLK-AL10"))
			printTime = 50;

		isStartRecorder = getIntent().getBooleanExtra("isStartRecorder", false);
		is3DPose = getIntent().getBooleanExtra("is3DPose", false);
		isDebug = getIntent().getBooleanExtra("isdebug", false);
		isROIDetect = getIntent().getBooleanExtra("ROIDetect", false);
		is106Points = getIntent().getBooleanExtra("is106Points", false);
		isBackCamera = getIntent().getBooleanExtra("isBackCamera", false);
		isFaceProperty = getIntent().getBooleanExtra("isFaceProperty", false);
		isOneFaceTrackig = getIntent().getBooleanExtra("isOneFaceTrackig", false);
		trackModel = getIntent().getStringExtra("trackModel");

		min_face_size = getIntent().getIntExtra("faceSize", min_face_size);
		detection_interval = getIntent().getIntExtra("interval", detection_interval);
		resolutionMap = (HashMap<String, Integer>) getIntent().getSerializableExtra("resolution");

		facepp = new Facepp();

		sensorUtil = new SensorEventUtil(this);

		mHandlerThread.start();
		mHandler = new Handler(mHandlerThread.getLooper());

		mGlSurfaceView = (GLSurfaceView) findViewById(R.id.opengl_layout_surfaceview);
		mGlSurfaceView.setEGLContextClientVersion(2);// 创建一个OpenGL ES 2.0
														// context
		mGlSurfaceView.setRenderer(this);// 设置渲染器进入gl
		// RENDERMODE_CONTINUOUSLY不停渲染
		// RENDERMODE_WHEN_DIRTY懒惰渲染，需要手动调用 glSurfaceView.requestRender() 才会进行更新
		mGlSurfaceView.setRenderMode(mGlSurfaceView.RENDERMODE_WHEN_DIRTY);// 设置渲染器模式
		mGlSurfaceView.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				autoFocus();
			}
		});

		mICamera = new ICamera();
		mDialogUtil = new DialogUtil(this);
		debugInfoText = (TextView) findViewById(R.id.opengl_layout_debugInfotext);
		AttriButetext = (TextView) findViewById(R.id.opengl_layout_AttriButetext);
		debugPrinttext = (TextView) findViewById(R.id.opengl_layout_debugPrinttext);
		if (isDebug)
			debugInfoText.setVisibility(View.VISIBLE);
		else
			debugInfoText.setVisibility(View.INVISIBLE);

		// 畫AR用
		imageParentLayout = (RelativeLayout)findViewById(R.id.parent_layout1);
		noseBitmap = BitmapFactory.decodeResource(getApplicationContext().getResources(),R.drawable.rbnose); // 在onCreate後才能拿到context
		bd =(BitmapDrawable) OpenglActivity.this.getResources().getDrawable(R.drawable.rbnose);

		// 拍照用
        snapshotButton = (Button)findViewById(R.id.button_snapshot);
        snapshotButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
				FaceArObj.takeSnapShot(faceARs,mostRecentPic,mICamera,OpenglActivity.this,mHandler,!isBackCamera);
            }
        });
	}

	/**
	 * 开始录制
	 */
	private void startRecorder() {
		if (isStartRecorder) {
			int Angle = 360 - mICamera.Angle;
			if (isBackCamera)
				Angle = mICamera.Angle;
			mediaRecorderUtil = new MediaRecorderUtil(this, mCamera, mICamera.cameraWidth, mICamera.cameraHeight);
			isStartRecorder = mediaRecorderUtil.prepareVideoRecorder(Angle);
			if (isStartRecorder) {
				boolean isRecordSucess = mediaRecorderUtil.start();
				if (isRecordSucess)
					mICamera.actionDetect(this);
				else
					mDialogUtil.showDialog(getResources().getString(R.string.no_record));
			}
		}
	}

	private void autoFocus() {
		if (mCamera != null && isBackCamera) {
			mCamera.cancelAutoFocus();
			Parameters parameters = mCamera.getParameters();
			parameters.setFocusMode(Parameters.FOCUS_MODE_AUTO);
			mCamera.setParameters(parameters);
			mCamera.autoFocus(null);
		}
	}

	private int Angle;

	@Override
	protected void onResume() {
		super.onResume();
		ConUtil.acquireWakeLock(this);
		startTime = System.currentTimeMillis();
		mCamera = mICamera.openCamera(isBackCamera, this, resolutionMap);
		if (mCamera != null) {
			Angle = 360 - mICamera.Angle;
			if (isBackCamera)
				Angle = mICamera.Angle;

			RelativeLayout.LayoutParams layout_params = mICamera.getLayoutParam();
			mGlSurfaceView.setLayoutParams(layout_params);

			int width = mICamera.cameraWidth;
			int height = mICamera.cameraHeight;

			int left = 0;
			int top = 0;
			int right = width;
			int bottom = height;
			if (isROIDetect) {
				float line = height * roi_ratio;
				left = (int) ((width - line) / 2.0f);
				top = (int) ((height - line) / 2.0f);
				right = width - left;
				bottom = height - top;
			}

			String errorCode = facepp.init(this, ConUtil.getFileContent(this, R.raw.megviifacepp_0_4_7_model));
			Facepp.FaceppConfig faceppConfig = facepp.getFaceppConfig();
			faceppConfig.interval = detection_interval;
			faceppConfig.minFaceSize = min_face_size;
			faceppConfig.roi_left = left;
			faceppConfig.roi_top = top;
			faceppConfig.roi_right = right;
			faceppConfig.roi_bottom = bottom;
			if (isOneFaceTrackig)
				faceppConfig.one_face_tracking = 1;
			else
				faceppConfig.one_face_tracking = 0;
			String[] array = getResources().getStringArray(R.array.trackig_mode_array);
			if (trackModel.equals(array[0]))
				faceppConfig.detectionMode = Facepp.FaceppConfig.DETECTION_MODE_TRACKING;
			else if (trackModel.equals(array[1]))
				faceppConfig.detectionMode = Facepp.FaceppConfig.DETECTION_MODE_TRACKING_ROBUST;
			else if (trackModel.equals(array[2]))
				faceppConfig.detectionMode = Facepp.FaceppConfig.DETECTION_MODE_TRACKING_FAST;

			facepp.setFaceppConfig(faceppConfig);
		} else {
			mDialogUtil.showDialog(getResources().getString(R.string.camera_error));
		}
	}

	private void setConfig(int rotation) {
		Facepp.FaceppConfig faceppConfig = facepp.getFaceppConfig();
		if (faceppConfig.rotation != rotation) {
			faceppConfig.rotation = rotation;
			facepp.setFaceppConfig(faceppConfig);
		}
	}

	/**
	 * 画绿色框
	 */
	private void drawShowRect() {
		mPointsMatrix.vertexBuffers = OpenGLDrawRect.drawCenterShowRect(isBackCamera, mICamera.cameraWidth,
				mICamera.cameraHeight, roi_ratio);
	}

	boolean isSuccess = false;
	float confidence;
	float pitch, yaw, roll;
	long startTime;
	long time_AgeGender_end = 0;
	String AttriButeStr = "";
	int rotation = Angle;

	@Override
	public void onPreviewFrame(final byte[] imgData, final Camera camera) {
		if (isSuccess)
			return;
		isSuccess = true;

        // 拍照用
        mostRecentPic = imgData;

		mHandler.post(new Runnable() {
			@Override
			public void run() {
				int width = mICamera.cameraWidth;
				int height = mICamera.cameraHeight;

				long faceDetectTime_action = System.currentTimeMillis();
				int orientation = sensorUtil.orientation;
				if (orientation == 0)
					rotation = Angle;
				else if (orientation == 1)
					rotation = 0;
				else if (orientation == 2)
					rotation = 180;
				else if (orientation == 3)
					rotation = 360 - Angle;

				setConfig(rotation);

				final Facepp.Face[] faces = facepp.detect(imgData, width, height, Facepp.IMAGEMODE_NV21);
				final long algorithmTime = System.currentTimeMillis() - faceDetectTime_action;

				if (faces != null) {
					long actionMaticsTime = System.currentTimeMillis();
					ArrayList<ArrayList> pointsOpengl = new ArrayList<ArrayList>();
					confidence = 0.0f;

					// 畫AR用
					if(faces.length > faceARs.size()){ // 如果偵測到的臉比AR物件數量還多
						for(int i = 0; i < faces.length-faceARs.size(); i++) {
							// AR用 new一個FaceArObj & 建構 然後加到ArrayList
							FaceArObj faceObj = new FaceArObj(OpenglActivity.this, OpenglActivity.this,
									imageParentLayout, R.drawable.main_scratch, R.drawable.debug_blue
									//,new int[]{R.drawable.main_scratch}
							);
							faceObj.init();
							Log.d("add 1 of faceARs","before add, faceARs size = "+faceARs.size());
							faceARs.add(faceObj);
						}

					}else if(faces.length < faceARs.size()){ // 若偵測到的臉比AR物件少
						for(int i = 0; i < faceARs.size()-faces.length; i++) {
							// AR用 先FaceArObj.removeImgView() 再從ArrayList刪FaceArObj
							faceARs.get(0).removeArImgViews();
							faceARs.remove(0);
							Log.d("after remove 1 faceAR","faceARs size = "+faceARs.size());
						}
					}
					if(NOSE_IMG_ACTIVATE) {
						if (faces.length > noseImgViews.size()) {// 創imageview
							for (int i = 0; i < faces.length - noseImgViews.size(); i++) {

								final ImageView noseIv = new ImageView(OpenglActivity.this);
								final TextView noseTv = new TextView(OpenglActivity.this);
								//noseIv.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT,RelativeLayout.LayoutParams.WRAP_CONTENT));
								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										//noseIv.setImageResource(R.drawable.rbnose); // 效能差
										//noseIv.setImageDrawable(getResources().getDrawable(R.drawable.rbnose)); // 一樣差, 還deprecated
										Log.d("noseImgView", "setImageBitmap and addView");
										noseIv.setImageBitmap(noseBitmap); // 貌似還是很差
										imageParentLayout.addView(noseIv); // 放imageView
										imageParentLayout.addView(noseTv); // 放debug用的textView
									}
								});
								noseImgViews.add(noseIv);
								noseTxtViews.add(noseTv);
							}
						} else if (faces.length < noseImgViews.size()) {// 刪imageView
							for (int i = 0; i < noseImgViews.size() - faces.length; i++) {

								final ImageView iv = noseImgViews.get(i);
								final TextView tv = noseTxtViews.get(i);
								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										imageParentLayout.removeView(iv);
										imageParentLayout.removeView(tv);
									}
								});
								noseImgViews.remove(0);
								noseTxtViews.remove(0);
							}
						}
					}
					if (faces.length >= 0) {
						for (int c = 0; c < faces.length; c++) {// 對於所有偵測到的臉
							if (is106Points)
								facepp.getLandmark(faces[c], Facepp.FPP_GET_LANDMARK106);
							 else
								facepp.getLandmark(faces[c], Facepp.FPP_GET_LANDMARK81);

							if (is3DPose) {
								facepp.get3DPose(faces[c]);
							}

							final Facepp.Face face = faces[c];

							if (isFaceProperty) { // 臉部屬性, 應該是因為沒接API 所以沒有此功能
								long time_AgeGender_action = System.currentTimeMillis();
								facepp.getAgeGender(faces[c]);
								time_AgeGender_end = System.currentTimeMillis() - time_AgeGender_action;
								String gender = "man";
								if (face.female > face.male)
									gender = "woman";
								AttriButeStr = "\nage: " + (int) Math.max(face.age, 1) + "\ngender: " + gender;
								Log.d("attribute",AttriButeStr);
							}

							pitch = faces[c].pitch;
							yaw = faces[c].yaw;
							roll = faces[c].roll;
							confidence = faces[c].confidence;

							if (orientation == 1 || orientation == 2) {
								width = mICamera.cameraHeight;
								height = mICamera.cameraWidth;
							}
							ArrayList<FloatBuffer> triangleVBList = new ArrayList<FloatBuffer>();
                            for (int i = 0; i < faces[c].points.length; i++) {// 對於臉上的每個部位點
								float x = (faces[c].points[i].x / height) * 2 - 1;
								if (isBackCamera)
									x = -x;
								float y = 1 - (faces[c].points[i].y / width) * 2;
								float[] pointf = new float[] { x, y, 0.0f };
								if (orientation == 1)
									pointf = new float[] { -y, x, 0.0f };
								if (orientation == 2)
									pointf = new float[] { y, -x, 0.0f };
								if (orientation == 3)
									pointf = new float[] { -x, -y, 0.0f };

								FloatBuffer fb = mCameraMatrix.floatBufferUtil(pointf);
                                //if(i!=64) // test 第k個點
								triangleVBList.add(fb);
							}

							pointsOpengl.add(triangleVBList);

							// AR用 faceArObj.drawFaceAR
							Log.d("before drawFaceAR","faceARs size = "+faceARs.size());
							if(faceARs.size()!=0) {
								faceARs.get(c).drawFaceAR(faces[c].points, mICamera, mGlSurfaceView, true);
							}


							if(NOSE_IMG_ACTIVATE) {
								final int tId = face.trackID;
								float noseLeft;
								float noseTop;
								float noseRight;
								float noseBot;
								float noseX = faces[c].points[34].x; // 以81個點來說 1= (圖)左眼左側,  34 = 鼻頭, 35 = 鼻底, 64 = 下巴, 62 = (圖)左臉, 63 = (圖)右臉
								noseX = width - noseX; // mirror 鏡像
								float noseY = faces[c].points[34].y;
								// faceWidth 和 faceHeight 要用距離算
								float faceWidth = (float) sqrt((face.points[63].x - face.points[62].x) * (face.points[63].x - face.points[62].x) + (face.points[63].y - face.points[62].y) * (face.points[63].y - face.points[62].y));
								float faceHeight = (float) sqrt((face.points[64].y - face.points[34].y) * (face.points[64].y - face.points[34].y) + (face.points[64].x - face.points[34].x) * (face.points[64].x - face.points[34].x)) * 2;

								// 設定鼻子寬高
								int noseImgInitHeight = bd.getBitmap().getHeight();
								int noseImgInitWidth = bd.getBitmap().getWidth();
								float noseWidth = faceWidth;
								float noseHeight = noseImgInitHeight * noseWidth / noseImgInitWidth;

							/*
                            switch(orientation){
                                case 1:
                                    float tmp = noseX;
                                    noseX = -noseY;
                                    noseY = tmp;
                                    break;
                                case 2:
                                    tmp = noseX;
                                    noseX = noseY;
                                    noseY = -tmp;
                                    break;
                                case 3:
                                    noseX = -noseX;
                                    noseY = -noseY;
                                    break;
                            }*/

								noseLeft = noseX - noseWidth / 2;
								noseRight = noseX + noseWidth / 2;
								noseTop = noseY - noseHeight / 2;
								noseBot = noseY + noseHeight / 2;

								// 設定比例&座標
								float camera2LayoutRatio = (float) mGlSurfaceView.getWidth() / (float) height; // 寬度比例(相機的height好像是橫的)
								float camera2LayoutHeightRatio = (float) mGlSurfaceView.getHeight() / (float) width; // 高度比例

								final float drawNoseX = (noseLeft - (width - height)) * camera2LayoutRatio;//*camera2LayoutRatio;
								final float drawNoseY = (noseTop) * camera2LayoutHeightRatio;//*camera2LayoutRatio;

								final float drawNoseWidth = noseWidth * camera2LayoutRatio;
								final float drawNoseHeight = noseImgInitHeight * drawNoseWidth / noseImgInitWidth;//noseHeight*camera2LayoutHeightRatio;
								//Log.d("widthRatio = "+camera2LayoutRatio,"drawNoseX = "+drawNoseX+", drawNoseY = "+drawNoseY);
								final TextView txtView = (TextView) findViewById(R.id.textView2);

								final ImageView noseImgView = noseImgViews.get(c);
								final TextView noseTxtView = noseTxtViews.get(c);

								// 計算傾斜角度
								noseX = (noseX - (width - height)) * camera2LayoutRatio;
								noseY *= camera2LayoutHeightRatio;
								float chinX = face.points[64].x;
								chinX = width - chinX; // mirror 鏡像
								chinX = (chinX - (width - height)) * camera2LayoutRatio;
								float chinY = face.points[64].y * camera2LayoutHeightRatio;

								float lenChinToNose = (float) sqrt((chinX - noseX) * (chinX - noseX) + (chinY - noseY) * (chinY - noseY)); // 下巴和鼻子的距離
								float sinAngle = (noseX - chinX) / lenChinToNose; // 大概是這樣算吧
								final float angle = (float) Math.toDegrees(Math.asin(sinAngle)); //Log.d("Draw/angle",angle+"");
								final float noseCenterX = noseX;// 旋轉中心點
								final float noseCenterY = noseY;


								runOnUiThread(new Runnable() {// 改變imageView位置
									@Override
									public void run() {

										//noseImgView.setImageResource(R.drawable.rbnose);
										RelativeLayout.LayoutParams imgParams = new RelativeLayout.LayoutParams((int) drawNoseWidth, (int) drawNoseHeight);
										noseImgView.setLayoutParams(imgParams);
										//RelativeLayout rLayout = (RelativeLayout)findViewById(R.id.r_layout1);
										//imageParentLayout.addView(noseImgView);

										noseImgView.setPivotX(noseCenterX - drawNoseX);// 這裡的座標是以imageView的左上角為基準, 而非螢幕左上角
										noseImgView.setPivotY(noseCenterY - drawNoseY);// 所以若耳朵旋轉中心則為 noseCenterY-drawEarY
										noseImgView.setRotation(angle);
										noseImgView.setX(drawNoseX);
										noseImgView.setY(drawNoseY);
										//*/
										// 文字跟隨
										noseTxtView.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT,
												RelativeLayout.LayoutParams.WRAP_CONTENT));
										noseTxtView.setX(drawNoseX);
										noseTxtView.setY(drawNoseY);
										noseTxtView.setTextSize(10);
										noseTxtView.setText("鼻子 " + tId + ":\ndraw: (" + drawNoseX + ", " + drawNoseY + ")" +
												"\nrotate: " + angle + ", center: (" + noseCenterX + "," + noseCenterY + ")" +
												"\ndraw: Width: " + drawNoseWidth + ", Height: " + drawNoseHeight);

										txtView.setText("鼻子 " + tId + ":\ndrawX: " + drawNoseX + "\t, drawY: " + drawNoseY +
												"\ndrawWidth: " + drawNoseWidth + "\t, drawHeight: " + drawNoseHeight +
												"\nrotate: " + angle + ", center: (" + noseCenterX + "," + noseCenterY + ")");
									}
								});

								float foreheadX = noseX;
								float foreheadY = noseY - faceHeight / 2;
								float earWidth = faceWidth;
								float earHeight = faceHeight / 2;
							/*drawTranslateImgview(earImgView,R.drawable.rbear,
									foreheadX,foreheadY,earWidth,earHeight,0,orientation,
									width,height,mGlSurfaceView.getWidth(),mGlSurfaceView.getHeight());
*/
							}
                        }
					} else {
						pitch = 0.0f;
						yaw = 0.0f;
						roll = 0.0f;
					}
					if (faces.length > 0 && is3DPose)
						mPointsMatrix.bottomVertexBuffer = OpenGLDrawRect.drawBottomShowRect(0.15f, 0, -0.7f, pitch,
								-yaw, roll, rotation);
					else
						mPointsMatrix.bottomVertexBuffer = null;
					synchronized (mPointsMatrix) {
						mPointsMatrix.points = pointsOpengl;
					}

					final long matrixTime = System.currentTimeMillis() - actionMaticsTime;
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							String logStr = "\ncameraWidth: " + mICamera.cameraWidth + "\ncameraHeight: "
									+ mICamera.cameraHeight + "\nalgorithmTime: " + algorithmTime + "ms"
									+ "\nmatrixTime: " + matrixTime + "\nconfidence:" + confidence;
							debugInfoText.setText(logStr);
							if (faces.length > 0 && isFaceProperty && AttriButeStr != null && AttriButeStr.length() > 0)
								AttriButetext.setText(AttriButeStr + "\nAgeGenderTime:" + time_AgeGender_end);
							else
								AttriButetext.setText("");
						}
					});
				}
				isSuccess = false;
				if (!isTiming) {
					timeHandle.sendEmptyMessage(1);
				}
			}
		});
	}

	@Override
	protected void onPause() {
		super.onPause();
		ConUtil.releaseWakeLock();
		if (mediaRecorderUtil != null) {
			mediaRecorderUtil.releaseMediaRecorder();
		}
		mICamera.closeCamera();
		mCamera = null;

		timeHandle.removeMessages(0);

		finish();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		facepp.release();
		if(noseBitmap !=null && !noseBitmap.isRecycled()){noseBitmap.recycle();}
	}

	private int mTextureID = -1;
	private SurfaceTexture mSurface;
	private CameraMatrix mCameraMatrix;
	private PointsMatrix mPointsMatrix;

	@Override
	public void onFrameAvailable(SurfaceTexture surfaceTexture) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		// 黑色背景
		GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

		mTextureID = OpenGLUtil.createTextureID();
		mSurface = new SurfaceTexture(mTextureID);
		// 这个接口就干了这么一件事，当有数据上来后会进到onFrameAvailable方法
		mSurface.setOnFrameAvailableListener(this);// 设置照相机有数据时进入
		mCameraMatrix = new CameraMatrix(mTextureID);
		mPointsMatrix = new PointsMatrix();
		mICamera.startPreview(mSurface); // 设置预览容器
		mICamera.actionDetect(this);
		if (isTiming) {
			timeHandle.sendEmptyMessageDelayed(0, printTime);
		}
		if (isROIDetect)
			drawShowRect();
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
		// 设置画面的大小
		GLES20.glViewport(0, 0, width, height);

		float ratio = (float) width / height;
		ratio = 1; // 这样OpenGL就可以按照屏幕框来画了，不是一个正方形了

		// this projection matrix is applied to object coordinates
		// in the onDrawFrame() method
		Matrix.frustumM(mProjMatrix, 0, -ratio, ratio, -1, 1, 3, 7);
		// Matrix.perspectiveM(mProjMatrix, 0, 0.382f, ratio, 3, 700);
	}

	private final float[] mMVPMatrix = new float[16];
	private final float[] mProjMatrix = new float[16];
	private final float[] mVMatrix = new float[16];
	private final float[] mRotationMatrix = new float[16];

	@Override
	public void onDrawFrame(GL10 gl) {
		final long actionTime = System.currentTimeMillis();
		// Log.w("ceshi", "onDrawFrame===");
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);// 清除屏幕和深度缓存
		float[] mtx = new float[16];
		mSurface.getTransformMatrix(mtx);
		mCameraMatrix.draw(mtx);
		// Set the camera position (View matrix)
		Matrix.setLookAtM(mVMatrix, 0, 0, 0, -3, 0f, 0f, 0f, 0f, 1f, 0f);

		// Calculate the projection and view transformation
		Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mVMatrix, 0);

		mPointsMatrix.draw(mMVPMatrix);

		if (isDebug) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					final long endTime = System.currentTimeMillis() - actionTime;
					debugPrinttext.setText("printTime: " + endTime);
				}
			});
		}
		mSurface.updateTexImage();// 更新image，会调用onFrameAvailable方法
	}

	Handler timeHandle = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 0:
				mGlSurfaceView.requestRender();// 发送去绘制照相机不断去回调
				timeHandle.sendEmptyMessageDelayed(0, printTime);
				break;
			case 1:
				mGlSurfaceView.requestRender();// 发送去绘制照相机不断去回调
				break;
			}
		}
	};

	/*
    public float convertDpToPixel(float dp){
        float px = dp * getDensity(this);
        return px;
    }
    public float convertPixelToDp(float px){
        float dp = px / getDensity(this);
        return dp;
    }

    public float getDensity(Context context){
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.density;
    }*/
	private void drawTranslateImgview(final ImageView imgView, final int drawableSource, float initX, float initY, float initWidth, float initHeight, float rotate,
									  int orientation, float cameraWidth, float cameraHeight, float surfaceWidth, float surfaceHeight){// cameraWidth應該要是較小的參數, 因此應該輸入相反?
		switch(orientation){
			case 1:
				float tmp = initX;
				initX = -initY;
				initY = tmp;
				break;
			case 2:
				tmp = initX;
				initX = initY;
				initY = -tmp;
				break;
			case 3:
				initX = -initX;
				initY = -initY;
				break;
		}
		float widthRatio = surfaceWidth/cameraWidth;
		float heightRatio = surfaceHeight/cameraHeight;
		final float drawY = (initY - initHeight/2) * heightRatio;
		final float drawX = ((initX - initWidth/2) - (cameraHeight-cameraWidth)) * widthRatio;
		final float drawWidth = initWidth*widthRatio;
		final float drawHeight = initHeight*heightRatio;

		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				imgView.setImageResource(drawableSource);
				ViewGroup.LayoutParams imgParams = imgView.getLayoutParams();
				Log.d("setParams","width = "+drawWidth+", height = "+drawHeight);
				imgParams.width = (int) drawWidth;
				imgParams.height = (int) drawHeight;
				imgView.setLayoutParams(imgParams);
				imgView.setX(drawX);
				imgView.setY(drawY);
				//txtView.setText("鼻子\ndrawX: "+drawNoseX+"\ndrawY: "+drawNoseY);
			}
		});
		//return new float[]{};
	};

}
