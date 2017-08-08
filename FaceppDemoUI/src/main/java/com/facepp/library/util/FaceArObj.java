package com.facepp.library.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.hardware.Camera;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.facepp.library.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import static java.lang.Math.sqrt;

/**
 * Created by johnnytu on 2017/8/4.
 */

public class FaceArObj {
    public final int AR_NOSE = 0;
    public final int AR_EAR = 1;
    public final int AR_SIZE = 2;
    public boolean DEBUG_TXT_VISIBLE = true;
    public boolean FACE_AR_ACTIVATE = true;

    // 不變的參數
    Context mContext;
    Activity mActivity; // 用來runOnUiThread
    RelativeLayout imgParentLayout; // AR要畫在這個Layout上
    final ImageView[] arImgViews; // 要畫的各部位AR
    final TextView[] arDebugTxtViews; // 各部位AR debug用
    TextView[] arTxtViews; // 各部位txtView TODO setVisibility(View.INVISIBLE)
    int[] arDrawableIds; // R.drawable.檔案名稱
    Bitmap[] arBitmaps; // 各部位AR要貼的圖

    // 會變的參數
    PointF[] facePoints;
    float[] arCenterXs; // TODO 畫圖座標要減去寬跟高的一半
    float[] arCenterYs;
    float[] arWidths; // TODO width跟height只需決定其中一個, 另一個用原圖比例算出
    float[] arHeights;
    float arRotateAngle;
    float arPivotX;
    float arPivotY;

