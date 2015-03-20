package org.prowl.torquescan;

import java.io.StreamCorruptedException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Map;

import org.prowl.torque.remote.ITorqueService;

import android.R.anim;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

public class PluginActivity extends Activity {

	// Array Adapter
	private ITorqueService torqueService;
	private ListView listView;
	private TextView serviceResponceTextView;
	private Handler mListViewUpdateHandler;
	private PIDArrayAdapter<String> pidArrayAdapter;
	private Map<String, PID> map = new HashMap<String, PID>();
	private boolean supportedPidsLoaded = false;
	private final String TAG = "TorqueDCS";

	// Service
	private Messenger mMessenger;
	private Messenger mReplyMessenger;
	private Intent mIntent;
	private boolean mServiceBound = false;
	private ServiceConnection mServiceConnection;

	// Other
	private final boolean DEBUG = true;

	@Override
	public void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		LayoutInflater inflater = LayoutInflater.from(this);
		View view = inflater.inflate(R.layout.main, null);
		serviceResponceTextView = (TextView) view
				.findViewById(R.id.serviceResponceTextView);

		if (mReplyMessenger == null) {
			mReplyMessenger = new Messenger(new ObdIIServiceResponseHandler());
		}

		// Bind to DCS Service
		startAndBindService();

		setupListViewAdapter(view);
		setContentView(view);
	}

	private void startAndBindService() {

		mIntent = new Intent(DCS_Torque_Service.OBDII_ACTION_CONNECT);

		// Start OBDII Service
		try {
			startService(mIntent);
		} catch (SecurityException e) {
			debug("Security Exception in startService:" + e.toString());
		}
		// Bind to GPS Service
		if (!mServiceBound) {
			// Service Connection to handle system callbacks
			mServiceConnection = new ServiceConnection() {
				@Override
				public void onServiceDisconnected(ComponentName name) {
					mMessenger = null;
				}

				@Override
				public void onServiceConnected(ComponentName name,
						IBinder service) {
					// We are connected to the service, create messenger and
					// send message
					mMessenger = new Messenger(service);
					try {
						// Subscribe to GPS updates
						Message msg = Message.obtain(null,
								DCS_Torque_Service.OBDII_MSG_REGISTER);
						msg.replyTo = mReplyMessenger;
						mMessenger.send(msg);
						debug("Message Sent to Service: OBDII_MSG_REGISTER");
					} catch (RemoteException e) {
						debug("Exeption sending Subscribe Message");
					}
				}
			};
			// Bind to the service
			try {
				mServiceBound = bindService(mIntent, mServiceConnection,
						Context.BIND_AUTO_CREATE);
			} catch (Exception e) {
				debug("Exeption binding to service:" + e.toString());
			}

		} else {
			debug("Attempted to Bind. Service already bound");
		}
	}

	private void setupListViewAdapter(View view) {
		listView = (ListView) view.findViewById(R.id.listView_PIDs);
		pidArrayAdapter = new PIDArrayAdapter<String>(this);
		listView.setAdapter(pidArrayAdapter);
		listView.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view,
					int position, long id) {
				pidArrayAdapter.itemSelected(position);
				debug("Item Selected: " + position);
			}
		});
		debug(listView.getAdapter() + "");
		pidArrayAdapter.load();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.dcs_torque_scan_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		switch (item.getItemId()) {
		case R.id.action_save:
			// SAVE THROUGH LISTVIEW ADAPTER, THEN TELL DCS SERVICE,
			// *** Need to do this because the Listener for
			// OnSharedPreferenceChange() does not seem to be working
			boolean sucessfulSave = pidArrayAdapter.save();
			if (sucessfulSave && mServiceBound) {
				Message msg = Message.obtain(null,
						DCS_Torque_Service.OBDII_MSG_UPDATE_TO_SHARED_PREF);
				msg.replyTo = mReplyMessenger;
				try {
					mMessenger.send(msg);
				} catch (RemoteException e) {
					debug("Ec sending OBDII_MSG_UPDATE_TO_SHARED_PREF message",
							e);
				}
			} else if (sucessfulSave && !mServiceBound) {
				debug("Preferences Saved, but Service Not Bound");
			} else {
				Toast.makeText(this, "Save FAILED", Toast.LENGTH_LONG).show();
			}

			break;
		case R.id.action_load:
			pidArrayAdapter.load();
			break;
		case R.id.action_select:
			pidArrayAdapter.selectAll(true);
			break;
		case R.id.action_select_active:
			pidArrayAdapter.selectAllActive();
			break;
		case R.id.action_deselect:
			pidArrayAdapter.selectAll(false);
			break;
		case R.id.action_refresh:
			update();
			break;
		case R.id.action_toggle_service:
			if (mServiceBound) {
				unbindAndStopService();
			} else {
				startAndBindService();
			}

			break;
		default:
			debug("onOptionsItemSelected() item.getItemId():"
					+ item.getItemId());
			break;
		}

		return super.onOptionsItemSelected(item);
	}

	private Runnable mListViewUpdateRunnable = new Runnable() {
		
		@Override
		public void run() {
			update();
			//mListViewUpdateHandler.postDelayed(mListViewUpdateRunnable, 100);
		}
	};
	
	@Override
	protected void onResume() {
		super.onResume();

		// Bind to the torque service
		Intent intent = new Intent();
		intent.setClassName("org.prowl.torque",
				"org.prowl.torque.remote.TorqueService");
		boolean successfulBind = bindService(intent, connection, 0);

		if (successfulBind) {
			mListViewUpdateHandler = new Handler();
			mListViewUpdateHandler.postDelayed(mListViewUpdateRunnable, 1000);
		} else {
			debug("PluginActivity did not BIND successfully");
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		mListViewUpdateHandler.removeCallbacksAndMessages(null);
		unbindService(connection);
		debug("PluginActivity OnPause() Called");
	}

	@Override
	protected void onDestroy() {
		debug("PluginActivity OnDestroy() Called");
		super.onDestroy();
	}

	/**
	 * Handler for messages returned from the service
	 */
	class ObdIIServiceResponseHandler extends Handler {

		@Override
		public void handleMessage(Message msg) {
			int respCode = msg.what;
			switch (respCode) {
			case DCS_Torque_Service.OBDII_MSG_DATA:
				mLastMessagePIDs = getPidArrayFromMessage(msg);
				if (mTextViewHandler == null) {
					mTextViewHandler = new Handler();
					mTextViewHandler.post(mTextViewRunnable);
				}
				if (mLastMessagePIDs != null) {
					calculateLogRate(mLastMessagePIDs);
					//updateServiceTextView(mLastMessagePIDs);
				}
				break;
			case DCS_Torque_Service.OBDII_MSG_STATUS_CONNECTED:
				update();
				break;
			case DCS_Torque_Service.OBDII_MSG_STATUS_DISCONNECTED:
				update();
				break;
			}
		}
	}

	private Handler mTextViewHandler;
	private Runnable mTextViewRunnable = new Runnable() {
		@Override
		public void run() {
			if (mLastMessagePIDs != null) {
				updateServiceTextView(mLastMessagePIDs);
			}
			mTextViewHandler.postDelayed(mTextViewRunnable, 50);
			// Max Update Frequency of 10Hz
		}
	};
	private PID[] mLastMessagePIDs;
	private long mCurrentTimeMillis = 0;
	private long mLastTimeMillis = 0;
	private double mInstantaneousLogRate = 0.0;
	private double mAverageLogRate = 0.0;
	private double[] mMovingAvgBuffer = new double[30];
	private DecimalFormat decimalFormat = new DecimalFormat("#.00");

	private PID[] getPidArrayFromMessage(Message msg) {
		// GET PID[] FROM MESSAGE
		try {
			return PID.fromByteArray(msg.getData().getByteArray(
					DCS_Torque_Service.OBDII_EXTRA));
		} catch (StreamCorruptedException e) {
			e.printStackTrace();
			return null;
		}
	}

	private void calculateLogRate(PID[] pidArray) {
		mCurrentTimeMillis = System.currentTimeMillis();

		// CALCULATE INSTANTANEOUS READ SPEED PIDs/SEC = #PIDs / delta T
		double deltaInSecs = (mCurrentTimeMillis - mLastTimeMillis) * 1.0
				/ Math.pow(10.0, 3.0);
		mInstantaneousLogRate = pidArray.length / deltaInSecs;

		// CALCULATE MOVING AVERAGE READ SPEED
		mAverageLogRate = 0;
		for (int i = 0; i < mMovingAvgBuffer.length; i++) {
			if (i == mMovingAvgBuffer.length - 1) {
				mMovingAvgBuffer[i] = mInstantaneousLogRate;
				mAverageLogRate = mAverageLogRate + mInstantaneousLogRate;
			} else {
				mMovingAvgBuffer[i] = mMovingAvgBuffer[i + 1];
				mAverageLogRate = mAverageLogRate + mMovingAvgBuffer[i];
			}
		}
		mAverageLogRate = mAverageLogRate / mMovingAvgBuffer.length;

		mLastTimeMillis = mCurrentTimeMillis;
	}

	private void updateServiceTextView(PID[] pidArray) {

		// PID READ SPEED
		String tempString = "Number of PIDs: " + pidArray.length 
				+ System.getProperty("line.separator");

		try {
			tempString = tempString + "Read Speed: "
					+ decimalFormat.format(mAverageLogRate) + " (PIDs/s) , "
					+ decimalFormat.format(mAverageLogRate / pidArray.length)
					+ " Hz" + " , " + torqueService.getPIDReadSpeed() + " , " + torqueService.getConfiguredSpeed() + System.getProperty("line.separator");
		} catch (RemoteException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		// ECU Information
		try {
			tempString = tempString + "ECU Connected: " + torqueService.isConnectedToECU() + System.getProperty("line.separator");
			tempString = tempString + "ECU Protocol: " + torqueService.getProtocolName() + System.getProperty("line.separator");
		} catch (RemoteException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		tempString = tempString+ System.getProperty("line.separator");
		for (int i = 0; i < pidArray.length; i++) {
			PID pid = (PID) pidArray[i];
			tempString = tempString + pid.getName() + ": " + pid.getValue()
					+ " " + pid.getUnit() + " @ " + pid.getTime()
					+ System.getProperty("line.separator");
		}

		serviceResponceTextView.setText(tempString);
	}

	private void unbindAndStopService() {

		// UN-SUBSCRIBE FROM OBD UPDATES
		Message msg = Message.obtain(null,
				DCS_Torque_Service.OBDII_MSG_UNREGISTER);
		msg.replyTo = mReplyMessenger;
		try {
			if (mMessenger == null) {
				debug("Messenger Null, Service never connected");
			} else {
				mMessenger.send(msg);
				debug("Message Sent to Service: UNSUBSCRIBED MESSAGE_DATA_PARCEABLE_STRING");
			}
		} catch (Exception e) {
			debug("Exeption Unsibscribing to GPS updates:", e);
		}
		// UNBIND SERVICE
		if (mServiceBound) {
			try {
				unbindService(mServiceConnection);
				mServiceBound = false;
			} catch (Exception e) {
				debug("Exception in Unbind: ", e);
			}
		}
		// STOP SERVICE, VIA DISCONNECT MESSAGE
		try {
			startService(new Intent(DCS_Torque_Service.OBDII_ACTION_DISCONNECT));
			// stopService(mIntent);
		} catch (Exception e) {
			debug("Exception in Stop Service: ", e);
		}
		mMessenger = null;

	}

	/**
	 * Do an update
	 */
	public void update() {
		updatePidList();
	}

	private void updatePidList() {
		// Populate Supported PIDS
		if (!supportedPidsLoaded || map.isEmpty()) {
			loadSupportedPIDs();
		}

		// Upadate active PIDs
		try {
			storePIDs(torqueService.listActivePIDs(), true);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
	}

	private void loadSupportedPIDs() {
		try {
			storePIDs(torqueService.listECUSupportedPIDs(), false);
			supportedPidsLoaded = true;
		} catch (RemoteException e) {
			e.printStackTrace();
		}
	}

	private void storePIDs(String[] pidsToStore, boolean active) {
		try {
			String[] pidInfoString = torqueService
					.getPIDInformation(pidsToStore);
			float[] pidValues = torqueService.getPIDValues(pidsToStore);
			for (int i = 0; i < pidsToStore.length; i++) {
				if (active) {
					// Check if PID already exist, then update or create new
					if (map.containsKey(pidsToStore[i])) {
						PID tempPID = map.get(pidsToStore[i]);
						tempPID.setDescriptionNameAndUnits(pidInfoString[i]);
						tempPID.setId(pidsToStore[i]);
						tempPID.setValue(pidValues[i]);
						tempPID.setState(PID.STATE.ACTIVE);
						map.put(pidsToStore[i], tempPID);
					} else {
						map.put(pidsToStore[i], new PID(pidInfoString[i],
								pidsToStore[i], pidValues[i], PID.STATE.ACTIVE));
					}
				} else {
					// Check if PID already exist, then update or create new
					if (map.containsKey(pidsToStore[i])) {
						PID tempPID = map.get(pidsToStore[i]);
						tempPID.setDescriptionNameAndUnits(pidInfoString[i]);
						tempPID.setId(pidsToStore[i]);
						tempPID.setState(PID.STATE.SUPPORTED);
						map.put(pidsToStore[i], tempPID);
					} else {
						map.put(pidsToStore[i], new PID(pidInfoString[i],
								pidsToStore[i], PID.STATE.SUPPORTED));
					}
				}
			}

		} catch (RemoteException e) {
			e.printStackTrace();
		}

		PluginActivity.this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				try {
					pidArrayAdapter.updatePidMap(map);
					pidArrayAdapter.notifyDataSetChanged();
				} catch (IllegalStateException e) {
					e.printStackTrace();
					// Thats OK
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Bits of service code. You usually won't need to change this.
	 */
	private ServiceConnection connection = new ServiceConnection() {
		public void onServiceConnected(ComponentName arg0, IBinder service) {
			torqueService = ITorqueService.Stub.asInterface(service);
		};

		public void onServiceDisconnected(ComponentName name) {
			torqueService = null;
		};
	};

	private void debug(String str) {
		if (DEBUG)
			Log.d(TAG, str);
	}

	private void debug(String str, Exception e) {
		if (DEBUG)
			Log.d(TAG, str, e);
	}
}