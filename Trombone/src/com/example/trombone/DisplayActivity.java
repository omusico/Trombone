package com.example.trombone;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import android.app.ActionBar.LayoutParams;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;
import ca.uol.aig.fftpack.RealDoubleFFT;
import classes.CalibrationData;
import classes.History;
import classes.Memo;
import classes.MusicSheet;
import classes.Note;
import db.DBHelper;

import static classes.Constants.*;

public class DisplayActivity extends Activity {
	boolean debugMode = false;
	boolean feedbackOn = false;
	
	// music sheet information
	private int musicSheetId;
	private int calibId;
	private int pageNum = 1;  // TODO : page related works
	int lastNoteIndex;
	int currentPosition = 0;
	private long prevRecognitionTime;
	
	private TextView selectedMemo;	// for memo modify
	private ArrayList<Memo> memoList = new ArrayList<Memo>();
	private ArrayList<Note> noteList = new ArrayList<Note>();

	// db helper
	DBHelper dbhelper = new DBHelper(this);

	// handling long touch for memo addition
	private float mLastMotionX = 0;
	private float mLastMotionY = 0;
	private int mTouchSlop;
	private boolean mHasPerformedLongPress;
	private CheckForLongPress mPendingCheckForLongPress;
	private Handler mHandler = null;

	private RealDoubleFFT transformer;
	Button startStopButton;
	Button prevButton, nextButton;
	boolean started = false;
	int sampleSize = 10;
	int sampleCount = 0;

	double[][][] calib_data = new double[3][12][blockSize + 1]; // 3,4,5 octave
	double[][] calibPitches = new double[3][12]; // 3,4,5 octave

	double[] ref_pitches;

	int currentCount = 0;
	int currentError = 0;

	RecordAudio recordTask;

	// TODO : collect view variables in here.
	TextView pageNumView;
	ImageView trackingView, trackingDebugView;
	ImageView currentSpec;
	Bitmap curBitmap, rec1Bitmap, rec2Bitmap;
	Canvas curCanvas, rec1Canvas, rec2Canvas;
	Paint paint = new Paint();

	int width, height;
	float ratio = 1;
	int bar_length = 12; 
	int keyNumber = 0;
	
	int[] yPositions = yPosition;
	
	// drawing related.
	int side_padding = 40;
	int top_padding = 80;
	int space_five_line = 150;
	double dx;

	TextView resultText, debugText;

	double[][] toTransformSample = new double[blockSize * 2 + 1][sampleSize];

	MusicSheet music_sheet;
	ArrayList<ImageView> noteViews =  new ArrayList<ImageView>();

	boolean [] matches = new boolean[11];
	double [] scores = new double[11];
	double [] errors = new double[11];
	double [] factors = {0,0,0,0,0,1,0.7,0.4,0.2,0.1,0.1};
	
	double tracking_velocity;
	double tracking_x;   // XXX : necessary???
	double tracking_y;
	long tracking_prev_time = -1;
	long matched_time = -1;
	
	int num_of_note = 0;
	int num_of_correct = 0;
	int num_of_mistake = 0;
	
