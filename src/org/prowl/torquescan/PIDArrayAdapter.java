package org.prowl.torquescan;


import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.TextView;


public class PIDArrayAdapter<T> extends ArrayAdapter<T> {
	
	private Context context;
	private static int layout_id = R.layout.listitem_pid; // Should not change
	private LayoutInflater inflater;
	// Widgets
	private TextView nameTextView;
	private TextView valueTextView;
	private CheckBox checkBox;
	
	private SharedPreferences sharedPref;
	private Set<String> checkedPidIdsSet = new HashSet<String>();
	private Map<String, PID> map;
	private PID[] pidArray = {};
	private final String TAG = "TorqueDCS";
	private boolean DEBUG = true;
	
	public PIDArrayAdapter(Context context) {
		super(context, layout_id);
		this.context = context;	
		this.inflater = ((Activity) this.context).getLayoutInflater();
		this.sharedPref = context.getSharedPreferences(context.getString(R.string.dcs_preference_key), Context.MODE_PRIVATE);
	}
	
	@Override
	public int getCount() {
		if (map == null) {
			return 0;
		} else {
			int size = map.size();
			return size;
		}
	}

	@Override
	public long getItemId(int position) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		//Inflate view if not already inflated
        if(convertView==null) {
            convertView = inflater.inflate(layout_id, parent, false);
        }
        getWidgets(convertView);
        updateWidgets(position, convertView);
		return convertView;
	}

	private void updateWidgets(int position, View view) {
		if (position < pidArray.length) {
			
			// Set Name Text Box
			this.nameTextView.setText(pidArray[position].getName());
			
			// Set Value Text Box
			switch (pidArray[position].getState()) {
				case PID.STATE.ACTIVE:
					this.valueTextView.setText(pidArray[position].getValue() + " " + pidArray[position].getUnit());
					view.setBackgroundColor(Color.parseColor("#ff464D5C"));
					break;
				case PID.STATE.SUPPORTED:
					this.valueTextView.setText("PID Supported, not Active");
					view.setBackgroundColor(Color.BLACK);
					break;
				default:
					this.valueTextView.setText("PID Unknown State");
					view.setBackgroundColor(Color.BLACK);
					break;
			}
			
			// Set Checkbox enable/disable
			this.checkBox.setChecked(checkedPidIdsSet.contains(pidArray[position].getId()));
		}
	}

	private void getWidgets(View convertView) {
		this.nameTextView = (TextView) convertView.findViewById(R.id.textView_PidName);
		this.valueTextView = (TextView) convertView.findViewById(R.id.textView_PidValue);
		this.checkBox = (CheckBox) convertView.findViewById(R.id.checkBox1);
	}
	
	public void updatePidMap(Map<String, PID> map){
		this.map = map;
		pidArray = map.values().toArray(new PID[map.size()]);
	}
	
	public void itemSelected(int position){
		// Check or uncheck as appropriate
		if (checkedPidIdsSet.contains(pidArray[position].getId())) {
			checkedPidIdsSet.remove(pidArray[position].getId());
		} else {
			checkedPidIdsSet.add(pidArray[position].getId());
		}
		this.notifyDataSetChanged();
	}
	
	public boolean save() {
		
		SharedPreferences.Editor editor = sharedPref.edit();
		editor.putStringSet(context.getString(R.string.dcs_checked_items), new HashSet<String>(checkedPidIdsSet));
		debug("Share Preferences Saved: " + checkedPidIdsSet.toString());
		return editor.commit();
	}

	public void load() {
		if (sharedPref.contains(context.getString(R.string.dcs_checked_items))) {
			checkedPidIdsSet = new HashSet<String>(sharedPref.getStringSet(context.getString(R.string.dcs_checked_items), new HashSet<String>()));
			debug("Share Preferences Loaded: " + checkedPidIdsSet.toString());
		}else{
			debug("Share Preferences - Nothing to Load");
		}
	}

	public void selectAll(boolean checked) {
		checkedPidIdsSet.clear();
		if (checked) {
			for (PID pid : pidArray) {
				checkedPidIdsSet.add(pid.getId());
			}
		}
		debug("All PIDs Set" + checked);
	}
	private void debug(String str){
		if ( DEBUG ) Log.d( TAG , str); 
	}
	private void debug(String str, Exception e){
		if ( DEBUG ) Log.d( TAG , str, e); 
	}

	public void selectAllActive() {
		checkedPidIdsSet.clear();
		for (PID pid : pidArray) {
			if (pid.getState() == PID.STATE.ACTIVE) {
				checkedPidIdsSet.add(pid.getId());
			}
		}
		debug("All ACTIVE PIDs Set");
	}
}


