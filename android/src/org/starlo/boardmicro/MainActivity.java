package org.starlo.boardmicro;

import android.os.Bundle;
import android.app.Activity;
import android.webkit.WebView;
import android.widget.*;
import android.content.Intent;
import android.webkit.WebViewClient;
import android.view.*;
import com.dropbox.chooser.android.DbxChooser;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.util.Log;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.View.*;
import android.hardware.SensorManager;
import android.content.*;
import android.graphics.Matrix;

public abstract class MainActivity extends Activity implements SurfaceHolder.Callback, BoardMicroInterface{

	public static final String SEND_COMMAND_ACTION = "sendCommand";

	private static final int DBX_CHOOSER_REQUEST = 0;
	private static final String ASSET_URL = "file:///android_asset/avrcore.html";

	private SurfaceView mSurfaceView;
	private SurfaceHolder mHolder;
	private Bitmap mScaledBitmap;
	private Thread mRefreshThread = null;
	private GestureDetector mGestureDetector = null;

	private boolean mScreenDirty = true;
	private boolean mProgramEnded = false;
	private boolean mDropboxCalled = false;
	private boolean mPaused = false;

 	protected static final int DEBUG_COMMAND_REQUEST = 1;
	protected Bitmap mBitmap;
	protected int mScreenWidth;
	protected int mScreenHeight;
	protected WebView mBackgroundWebView;
	protected boolean mShouldToastResult = false;

	private int SCREEN_WIDTH;
	private int SCREEN_HEIGHT;
	private int[] mPixelArray;
	private int mLayout;