	@Override
	protected void onStop(){
		if (started) {
			started = false;
			recordTask.cancel(true);			
		}
		
		// XXX : This is temporary.
		// This history construction must be done when play is end.
		Date cDate = new Date();
		String fDate = new SimpleDateFormat("yyyy-MM-dd").format(cDate);
		int score = (int)((float)num_of_correct/num_of_note*100);
		History history = new History(-1, fDate, Math.min(score, 100), musicSheetId);
		dbhelper.addHistory(history);
		
		super.onStop();
	}

	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		requestWindowFeature(Window.FEATURE_NO_TITLE);  
		//set up full screen
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,   
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.activity_display);

		initialize();
		keyNumber = music_sheet.getKeyNumber();
		if(keyNumber<0)
			yPositions=yPosition_flat;
		bar_length = music_sheet.getBeat();		
		dx = ((nexus7_width - 2*side_padding - 100) / 2) / (double) bar_length; 
		
		drawBackground();

		displayMusicSheet(pageNum);
		
		
		for (int pg =1; pg<=music_sheet.getPages(); pg++)
		{
			num_of_note += music_sheet.getNotes(pg).size();
		}
		if (debugMode) debugText.setText(num_of_note+"");		
		
		prevButton = (Button) findViewById(R.id.prevButton);
		if(pageNum<=1) prevButton.setClickable(false);
		else prevButton.setClickable(true);
		prevButton.setOnClickListener(new Button.OnClickListener() {
			public void onClick(View v) {
				updatePage(pageNum - 1);
			}
		});
		
		nextButton = (Button) findViewById(R.id.nextButton);
		if(pageNum+1>=music_sheet.getPages()) nextButton.setClickable(false);
		else nextButton.setClickable(true);
		nextButton.setOnClickListener(new Button.OnClickListener() {
			public void onClick(View v) {
				updatePage(pageNum + 1);	
			}
		});
		
		// start button
		startStopButton = (Button) findViewById(R.id.StartStopButton);
		startStopButton.setOnClickListener(new Button.OnClickListener() {
			public void onClick(View v) {
				if (started) {
					started = false;
					startStopButton.setText("Start");
					recordTask.cancel(true);
					currentPosition = 0;
					scores = new double[11];
					errors = new double[11];
					currentCount = 0;
					currentError = 0;
					tracking_velocity = 0;
				} else {
					started = true;
					startStopButton.setText("Stop");
					recordTask = new RecordAudio();
					recordTask.execute();		// thread call
					
					//Date temp = new Date();
					prevRecognitionTime = 0;// temp.getTime();
				}
			}
		});
		
		CheckBox checkbox = (CheckBox) findViewById(R.id.feedbackCheckBox);
		checkbox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
			
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (buttonView.getId() == R.id.feedbackCheckBox) {
					if (isChecked) feedbackOn = true;
					else feedbackOn = false;
				}
			}
		});
		
		transformer = new RealDoubleFFT(blockSize * 2 + 1);
		
		// Capture musicsheet for preview
		String directoryPath = "/storage/emulated/0/DCIM/TROMBONE_PREVIEW/";
		File directory = new File(directoryPath);
		if ( !directory.isDirectory() )
			directory.mkdirs();
		
		Log.d("for captrue", "capture make directry");
		String previewPath = "/storage/emulated/0/DCIM/TROMBONE_PREVIEW/" + musicSheetId + ".png";
		File preview = new File(previewPath);
		if ( preview != null && preview.exists() )
		{
			Log.d("for captrue", "capture file exist");
		}
		else
		{
			Log.d("for captrue", "capture file does not exist");
			capturePreview();
		}
	}
	
	private void FeedbackVelocity(int prev, int curr) {
		num_of_correct ++;
		if(debugMode) debugText.setText(num_of_correct+"/"+num_of_note+" "+(int)((float)num_of_correct/num_of_note*100)+" ");
		trackingView.setVisibility(View.VISIBLE);
		Date temp = new Date();
		long currentRecognitionTime = temp.getTime();
		long deltaTime = currentRecognitionTime - prevRecognitionTime;
		matched_time = temp.getTime();
		scores = new double[11];
		errors = new double[11];
		try {
			int total_beat = 0;
			String s="";
			for (int i = prev; i<curr; i++) {
				total_beat += music_sheet.getNote(pageNum, i).getBeat();
			}
			double modifiedVelocity = total_beat / (double) deltaTime;
			
			if(tracking_velocity < 1/10000) tracking_velocity =  modifiedVelocity * 0.9;
			tracking_velocity = tracking_velocity * 0.4 + modifiedVelocity * 0.6;
			
			prevRecognitionTime = currentRecognitionTime;
		} catch (Exception e) {
			tracking_velocity = 1/5000;
		}
	}
	
	private void capturePreview() {
		Log.d("for captrue", "capture capturePreview");
		FrameLayout screen = (FrameLayout) findViewById(R.id.music_sheet);
		screen.setDrawingCacheEnabled(true);
		screen.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
		screen.layout(0, 0, screen.getMeasuredWidth(), screen.getMeasuredHeight()/5*3); 
		screen.buildDrawingCache();
		
		Bitmap bm = Bitmap.createBitmap(screen.getDrawingCache());
		screen.destroyDrawingCache();
		screen.setDrawingCacheEnabled(false);
		
		savePreview(bm);
	}
	
	private void savePreview(Bitmap bm) {
		Log.d("for captrue", "capture savePreview");
		FileOutputStream stream;
		String directoryPath = "/storage/emulated/0/DCIM/TROMBONE_PREVIEW/";
		String path = musicSheetId + ".png";
		
		try {
			File file = new File(directoryPath, path);
			file.createNewFile();
			Log.d("for captrue", file.getAbsolutePath());
	
			stream = new FileOutputStream(file);
			bm.compress(Bitmap.CompressFormat.PNG, 100, stream);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
		
	// set initial data
	private void initialize() {
		// touch handler
		mHandler = new Handler();
		mTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
		
		// spectrum
		currentSpec = (ImageView) findViewById(R.id.CurrentSpectrum);
		curBitmap = Bitmap.createBitmap((int) blockSize, (int) 100,
				Bitmap.Config.ARGB_8888);
		curCanvas = new Canvas(curBitmap);
		currentSpec.setImageBitmap(curBitmap);
		
		// view binding
		resultText = (TextView) findViewById(R.id.resultText);
		debugText = (TextView) findViewById(R.id.debugText);
		pageNumView = (TextView) findViewById(R.id.page_number);

		// set music sheet
		musicSheetId = getIntent().getIntExtra("musicsheet_id", -1); 
		music_sheet = dbhelper.getMusicSheet(musicSheetId);
		TextView titleView = (TextView) findViewById(R.id.music_sheet_title);
		titleView.setText(music_sheet.getName());

		// tracking bar (with velocity)
		trackingView = new ImageView(getBaseContext());
		Bitmap trackingBm = Bitmap.createBitmap((int) 40, (int) 120,
				Bitmap.Config.ARGB_8888);
		Canvas trackingCanvas = new Canvas(trackingBm);
		trackingView.setImageBitmap(trackingBm);

		trackingCanvas.drawColor(Color.LTGRAY);

		trackingView.setLayoutParams(new LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		trackingView.setScaleType(ScaleType.MATRIX);
		FrameLayout l = (FrameLayout) findViewById(R.id.music_sheet);
		l.addView(trackingView);
		
		// tracking bar (for debug)
		trackingDebugView = new ImageView(getBaseContext());
		Bitmap trackingDebugBm = Bitmap.createBitmap((int) 40, (int) 120,
				Bitmap.Config.ARGB_8888);
		Canvas trackingDebugCanvas = new Canvas(trackingDebugBm);
		trackingDebugView.setImageBitmap(trackingDebugBm);

		trackingDebugCanvas.drawColor(Color.CYAN);

		trackingDebugView.setLayoutParams(new LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		trackingDebugView.setScaleType(ScaleType.MATRIX);
		l.addView(trackingDebugView);
		
		if (!debugMode) trackingDebugView.setVisibility(View.GONE);
		
		updatePage(1);	// first page

		// set calibration data
		calibId = getIntent().getIntExtra("calib_id2play", -1);
		try {
			CalibrationData cd = dbhelper.getCalibrationData(calibId);    			
			FileInputStream fis = new FileInputStream(cd.getFile_path());
			ObjectInputStream iis = new ObjectInputStream(fis);
			calib_data = (double[][][]) iis.readObject();
			iis.close();
			
			FileInputStream fis2 = new FileInputStream(cd.getFile_path2());
			ObjectInputStream iis2 = new ObjectInputStream(fis2);
			calibPitches = (double[][]) iis2.readObject();
			iis2.close();
			
		}catch (Exception e) {
			Log.d("ccccc", "exception : " + e.toString());
		} 
		
		tracking_velocity = 1 / 5000; // �λ뜃由겼첎占�
		}
	
	private void drawBackground() {
		// scale layout for multiple devices
		// get dimension of device
		Display display = getWindowManager().getDefaultDisplay();
		Point size = new Point();
		display.getSize(size);
		width = size.x;
		height = size.y;

		getWindow().setLayout(nexus7_width, nexus7_height);

		FrameLayout mainView = (FrameLayout)
				findViewById(R.id.music_sheet_background);

		ratio = (float)width/nexus7_width;

		if (ratio < 1) {
			mainView.setScaleX((float) ratio);
			mainView.setScaleY((float) ratio);
			mainView.setPivotX(0.0f);
			mainView.setPivotY(0.0f);
		}
		
		// now draw background
		FrameLayout l = (FrameLayout) findViewById(R.id.music_sheet);

		Paint paint = new Paint();
		paint.setColor(Color.BLACK);

		// Display music sheet
		int y = top_padding;
		int count = 0;
		while (count++ < 3) {
			ImageView fiveLine = new ImageView(getBaseContext());
			Bitmap bitmap = Bitmap.createBitmap((int) nexus7_width, (int) space_five_line,
					Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap);
			fiveLine.setImageBitmap(bitmap);

			int startPosition = 19; 
			int interval = 20;
			if(ratio<1) interval = 22;

			for (int i = 0; i <5; i ++)
				canvas.drawLine(side_padding, startPosition+i*interval, 
						nexus7_width - side_padding, startPosition+i*interval, paint);

			canvas.drawLine((int) ((nexus7_width - side_padding + 140) / 2), startPosition,
					(int) ((nexus7_width - side_padding + 140) / 2), 
					startPosition+4*interval, paint);

			canvas.drawLine(nexus7_width - side_padding, startPosition, 
					nexus7_width - side_padding, 
					startPosition+4*interval, paint);

			fiveLine.setLayoutParams(new LayoutParams(
					LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
			fiveLine.setPadding(0, y, 0, 0);
			fiveLine.setScaleType(ScaleType.MATRIX);

			l.addView(fiveLine);

			ImageView clef = new ImageView(getBaseContext());
			Bitmap bm = BitmapFactory.decodeResource(getResources(),
					R.drawable.high);
			clef.setImageBitmap(bm);
			clef.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
					LayoutParams.WRAP_CONTENT));
			clef.setPadding(side_padding, y, 0, 0);

			Matrix m = new Matrix();
			m.postScale((float) 0.24, (float) 0.24);
			clef.setScaleType(ScaleType.MATRIX);
			clef.setImageMatrix(m);

			l.addView(clef);
			
			if(keyNumber<0)
			{
				int[] flat_position = {7,10,6,9,5,8,4};
				for (int i=0; i<(keyNumber*-1) && i<7; i++)
				{
					ImageView iv = new ImageView(getBaseContext());
					Bitmap btm = BitmapFactory.decodeResource(getResources(),
							R.drawable.flat);
					iv.setImageBitmap(btm);
					iv.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
							LayoutParams.WRAP_CONTENT));
					iv.setPadding(side_padding+8*i+50, y+flat_position[i]*-10 +105, 0, 0);

					Matrix mat = new Matrix();
					mat.postScale((float) 0.17, (float) 0.17);
					iv.setScaleType(ScaleType.MATRIX);
					iv.setImageMatrix(mat);

					l.addView(iv);
				}
				
			}
			else if(keyNumber>0)
			{
				int[] sharp_position = {11,8,12,9,6,10,7};
				for (int i=0; i<(keyNumber) && i<7; i++)
				{
					ImageView iv = new ImageView(getBaseContext());
					Bitmap btm = BitmapFactory.decodeResource(getResources(),
							R.drawable.sharp);
					iv.setImageBitmap(btm);
					iv.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
							LayoutParams.WRAP_CONTENT));
					iv.setPadding(side_padding+8*i+50, y+sharp_position[i]*-10 +110, 0, 0);

					Matrix mat = new Matrix();
					mat.postScale((float) 0.14, (float) 0.14);
					iv.setScaleType(ScaleType.MATRIX);
					iv.setImageMatrix(mat);

					l.addView(iv);
				}
			}
			y += space_five_line;
		}
	}
	
	private void showMemos(ArrayList<Memo> memos) {
		for (Memo memo : memos) {
			FrameLayout f = (FrameLayout) findViewById(R.id.music_sheet_background);

			TextView memoView = new TextView(this);
			memoView.setText(memo.getContent());
			memoView.setLayoutParams(new LayoutParams(
					LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
			memoView.setX(memo.getX());
			memoView.setY(memo.getY());
			memoView.setTextColor(Color.argb(memo.getOpacity(), 255, 0, 0));
			memoView.setTextSize(30);
			
			memo.setTv(memoView);
			
			memoView.setOnClickListener(new View.OnClickListener() {

				@Override
				public void onClick(View v) {
					selectedMemo = (TextView) v;
					
					Intent foo = new Intent(DisplayActivity.this, TextEntryActivity.class);
					foo.putExtra("value", ((TextView) v).getText().toString());
					foo.putExtra("deletable", true);
					DisplayActivity.this.startActivityForResult(foo, MEMO_MODIFY);
				}
			});

			f.addView(memoView);
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {

		switch (event.getAction()) {

		case MotionEvent.ACTION_DOWN:
			mLastMotionX = event.getX();
			mLastMotionY = event.getY();   // �좎럩伊숂뙴�묒삕占쎌슜�삣뜝�뚮듌占쏙옙�좎럩伊숂뙴�묒삕占쎈뜇���좎럩伊숂뙴�묒삕占쎌슜�삣뜝�뚮듌占쏙옙
			mHasPerformedLongPress = false;   

			postCheckForLongClick(0);     //  Long click message �좎럩伊숂뙴�묒삕占쎌슜�삣뜝�뚮듌占쏙옙
			break;

		case MotionEvent.ACTION_MOVE:
			final float x = event.getX();
			final float y = event.getY();
			final int deltaX = Math.abs((int) (mLastMotionX - x));
			final int deltaY = Math.abs((int) (mLastMotionY - y));

			if (deltaX >= mTouchSlop || deltaY >= mTouchSlop) {
				if (!mHasPerformedLongPress) {
					// This is a tap, so remove the longpress check
					removeLongPressCallback();
				}
			}

			break;

		case MotionEvent.ACTION_CANCEL:
			if (!mHasPerformedLongPress) {
				// This is a tap, so remove the longpress check
				removeLongPressCallback();
			}
			break;

		case MotionEvent.ACTION_UP:
			if (!mHasPerformedLongPress) {
				removeLongPressCallback();
				
				performOneClick(); 

			}

			break;

		default:
			break;
		}
		return super.onTouchEvent(event);
	}

	// Long Click�좎럩伊숂뙴�묒삕�좎룞�쀯옙�뽰맶�좎럩�울옙類㏃삕�ル∥吏쀥뜝�숈삕 Runnable �좎럩伊숋옙猿볦삕占쎈뜄�뉐뜝�숈삕 
	class CheckForLongPress implements Runnable {

		public void run() {
			if (performLongClick()) {
				mHasPerformedLongPress = true;
			}
		}
	}
 
	private void postCheckForLongClick(int delayOffset) {
		mHasPerformedLongPress = false;

		if (mPendingCheckForLongPress == null) {
			mPendingCheckForLongPress = new CheckForLongPress();
		}

		mHandler.postDelayed(mPendingCheckForLongPress,
				ViewConfiguration.getLongPressTimeout() - delayOffset);	
		}


	private void removeLongPressCallback() {
		if (mPendingCheckForLongPress != null) {
			mHandler.removeCallbacks(mPendingCheckForLongPress);
		}
	}

	public boolean performLongClick() {
		Intent foo = new Intent(this, TextEntryActivity.class);
		foo.putExtra("value", "");
		foo.putExtra("deletable", false);
		this.startActivityForResult(foo, MEMO_ADD);

		return true;
	}

	private void performOneClick() {
		// do nothing
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case MEMO_ADD:
			try {
				String value = data.getStringExtra("value");
				int opacity = data.getIntExtra("opacity", 255);

				if (value != null && value.length() > 0) {
					FrameLayout f = (FrameLayout) findViewById(R.id.music_sheet_background);

					TextView memoView = new TextView(this);
					memoView.setText(value);
					memoView.setLayoutParams(new LayoutParams(
							LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
					memoView.setX(mLastMotionX);
					memoView.setY(mLastMotionY);
					memoView.setTextColor(Color.argb(opacity, 255, 0, 0));
					// TODO : another font
					memoView.setTextSize(30);  // TODO : get size as like opacity

					Memo memo = new Memo(-1, mLastMotionX, mLastMotionY,
							opacity, pageNum, value, musicSheetId, memoView);
					int id = (int) dbhelper.addMemo(memo);
					memo.setId(id);
					
					memoList.add(memo);
					
					memoView.setOnClickListener(new View.OnClickListener() {

						@Override
						public void onClick(View v) {
							selectedMemo = (TextView) v;
							
							Intent foo = new Intent(DisplayActivity.this, TextEntryActivity.class);
							foo.putExtra("value", ((TextView) v).getText().toString());
							foo.putExtra("deletable", true);
							DisplayActivity.this.startActivityForResult(foo, MEMO_MODIFY);
						}
					});

					f.addView(memoView);
				}
			} catch (Exception e) {
			}
			break;
		case MEMO_MODIFY:
			try {
				boolean deleted = data.getBooleanExtra("delete", false);
				String value = data.getStringExtra("value");
				int opacity = data.getIntExtra("opacity", 255);
				FrameLayout f = (FrameLayout) findViewById(R.id.music_sheet_background);
				
				if (deleted) {
					// update memo DB
					for (Memo memo : memoList) {
						if (memo.getTv().equals(selectedMemo)) {
							dbhelper.deleteMemo(memo);
							break;
						}
					}
					f.removeView(selectedMemo);
				} else if (value != null && value.length() > 0) {

					// update display
					selectedMemo.setText(value);
					selectedMemo.setTextColor(Color.argb(opacity, 255, 0, 0));

					// update memo DB
					for (Memo memo : memoList) {
						if (memo.getTv().equals(selectedMemo)) {
							memo.setContent(value);
							memo.setOpacity(opacity);
							dbhelper.updateMemo(memo);
							break;
						}
					}
					
				}
			} catch (Exception e) {
			}
			break;
		case SCORE:
			this.finish();
		default:
			break;
		}
	}


	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
	}

	private int getNotePosition(Note note) {
		int umm = note.getPitch()%100;
		int oct = note.getPitch()/100;

		int note_height = yPositions[umm-1] * -10 + 60;

		if(oct==5) note_height-=70;
		if(oct==4&&umm==1) note_height-=10;
		if(oct<=3) {
			note_height-=60;
			note_height/=2;
			note_height+=90;
		}

		return note_height;
	}
	

	private void displayMusicSheet(int page) {
		FrameLayout l = (FrameLayout) findViewById(R.id.music_sheet);

		for (ImageView iv : noteViews) {
			l.removeView(iv);
		}
		noteViews.clear();

		// Display music sheet
		int note_index = 0;
		int y = top_padding;
		int count = 0;
		while (count++ < 3) {
			if (note_index >= 0)
				note_index = DrawNotes(note_index, side_padding + 100, y, music_sheet.getNotes(pageNum));
			if (note_index >= 0)
				note_index = DrawNotes(note_index,
						(int) ((nexus7_width - side_padding + 140) / 2), y, music_sheet.getNotes(pageNum));
			y += space_five_line;
		}
		lastNoteIndex = music_sheet.getNotes(pageNum).size()-1;
	}

	public int DrawNotes(int pt, int x, int y, ArrayList<Note> notes) {
		FrameLayout l = (FrameLayout) findViewById(R.id.music_sheet);

		int beatSum = 0;
		while (pt < notes.size()) {
			Note note = notes.get(pt++);
			beatSum += note.getBeat();

			if (beatSum > bar_length) {
				return pt - 1;
			}

			ImageView noteImage = new ImageView(getBaseContext());
			Bitmap bmNote;
			if (note.isRest()) {
				switch (note.getBeat()){
				
				case 1:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.rest_1);
					break;
				case 2:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.rest_2);
					break;
				case 4:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.rest_4);
					break;
				case 6:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.rest_6);
					break;
				case 8:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.rest_8);
					break;
				case 16:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.rest_16);
					break;
				default:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.rest_4);
				}
			} else {
				switch (note.getBeat()) {
				case 1:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_1);
					break;
				case 2:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_2);
					break;
				case 3:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_3);
					break;
				case 4:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_4);
					break;
				case 6:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_6);
					break;
				case 8:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_8);
					break;
				case 12:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_12);
					break;
				case 16:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_16);
					break;
				default:
					bmNote = BitmapFactory.decodeResource(getResources(),
							R.drawable.note_1);
				}
			}

			paint.setColor(Color.BLACK);
			paint.setStrokeWidth(2f);
			if (note.getPitch() / 100 < 4 || note.getPitch()==401 ) {
				int ummY = yPositions[note.getPitch()%100-1];
				ImageView lineImage = new ImageView(getBaseContext());
				Bitmap bmLine = Bitmap.createBitmap((int) 80, (int) 180,
						Bitmap.Config.ARGB_8888);
				Canvas lineCanvas = new Canvas(bmLine);
				lineImage.setImageBitmap(bmLine); 

				int lineY = space_five_line;
				if(ratio<1) lineY+=7;
				if(ummY%2!=0||note.getPitch()==401) lineY += 6;

				for (int i=ummY; i<8; i+=2){				
					lineCanvas.drawLine(0, lineY, 30, lineY, paint);
					lineY -= 8;
					if(note.getPitch()==401) break;
				}
				lineImage.setLayoutParams(new LayoutParams(
						LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
				lineImage.setPadding(x+5, getNotePosition(note)+y-100, 0, 0);

				l.addView(lineImage);
				noteViews.add(lineImage);
			}
			// noteCanvas.drawLine(0, 0, 0, 0, p);

			noteImage.setImageBitmap(bmNote);
			noteImage.setLayoutParams(new LayoutParams(
					LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
			noteImage.setPadding(x+10, getNotePosition(note) + y, 0, 0);
			note.x = x;
			note.y = y;

			Matrix mNote = new Matrix();
			mNote.postScale((float) 0.5, (float) 0.5);
			noteImage.setScaleType(ScaleType.MATRIX);
			noteImage.setImageMatrix(mNote);

			l.addView(noteImage);
			noteViews.add(noteImage);

			if (note.isAccidental()) { 
				ImageView accidental = new ImageView(getBaseContext());
				Bitmap bmA;
				int umm = note.getPitch()%100;
				if(umm!=2&&umm!=4&&umm!=7&&umm!=9&&umm!=11)
					bmA = BitmapFactory.decodeResource(getResources(),
							R.drawable.natural);
				else if (keyNumber<0)
					bmA = BitmapFactory.decodeResource(getResources(),
							R.drawable.flat);
				else
					bmA = BitmapFactory.decodeResource(getResources(),
							R.drawable.sharp);
												
				accidental.setImageBitmap(bmA);
				accidental.setLayoutParams(new LayoutParams(
						LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
				accidental.setPadding(x-5, getNotePosition(note)+y+35, 0, 0);
				
				Matrix mA = new Matrix();
				mA.postScale((float) 0.17, (float) 0.17);
				accidental.setScaleType(ScaleType.MATRIX);
				accidental.setImageMatrix(mA);
				l.addView(accidental);
				noteViews.add(accidental); 
			}

			x += dx*note.getBeat();
		}
		return -1;
	}
	
	private void changeColorToRed(ImageView note) {
		if(!feedbackOn) return;
		
		Bitmap bitmap = ((BitmapDrawable)note.getDrawable()).getBitmap();
		
		int [] allpixels = new int [ bitmap.getHeight()*bitmap.getWidth()];
		bitmap.getPixels(allpixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

		for(int i =0; i<bitmap.getHeight()*bitmap.getWidth();i++){
			if(allpixels[i] != 0)
				allpixels[i] = Color.RED;
		}

		Bitmap newBmp = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Config.ARGB_8888);
		newBmp.setPixels(allpixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
		
		note.setImageBitmap(newBmp);
	}

	private class RecordAudio extends AsyncTask<Void, double[], Void> {
		@Override
		protected Void doInBackground(Void... params) {
			try {
				int bufferSize = AudioRecord.getMinBufferSize(frequency,
						channelConfiguration, audioEncoding);
				AudioRecord audioRecord = new AudioRecord(
						MediaRecorder.AudioSource.MIC, frequency,
						channelConfiguration, audioEncoding, bufferSize);

				short[] buffer = new short[blockSize * 2 + 1];
				double[] toTransform = new double[blockSize * 2 + 1];
				// n is even, the real of part of (n/2)-th complex FFT
				// coefficients is x[n];
				// (n-k)-th complex FFT coeffient is the conjugate of n-th
				// complex FFT coeffient.

				audioRecord.startRecording();
				while (started) {
					int bufferReadResult = audioRecord.read(buffer, 0,
							blockSize * 2 + 1);

					for (int i = 0; i < blockSize * 2 + 1
							&& i < bufferReadResult; i++) {
						toTransform[i] = (double) buffer[i] / Short.MAX_VALUE;
					}

					transformer.ft(toTransform);
					publishProgress(toTransform);
				}
				audioRecord.stop();

			} catch (Throwable t) {
				Log.e("AudioRecord", "Recording Failed");
			}
			return null;
		}

		// not in background
		@Override
		protected void onProgressUpdate(double[]... toTransform) {
			// tracking bar moving
			Date temp = new Date();
			long currentRecognitionTime = temp.getTime();
			if (tracking_prev_time <= 0) tracking_prev_time = currentRecognitionTime;
			long deltaTime = currentRecognitionTime - tracking_prev_time;
			tracking_prev_time = currentRecognitionTime; 
   
			double curr_x = trackingView.getX();
			if (Math.abs(curr_x - music_sheet.getNote(pageNum, currentPosition).x) > ((nexus7_width - 2*side_padding - 100) / 2)) 
				trackingView.setX( music_sheet.getNote(pageNum, currentPosition).x);
			else trackingView.setX((float)(curr_x + tracking_velocity * dx * deltaTime));
			//debugText.setText(trackingView.getX()+"   "+tracking_velocity);
			
			curCanvas.drawColor(Color.BLACK);
			double maxIntensity = Math.abs(toTransform[0][0]); // first real (0
			// imaginary)
			double maxFrequency = 0;
			double Magnitude[] = new double[blockSize + 1];
			Magnitude[0] = maxIntensity;
			for (int i = 1; i < toTransform[0].length / 2; i++) {
				Magnitude[i] = Math.sqrt((toTransform[0][2 * i - 1])
						* (toTransform[0][2 * i - 1]) + (toTransform[0][2 * i])
						* (toTransform[0][2 * i]));
				if (maxIntensity < Magnitude[i]) {
					maxIntensity = Magnitude[i];
					maxFrequency = i;
				}
			}

			Note currentNote = music_sheet.getNote(pageNum, currentPosition);
			int xMax = (int)(calibPitches[currentNote.getPitch()/100-3][currentNote.getPitch()%100-1]
					 / (frequency/(blockSize * 2 + 1)));
			paint.setColor(Color.argb(150,200,255,255));
			curCanvas.drawLine(xMax, 0, xMax, 100, paint);
			
			for (int i = 0; i < Magnitude.length; i++) {
				int x = i;
				int downy = (int) (100 - (calib_data[currentNote.getPitch()/100-3]
						[currentNote.getPitch()%100-1][i] * 10));
				int upy = 100;
				paint.setColor(Color.argb(80, 255, 10, 20));
				curCanvas.drawLine(x, downy, x, upy, paint);
			}
			
			while (music_sheet.getNote(pageNum, currentPosition).isRest() && tracking_velocity < (double)1/4000)
			{
				num_of_correct++;
				currentPosition++;
			}
			String s = "";
			for (int j=0; j<scores.length; j++)
			{
				Note tempNote = music_sheet.getNote(pageNum, currentPosition-5+j);
				if( tempNote != null ){ //currentPosition-5+j>=0||pageNum>1 &&        currentPosition <= lastNoteIndex ||pageNum>1){
					double[] tempSpec = calib_data[tempNote.getPitch()/100-3][tempNote.getPitch()%100-1];
					double tempMaxF = calibPitches[tempNote.getPitch()/100-3][tempNote.getPitch()%100-1];
					int tempIdx = (int)Math.round(tempMaxF/(frequency/(blockSize*2+1)));
					
					double mag = 0;
					int idx = 0;
					for (int i=-5;i<=5; i++)
					{
						if(tempIdx+i>=0 && tempIdx+i<Magnitude.length)
						{
							if(mag<Magnitude[tempIdx+i]) {
								mag=Magnitude[tempIdx+i];
								idx = i;
							}
						}					
					}
					matches[j]= (Math.abs(idx)<2 && mag>tempSpec[tempIdx]*0.4)
							|| tempNote.isRest();
					if(matches[j]) {
						scores[j]+=deltaTime*factors[j];
					}
					else errors[j]+=deltaTime*factors[j];
					s+= Math.floor(scores[j]*10)/10+","+Math.floor(errors[j]*10)/10+" ";
				}	
				else s+= " - " ;
			}
			
			if(debugMode) resultText.setText(s);
			
			// initializing
			if(matches[5]==true && prevRecognitionTime ==0){								
				prevRecognitionTime = temp.getTime();
			}

			Note currNote = music_sheet.getNote(pageNum, currentPosition); 
			
			Note nextNote1 = music_sheet.getNote(pageNum, currentPosition+1);
			Note nextNote2 = music_sheet.getNote(pageNum, currentPosition+2);
			Note nextNote3 = music_sheet.getNote(pageNum, currentPosition+3);
			Note nextNote4 = music_sheet.getNote(pageNum, currentPosition+4);
			Note nextNote5 = music_sheet.getNote(pageNum, currentPosition+5);
			
			double passedTime = currentRecognitionTime - matched_time;
			
			boolean isPassed = false;
			
			
			if(nextNote1 == null)
			{
				if(passedTime > currNote.getBeat()/tracking_velocity*2)
				{
					started = false;
					startStopButton.setText("Start");
					recordTask.cancel(true);
					tracking_velocity = 0;
					trackingView.setX(music_sheet.getNote(pageNum, currentPosition).x);
					trackingView.setY(music_sheet.getNote(pageNum, currentPosition).y);

					Intent foo = new Intent(DisplayActivity.this, ScoreActivity.class);
					foo.putExtra("title", "Score");
					
					int score = (int)((float)num_of_correct/num_of_note*100);
					foo.putExtra("score", Math.min(score, 100));
					DisplayActivity.this.startActivityForResult(foo, SCORE);
					
				}
			}
			else if (scores[5] > currNote.getBeat() / tracking_velocity * 0.4 || tracking_velocity < (double)1/4000) {
				// debugText.setText("here");
				if ( (passedTime > currNote.getBeat()/tracking_velocity*0.8 && currNote.isRest())
						|| ( (passedTime > currNote.getBeat()/tracking_velocity || (tracking_velocity<(double)1/4000&&scores[5]>200)) && matches[6] && nextNote1.getPitch()!=currNote.getPitch() && !currNote.isRest())
						|| (errors[5]> 60 && matches[6] && nextNote1.getPitch()==currNote.getPitch() && !currNote.isRest())
						|| (errors[5]> 60 && nextNote1.isRest() && 
								(passedTime > (currNote.getBeat())/tracking_velocity || tracking_velocity < (double)1/4000) && !currNote.isRest()) ){
				//	debugText.setText("here2");
					currentPosition++;
					FeedbackVelocity(currentPosition - 1, currentPosition); 
					isPassed = true; 
				}	
				else; 
				//	debugText.setText("here3");
			} 
			if(!isPassed && !currNote.isRest()){
				if (!matches[5] && matches[6] && nextNote1!=null && !nextNote1.isRest()
						&& (passedTime>currNote.getBeat()/tracking_velocity*0.5 || tracking_velocity < (double)1/4000)) {
					currentPosition++;
					FeedbackVelocity(currentPosition - 1, currentPosition); 
				} else if (!matches[5] && matches[7] && nextNote2!=null && !nextNote2.isRest()
						&& (passedTime>(currNote.getBeat()+nextNote1.getBeat())/tracking_velocity*0.5 || tracking_velocity < (double)1/4000)) {
					currentPosition += 2;
					num_of_mistake += 1;
					changeColorToRed(noteViews.get(currentPosition-1));
					FeedbackVelocity(currentPosition - 2, currentPosition); 
				}else if (!matches[5] && matches[8]  && nextNote3!=null && !nextNote3.isRest()
						&& (passedTime>(currNote.getBeat()+nextNote1.getBeat()+nextNote2.getBeat())/tracking_velocity*0.5 || tracking_velocity < (double)1/4000)) {
					currentPosition += 3;
					num_of_mistake += 2;
					changeColorToRed(noteViews.get(currentPosition-1));
					changeColorToRed(noteViews.get(currentPosition-2));
					FeedbackVelocity(currentPosition - 3, currentPosition); 
				}
				else if (!matches[5] && matches[9]  && nextNote4!=null && !nextNote4.isRest()
						&& (passedTime>(currNote.getBeat()+nextNote1.getBeat()+nextNote2.getBeat()+nextNote3.getBeat())/tracking_velocity*0.5 || tracking_velocity < (double)1/4000)) {
					currentPosition += 4;
					num_of_mistake += 3;
					changeColorToRed(noteViews.get(currentPosition-1));
					changeColorToRed(noteViews.get(currentPosition-2));
					changeColorToRed(noteViews.get(currentPosition-3));
					FeedbackVelocity(currentPosition - 4, currentPosition); 
				}
				else if (!matches[5] && matches[10]  && nextNote5!=null && !nextNote5.isRest()
						&& (passedTime>(currNote.getBeat()+nextNote1.getBeat()+nextNote2.getBeat()+nextNote3.getBeat()+nextNote4.getBeat())/tracking_velocity*0.5 || tracking_velocity < (double)1/4000)) {
					currentPosition += 5;
					num_of_mistake += 4;
					changeColorToRed(noteViews.get(currentPosition-1));
					changeColorToRed(noteViews.get(currentPosition-2));
					changeColorToRed(noteViews.get(currentPosition-3));
					changeColorToRed(noteViews.get(currentPosition-4));
					FeedbackVelocity(currentPosition - 5, currentPosition); 
				}
			}

			/*
			Note currNote = music_sheet.getNote(pageNum, currentPosition); 
			double passedTime = currentRecognitionTime - matched_time;
			if (currNote.isRest() && passedTime > currNote.getBeat()/tracking_velocity){
				currentPosition++;
				FeedbackVelocity(currentPosition - 1, currentPosition); 
			}
			if (matches[5]&&matches[6] && passedTime > currNote.getBeat()/tracking_velocity){
				currentPosition++;
				FeedbackVelocity(currentPosition - 1, currentPosition); 
			}
					
			if(!matches[5]&&matches[6]) {
				currentPosition++;
				FeedbackVelocity(currentPosition - 1, currentPosition); // XXX : prev, curr
			} else if(!matches[5]&&matches[7]) {
				currentPosition+=2;
				FeedbackVelocity(currentPosition - 2, currentPosition); // XXX : prev, curr
			} else if(!matches[5]&&matches[8]) {
				currentPosition+=3;
				FeedbackVelocity(currentPosition - 3, currentPosition); // XXX : prev, curr
			}
			*/
			
			trackingDebugView.setX(music_sheet.getNote(pageNum, currentPosition).x);
			trackingDebugView.setY(music_sheet.getNote(pageNum, currentPosition).y);
			
			if (tracking_y != music_sheet.getNote(pageNum, currentPosition).y) {
				tracking_x = music_sheet.getNote(pageNum, currentPosition).x;
				tracking_y = music_sheet.getNote(pageNum, currentPosition).y;
				trackingView.setY((float) tracking_y);  // XXX : tracking view should independent to curr position
				trackingView.setX((float) tracking_x);  // XXX : tracking view should independent to curr position
			}
			
			if (lastNoteIndex >= 0 && currentPosition >= lastNoteIndex
					&& pageNum < music_sheet.getPages() && passedTime>0.6*(currNote.getBeat())/tracking_velocity ) {
				// turn to next page.
				updatePage(pageNum + 1);	
				currentPosition--;
			}

			for (int i = 0; i < Magnitude.length; i++) {
				int x = i;
				int downy = (int) (100 - (Magnitude[i] * 10));
				int upy = 100;
				paint.setColor(Color.rgb(250, 100, 255));
				curCanvas.drawLine(x, downy, x, upy, paint);
			}
			/*			
			if (true) {
				if (sampleCount < sampleSize) {
					for (int i = 0; i < toTransform[0].length; i++) {
						toTransformSample[i][sampleCount] = toTransform[0][i];
					}
					sampleCount++;
				} else {
					sampleCount = 0;
				}
			}*/

			currentSpec.invalidate();
		}
	}
	
	public double pitch2frequency(int in_pitch){
		int oct = in_pitch/100;
		int umm = in_pitch%100;
		return ref_pitches[umm-1]*Math.pow(2,(oct-4)); 
	}
	
	private void updatePage(int page) {
		if (page > music_sheet.getPages() || page <= 0)
			Log.d("Warning", "unexpected page : " + page);
		else {
			pageNum = page;
			
			// show page number on the bottom
			pageNumView.setText(Integer.toString(page));

			// show existing memos
			memoList = music_sheet.getMemos(page);
			showMemos(memoList);
			
			// update notes
			noteList = music_sheet.getNotes(page);
			displayMusicSheet(page);
			
			// initialize current playing position as 0
			currentPosition = 0;
			
			// tracking bar position
			trackingView.setX(music_sheet.getNote(pageNum, currentPosition).x);
			trackingView.setY(music_sheet.getNote(pageNum, currentPosition).y);
			if(page!=1) trackingView.setVisibility(View.INVISIBLE);
		}
	}
}
