package com.example.trombone;


import java.util.ArrayList;
import java.util.List;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup.LayoutParams;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;

import com.essence.chart.Chart;
import com.essence.chart.GridData;

import static classes.Constants.*;
import classes.*;
import db.DBHelper;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 * 
 * @see SystemUiHider
 */
public class HistoryActivity extends Activity {
	private Chart scoreChart, countChart;
	DBHelper dbhelper = new DBHelper(this);
	List<MusicSheet> musicSheets;
	private MusicSheet selected_ms;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_NO_TITLE);  
		//set up full screen
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,   
				WindowManager.LayoutParams.FLAG_FULLSCREEN);  

		setContentView(R.layout.activity_history);

		scoreChart = (Chart) findViewById(R.id.history_score_chart);
		countChart = (Chart) findViewById(R.id.history_count_chart);
		Spinner spin =  (Spinner) findViewById(R.id.spinner_history);

		// set height of charts
		LayoutParams layoutParamsChart = spin.getLayoutParams();
		int spinHeight = layoutParamsChart.height;
		
		layoutParamsChart = scoreChart.getLayoutParams();
		layoutParamsChart.height = (int) (0.5 * (nexus7_height - spinHeight));
		scoreChart.setLayoutParams(layoutParamsChart);

		layoutParamsChart = countChart.getLayoutParams();
		layoutParamsChart.height = (int) (0.5 *(nexus7_height - spinHeight));
		countChart.setLayoutParams(layoutParamsChart);
		
		// spinner
		musicSheets = dbhelper.getAllMusicSheets(1);
		selected_ms = musicSheets.get(0);
		
		ArrayList<String> sheetNames = new ArrayList<String>();
		ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
	                android.R.layout.simple_spinner_dropdown_item, sheetNames);
				
		for (MusicSheet ms : musicSheets)
		{
			sheetNames.add(ms.getName());
			adapter.notifyDataSetChanged();
		}

		spin.setAdapter(adapter);
        spin.setOnItemSelectedListener(new OnItemSelectedListener() {
        	@Override
			public void onItemSelected(AdapterView<?> parent, View view,
					int position, long id) {
        		selected_ms = musicSheets.get(position);
        		updateScoreHistory();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
        		selected_ms = musicSheets.get(0);
			}
        });
		

		// score chart
		scoreChart.setChartType(Chart.Chart_Type_Line);
		scoreChart.setLegendVisible(true);
		// XXX : legend name
		scoreChart.setTitle("Score history of " + selected_ms.getName());
		scoreChart.setTitleFontSize(30);
		
		updateScoreHistory();
		

		// TODO : set data name (music sheet title)

		// TODO : set X-axis name correctly 


		// chart.setYAxisMaximum(true, 2000);

		// count chart
		countChart.setChartType(Chart.Chart_Type_Clustered_Column);
		countChart.setLegendVisible(false);
		countChart.setTitle("Hits");
		countChart.setTitleFontSize(30);

		int nRow = 2;
		int nCol = musicSheets.size();
		GridData gridData = new GridData(nRow, nCol);
		for (int j = 0; j < nCol; j++) {
			MusicSheet musicSheet = musicSheets.get(j);
			gridData.setCell(0, j, musicSheet.getName());
			gridData.setCell(1, j, musicSheet.getPlayCount());
			// XXX : future work. finish count
		}

		countChart.setSourceData(gridData, 1);

	}
	
	private void updateScoreHistory() {
		int musicSheetId = selected_ms.getId();
		List<History> histories = dbhelper.getHitories(musicSheetId);

		scoreChart.setTitle("Score history of " + selected_ms.getName());
		
		int nRow = histories.size();
		int nCol = 2;
		GridData gridData = new GridData(nRow, nCol);
		
		for (int i = 0; i < nRow; i++) {
			History history = histories.get(i);
			gridData.setCell(i, 0, history.getDate());
			gridData.setCell(i, 1, history.getScore());
		}

		scoreChart.setSourceData(gridData, 0);
		scoreChart.postInvalidate();
	}

}