    public FaceArObj(Context context, Activity activity, RelativeLayout parentL,
                     int noseDrawableId,// ImageView noseImageView,
                     int earDrawableId)//, ImageView earImageView)
    {
        mContext = context;
        mActivity = activity;
        imgParentLayout = parentL;
        // 初始化各部位 ImageView 和txtView
        arImgViews = new ImageView[AR_SIZE];//{noseImageView, earImageView};
        arImgViews[AR_NOSE] = new ImageView(context);
        arImgViews[AR_EAR] = new ImageView(context);

        arDebugTxtViews = new TextView[AR_SIZE];
        arDebugTxtViews[AR_NOSE] = new TextView(context);
        arDebugTxtViews[AR_EAR] = new TextView(context);

        // 載入R.drawable.檔名 資料
        arDrawableIds = new int[AR_SIZE];//{noseDrawableId, earDrawableId};
        arDrawableIds[AR_NOSE] = noseDrawableId;
        arDrawableIds[AR_EAR] = earDrawableId;
        // 載入drawable, 轉成bitmap
        arBitmaps = new Bitmap[AR_SIZE];
        arBitmaps[AR_NOSE] = decodeSampledBitmapFromResource(mContext.getResources(),arDrawableIds[AR_NOSE],(int)100,(int)100);
        arBitmaps[AR_EAR] = decodeSampledBitmapFromResource(mContext.getResources(),arDrawableIds[AR_EAR],(int)100,(int)100); // 更大貌似會OOM
        // 初始化array
        arCenterXs = new float[AR_SIZE];
        arCenterYs = new float[AR_SIZE];
        arWidths = new float[AR_SIZE];
        arHeights = new float[AR_SIZE];
    }
    public void init(){ // 初始化 不會變的參數, 所有ImageView 放到 Layout上
        /*
        for(int i = 0; i < arBitmaps.length; i++){
            arBitmaps[i] = BitmapFactory.decodeResource(mContext.getResources(), arDrawableIds[i]); // 在onCreate後才能拿到context, 畫Bitmap
        }*/
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //earImgView.setLayoutParams(new RelativeLayout.LayoutParams(1,1)); // 初始長寬設1
                //earImgView.setImageBitmap(arBitmaps[AR_EAR]);
                //imgParentLayout.addView(earImgView);
                for(int i = 0; i < arImgViews.length; i++){
                    //arImgViews[i].setLayoutParams(new RelativeLayout.LayoutParams(1,1)); // 初始長寬設1
/*
                    arImgViews[i].setImageBitmap(arBitmaps[i]);
                    imgParentLayout.addView(arImgViews[i]);
                    arBitmaps[i].recycle();
                    arBitmaps[i] = null;
                    */
                    imgParentLayout.addView(arImgViews[i]);
                    arImgViews[i].setImageBitmap(arBitmaps[i]);
                    imgParentLayout.addView(arDebugTxtViews[i]);
                }
                //TODO 改成全部AR
                Log.d("FaceArObj","arImageView setImageBitmap");
                /*
                arImgViews[AR_EAR].setImageBitmap(arBitmaps[AR_EAR]);
                arImgViews[AR_NOSE].setImageBitmap(arBitmaps[AR_NOSE]);
                imgParentLayout.addView(arDebugTxtViews[AR_EAR]);
                imgParentLayout.addView*/

            }
        });
    }
    public void drawFaceAR(PointF[] fPoints, ICamera ca, GLSurfaceView glSurfaceView,
                          boolean isMirror){ // 多次call tuneAR
        facePoints = fPoints;
        // 以81個點來說
        // 1= (圖)左眼左側,
        // 34 = 鼻頭, 35 = 鼻底, 64 = 下巴,
        // 62 = (圖)左臉, 63 = (圖)右臉,
        // 19 = (圖)左眉右側, 26 = (圖)右眉左側
        // 40 = (圖)鼻子左側, 41 = (圖)鼻子右側
        // 所有AR共用的參數
        float noseX = fPoints[34].x;
        float noseY = fPoints[34].y;
        float chinX = fPoints[64].x;
        float chinY = fPoints[64].y;
        float lenChinToNose = (float)sqrt((chinX-noseX)*(chinX-noseX)+(chinY-noseY)*(chinY-noseY)); // 下巴和鼻子的距離
        float sinAngle = (noseX-chinX)/lenChinToNose; // 大概是這樣算吧
        final float rAngle = (float)Math.toDegrees(Math.asin(sinAngle)); //Log.d("Draw/angle",angle+"");
        arRotateAngle = rAngle;
        float initPivotX = noseX;
        float initPivotY = noseY;
        arPivotX = noseX;
        arPivotY = noseY;

        // 畫耳朵

        float faceWidth = (float) sqrt((fPoints[63].x - fPoints[62].x) * (fPoints[63].x - fPoints[62].x) + (fPoints[63].y - fPoints[62].y) * (fPoints[63].y - fPoints[62].y));
        float faceHeight = //(float) sqrt((fPoints[64].y - fPoints[34].y) * (fPoints[64].y - fPoints[34].y) + (fPoints[64].x - fPoints[34].x) * (fPoints[64].x - fPoints[34].x)) * 2;
                (float) sqrt((fPoints[19].y - fPoints[40].y) * (fPoints[19].y - fPoints[40].y) + (fPoints[19].x - fPoints[40].x) * (fPoints[19].x - fPoints[40].x)) * 3; // 左鼻&左眉距離 * 3
        float initEarX = (fPoints[62].x + fPoints[63].x) / 2;
        float initEarY = fPoints[64].y - faceHeight;
            // = fPoints[34].y - faceHeight/3; // 鼻頭座標 - (鼻子到眉毛的距離)

        float initEarWidth = faceWidth;
        //arWidths[AR_EAR] = initEarWidth;
        float initNoseWidth = faceWidth/2;
        //arWidths[AR_NOSE] = initNoseWidth;
        //
        tuneAR(ca,glSurfaceView,AR_EAR,isMirror,rAngle,initPivotX,initPivotY,
                initEarX,initEarY,initEarWidth);
        tuneAR(ca,glSurfaceView,AR_NOSE,isMirror,rAngle,initPivotX,initPivotY,
                noseX,noseY,initNoseWidth);

    }



    //  本function只畫一個imageView 然後在drawAll裡面多次call
    private void tuneAR(ICamera ca, GLSurfaceView glSurfaceView, final int arId, //
                        boolean isMirror, float rotateAngle, float initPivotX, float initPivotY,
                        //float noseInitX, float noseInitY, float noseInitWidth,
                        float imgInitX, float imgInitY, float imgInitWidth)
    { // 變更ImageView座標&角度&大小
        /**
         *                    @param cameraWidth 在輸入cameraWidth跟Height時, 要把mICamera.getHeight當成Width, 反之亦然, 因為mICamera return的是顛倒的
         *                    @param cameraHeight 在輸入cameraWidth跟Height時, 要把mICamera.getWidth當成Height, 反之亦然, 因為mICamera它return的是顛倒的
         *                    @param imgInitWidth 只需要設定寬度, draw會依原圖比例換算高度
         *                    @param initPivotX 旋轉中心點初始座標
         **/
        //arCenterXs[AR_NOSE] = noseInitX;
        //arCenterYs[AR_NOSE] = noseInitY;
        //arWidths[AR_NOSE] = noseInitWidth;
        arCenterXs[arId] = imgInitX;
        arCenterYs[arId] = imgInitY;
        arWidths[arId] = imgInitWidth;
        float imgInitHeight = arBitmaps[arId].getHeight() * imgInitWidth/arBitmaps[arId].getWidth();
        arHeights[arId] = imgInitHeight;
        // 計算比例
        float cameraWidth = ca.cameraHeight, cameraHeight = ca.cameraWidth; // 這邊是故意打反的, 因為return的cameraHeight才是左右寬度
        float surfaceWidth = glSurfaceView.getWidth(), surfaceHeight = glSurfaceView.getHeight(); // 得到surface寬高
        float ca2LayoutWidthRatio = surfaceWidth/cameraWidth;
        float ca2LayoutHeightRatio = surfaceHeight/cameraHeight;

        //float imgInitHeight = arBitmaps[arId].getHeight() * imgInitWidth/arBitmaps[arId].getWidth(); // 根據寬度和原圖比例計算高度
        if(isMirror){ // 鏡像mirror
            rotateAngle = -rotateAngle;
            initPivotX = cameraWidth - initPivotX;
            imgInitX = cameraWidth - imgInitX;
            //noseInitX = cameraWidth - noseInitX;
        }
        // 換算成 SurfaceView的座標
        final float drawArX = (imgInitX - imgInitWidth/2) * ca2LayoutWidthRatio;
        final float drawArY = (imgInitY - imgInitHeight/2) * ca2LayoutHeightRatio;
        final float drawArWidth = imgInitWidth * ca2LayoutWidthRatio;
        final float drawArHeight = arBitmaps[arId].getHeight() * drawArWidth/arBitmaps[arId].getWidth(); // 依據想要的寬度和原圖比例計算高度
        final float drawPivotX = initPivotX * ca2LayoutWidthRatio;
        final float drawPivotY = initPivotY * ca2LayoutHeightRatio;
        final float drawRotateAngle = rotateAngle;

        // 把這個AR畫上去
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                RelativeLayout.LayoutParams imgParams = new RelativeLayout.LayoutParams((int)drawArWidth, (int)drawArHeight);
                if(FACE_AR_ACTIVATE) {
                    if (arBitmaps[arId] != null && !arBitmaps[arId].isRecycled()) { // 為了避免 trying to use a recycled bitmap ????????????
                        arImgViews[arId].setLayoutParams(imgParams);
                        arImgViews[arId].setPivotX(drawPivotX - drawArX);// 這裡的座標是以imageView的左上角為基準, 而非螢幕左上角
                        arImgViews[arId].setPivotY(drawPivotY - drawArY);// 所以旋轉中心座標要扣掉畫imageView的座標
                        arImgViews[arId].setRotation(drawRotateAngle);
                        arImgViews[arId].setX(drawArX);
                        arImgViews[arId].setY(drawArY);
                        Log.d("FaceArObj", "after setImageBitmap");
                        //arImgViews[arId].setImageResource(arDrawableIds[arId]);
                    }
                }
                arDebugTxtViews[arId].setText("AR "+arId+"of Face\ndrawWidth: "+(int)drawArWidth+"\tdrawHeight: "+(int)drawArHeight);
                arDebugTxtViews[arId].setX(drawArX);
                arDebugTxtViews[arId].setY(drawArY);
                if(!DEBUG_TXT_VISIBLE){
                    arDebugTxtViews[arId].setVisibility(View.INVISIBLE);
                }
            }
        });

    }
    public void removeArImgViews(){ // 刪imageView & recycle Bitmap
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                 // 全部AR
                for(int i = 0; i < arImgViews.length; i++) { // 移除這張臉的所有AR
                    imgParentLayout.removeView(arImgViews[i]);
                    imgParentLayout.removeView(arDebugTxtViews[i]);
                    Log.d("FaceArObj","after remove ImgView from layout");

                    // 為了避免 try to use recycled bitmap 而把下面的code也放到runOnUiThread裡面
                    arImgViews[i] = null;
                    Log.d("FaceArObj","after imageView assigned null");
                    if(arBitmaps[i]!=null && !arBitmaps[i].isRecycled()) {
                        arBitmaps[i].recycle();
                        Log.d("FaceArObj","after recycle arBitmap");
                    }
                }

            }
        });


    }
    // 拍照用
    public static void takeSnapShot(final ArrayList<FaceArObj> allFaces, byte[] imgByte, ICamera ica, Activity activity,Handler handler){
        boolean isFrontCamera = true;
        boolean isTestImg = true;
        Camera camera = ica.mCamera;
        //Log.d("snapshot","camera = "+(camera==null?"null":"notNull"));
        final Bitmap combineBitmap = ica.getBitMap(imgByte,camera,isFrontCamera); // 先畫上照片bitmap
        Log.d("snapshot","picBitmap "+combineBitmap.getWidth()+"*"+combineBitmap.getHeight());
        final Canvas combineCanvas = new Canvas(combineBitmap);
        combineCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.DST); // 只設定顏色的話預設就為PorterDuff.Mode.SRC_OVER 貌似改善黑畫面

        handler.post(new Runnable() {
            @Override
            public void run() {
                if(allFaces.size()>0) {// 若有偵測到臉
                    for (FaceArObj faceArObj : allFaces) { // 對於每一張臉
                        for (int i = 0; i < faceArObj.AR_SIZE; i++) { // 對於臉上的每個AR
                            Bitmap arBitmap = faceArObj.arBitmaps[i];
                            Bitmap arBitmap2 = Bitmap.createScaledBitmap(arBitmap,(int)faceArObj.arWidths[i],(int)faceArObj.arHeights[i],false);
//                            combineCanvas.drawBitmap(arBitmap2,
//                                    faceArObj.arCenterXs[i] - faceArObj.arWidths[i]/2,
//                                    faceArObj.arCenterYs[i] - faceArObj.arHeights[i]/2,
//                                    null);

                            drawRotateBitmapByCenter(combineCanvas,null,arBitmap2, faceArObj.arRotateAngle,
                                    faceArObj.arCenterXs[i] - faceArObj.arWidths[i]/2,
                                    faceArObj.arCenterYs[i] - faceArObj.arHeights[i]/2,
                                    faceArObj.arPivotX,faceArObj.arPivotY);

                            arBitmap2.recycle();
                        }
                    }
                }else{ // TODO 直接拍照

                }
                // 將bitmap存到檔案
                SimpleDateFormat sdFormat = new SimpleDateFormat("yyyyMMdd HHmmss");
                Date date = new Date();
                String strDate = sdFormat.format(date);
                saveBitmapToInternalStorage(allFaces.get(0).mContext,combineBitmap,strDate);
            }
        });






        if(isTestImg){
            // 因為貌似是在main thread裡面call這個function所以不用runOnUiThread
            ImageView testImgView = (ImageView)activity.findViewById(R.id.opengl_image);
            testImgView.setImageBitmap(combineBitmap);
        }
        //picBitmap.recycle();

    }
    private static void drawRotateBitmapByCenter(Canvas canvas, Paint paint, Bitmap bitmap,
                                          float rotation, float posX, float posY, float centerX, float centerY) {// 旋轉bitmap本身
        //if(mirrorPoints){posX=config.imageWidth-posX;}
        Matrix matrix = new Matrix();
        matrix.postTranslate(posX, posY);
        matrix.postRotate(rotation,centerX,centerY);
        //matrix.postTranslate(0,(float)(bitmap.getWidth()*sin(-rotation)));// 拍照的 旋轉角度修正
        canvas.drawBitmap(bitmap, matrix, paint);
        //canvas.drawBitmap(bitmap,posX,posY,null); 怒不旋轉
    }
    private static void saveBitmapToInternalStorage(@NonNull final Context context, @NonNull final Bitmap bitmapImage, @NonNull final String fileName) {

        // path to /data/data/yourapp/app_data/images
        String LOG_TAG = "saveBitmapToInternalStorage";
        File directory = context.getDir("images", Context.MODE_PRIVATE);

        // File location to save image
        File imagePath = new File(directory, fileName);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(imagePath);

            // Use the compress method on the BitMap object to write image to the OutputStream
            bitmapImage.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, "Exception while trying to save file to internal storage: " + imagePath, e);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Exception while trying to flush the output stream", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Exception wile trying to close file output stream.", e);
                }
            }
        }
    }

    private static Bitmap decodeSampledBitmapFromResource(Resources res, int resId,
                                                         int reqWidth, int reqHeight) {
        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options); // 這行到底幹嘛用的

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);
    }
    private static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }
}
