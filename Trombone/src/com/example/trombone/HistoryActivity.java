package com.example.trombone;


import android.app.Activity;
import android.os.Bundle;
<<<<<<< HEAD
=======
import android.view.Window;
import android.view.WindowManager;
>>>>>>> origin/origin

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 * 
 * @see SystemUiHider
 */
public class HistoryActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

<<<<<<< HEAD
=======
		requestWindowFeature(Window.FEATURE_NO_TITLE);  
	    //set up full screen
	    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,   
	                WindowManager.LayoutParams.FLAG_FULLSCREEN);  

>>>>>>> origin/origin
		setContentView(R.layout.activity_history);

	}

}