	private BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			sendDebugCommand(intent.getStringExtra("command"));
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(mLayout);
		setupUI();
		startBackgroundWebApp();
		IntentFilter filter = new IntentFilter();
		filter.addAction(SEND_COMMAND_ACTION);
		registerReceiver(receiver, filter);
		//WebView.setWebContentsDebuggingEnabled(true);
	}

	@Override
	protected void onResume() {
		mPaused = false;
		super.onResume();
	}

	@Override
	protected void onPause() {
		mPaused = true;
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		unregisterReceiver(receiver);
		super.onDestroy();
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if(resultCode != Activity.RESULT_OK)
			return;
		switch(requestCode)
		{
			case DBX_CHOOSER_REQUEST:
				final DbxChooser.Result result = new DbxChooser.Result(data);
				new DropboxTask(result.getLink().toString(), this).execute();
				break;
			case DEBUG_COMMAND_REQUEST:
				mShouldToastResult = true;
				sendDebugCommand(data.getStringExtra("command"));
				break;
		}
	}

	@Override
        public void writeToUARTBuffer(String buffer) {
		Log.v("UART", buffer);
		Toast.makeText(this, buffer, Toast.LENGTH_SHORT).show();
	}

	@Override
        public void setPixel(int x, int y, int color){
		mScreenDirty = true;
		mPixelArray[y*SCREEN_WIDTH+x] = color;
	}

	@Override
	public void startProcess(String javascriptUrl){
		try{
			mBackgroundWebView.loadUrl(javascriptUrl);
			mBackgroundWebView.loadUrl("javascript:engineInit()");
			mBackgroundWebView.loadUrl("javascript:exec()");
			startRefreshThread();
		}catch(Exception e){}
	}

	@Override
	public void updateADCRegister(final int value){
		if(mPaused)
			return;
                mSurfaceView.post(new Runnable(){
                        public void run(){
				mBackgroundWebView.loadUrl("javascript:writeADCDataRegister("+value+")");
                        }
                });
	}

	@Override
	public void sendDebugCommand(final String command){
		mBackgroundWebView.loadUrl("javascript:handleDebugCommandString('"+command+"')");
	}

	@Override
        public void endProgram(){
		mProgramEnded = true;
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		wipeScreen();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {}

	public abstract void handleButtonPress(View view);
	protected abstract Bitmap getScaledBitmap();
	protected abstract void filterOutUnsupportedPins();
	protected abstract SurfaceView getDisplay();
	protected abstract void startDebugActivity();

	protected void setConfiguration( int layout, int width, int height ){
		mLayout = layout;
		SCREEN_WIDTH = width;
		SCREEN_HEIGHT = height;
		mPixelArray = new int[SCREEN_WIDTH*SCREEN_HEIGHT];
	}

	private void startRefreshThread(){
		endProgram();
		mRefreshThread = new Thread(new Runnable(){
			public void run(){
				while(!mProgramEnded){
					refreshScreenLoop();
					try{
						Thread.yield();
					}catch(Exception e){}
				}
			}
		});
		int i = Short.MAX_VALUE*1000;
		while(i-- > 0)
			refreshScreenLoop();
		mProgramEnded = false;
		mRefreshThread.start();
	}

	private void wipeScreen(){
		wipeScreen(Color.BLACK);
	}

	private void wipeScreen(int color){
		for(int i = 0; i < SCREEN_HEIGHT; i++){
			for(int j = 0; j < SCREEN_WIDTH; j++){
				setPixel(j, i, color);
			}
		}
	}

	private void refreshScreenLoop(){
		if(mScreenDirty){
			mScreenDirty = false;
			if(mScaledBitmap != null)
				mScaledBitmap.recycle();
			Canvas canvas = mHolder.lockCanvas();
			if(canvas != null){
				canvas.drawColor(Color.BLACK);
				mBitmap.setPixels(mPixelArray, 0, SCREEN_WIDTH, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
				mScaledBitmap = getScaledBitmap();
				canvas.drawBitmap(mScaledBitmap, 0, 0, null);
				mHolder.unlockCanvasAndPost(canvas);
			}
		}
	}

	private void setupUI(){
		mSurfaceView = getDisplay();
		mHolder = mSurfaceView.getHolder();
		mHolder.addCallback(this);
		mBitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Config.ARGB_8888);
		Resources r = mSurfaceView.getResources();
		mScreenWidth =
			Float.valueOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, r.getConfiguration().screenWidthDp, r.getDisplayMetrics())).intValue();
		mScreenHeight =
			Float.valueOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, r.getConfiguration().screenHeightDp, r.getDisplayMetrics())).intValue();
		mBackgroundWebView = new WebView(this);
		mGestureDetector = new GestureDetector(getApplicationContext(),
			new GestureDetector.SimpleOnGestureListener() {
				@Override
				public boolean onDown(MotionEvent event){
					return true;
				}

				@Override
				public void onLongPress(MotionEvent event){
					mBackgroundWebView.loadUrl(ASSET_URL);
					new DbxChooser(DropboxConstants.API_KEY).forResultType(DbxChooser.ResultType.DIRECT_LINK).launch(MainActivity.this, DBX_CHOOSER_REQUEST);
				}

				@Override
				public boolean onDoubleTap(MotionEvent event){
					startDebugActivity();
					return true;
				}
			});
		mSurfaceView.setOnTouchListener(new OnTouchListener() {
			public boolean onTouch(View v, MotionEvent event) {
				return mGestureDetector.onTouchEvent(event);
			}
		});
		filterOutUnsupportedPins();
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}

	private void startBackgroundWebApp(){
		mBackgroundWebView.getSettings().setJavaScriptEnabled(true);
		mBackgroundWebView.loadUrl(ASSET_URL);
		mBackgroundWebView.addJavascriptInterface(
			new PichaiJavascriptInterface(this, new ADCSensorManager((SensorManager)getSystemService(Context.SENSOR_SERVICE))), "Android");
		mBackgroundWebView.getSettings().setUserAgentString(mBackgroundWebView.getSettings().getUserAgentString()+" NativeApp");
		mBackgroundWebView.setWebViewClient(new WebViewClient() {
			public void onPageFinished(WebView view, String loc) {
				if(DropboxConstants.USE_DROPBOX && !mDropboxCalled){
					mDropboxCalled = true;
					new DbxChooser(DropboxConstants.API_KEY).forResultType(DbxChooser.ResultType.DIRECT_LINK).launch(MainActivity.this, DBX_CHOOSER_REQUEST);
				}else if(!DropboxConstants.USE_DROPBOX){
					startProcess("javascript:loadDefault()");
				}
			}
		});
	}
}
