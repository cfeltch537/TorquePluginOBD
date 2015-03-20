package org.prowl.torquescan;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.prowl.torque.remote.ITorqueService;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class DCS_Torque_Service extends Service{


	// Debug Tag
	private final String TAG = "TorqueDCS";
	private final boolean DEBUG = true;

	// Intent Extra Strings
	public static final String OBDII_EXTRA = "obdII";
	public static final String SID_EXTRA = "subjectId";

	// OBDII / Torque
	public static final String OBDII_ACTION_CONNECT = "org.prowl.torquescan.DCS_Torque_Service.OBDII_ACTION_CONNECT";
	public static final String OBDII_ACTION_DISCONNECT = "org.prowl.torquescan.DCS_Torque_Service.OBDII_ACTION_DISCONNECT";
	public static final int OBDII_MSG_REGISTER = 801;
	public static final int OBDII_MSG_UNREGISTER = 802;
	public static final int OBDII_MSG_DATA = 803;
	public static final int OBDII_MSG_STATUS_CONNECTED = 804;
	public static final int OBDII_MSG_STATUS_DISCONNECTED = 805;
	public static final int OBDII_MSG_STATUS_CONNECTING = 806;
	public static final int OBDII_MSG_UPDATE_TO_SHARED_PREF = 807;


	// Constants - Notifications
	private static final int NOTIFICATION_OBDII_SERVICE_RUNNING = 0;

	// ITorqueService
	private ITorqueService torqueService;
	private SharedPreferences sharedPref;
	private Set<String> idsForPidsOfInterest;
	private PID[] pidsOfInterest;

	// Other Globals
	private boolean isServiceStarted = false;
	private String mSubjectID;
	private Handler mMessageHandler = new ObdIIMessageHandler();
	private Handler mPidUpdateHandler;
	private Messenger mMessenger = new Messenger(mMessageHandler);
	private PowerManager mPowerManager;
	private WakeLock mWakeLock;
	private NotificationManager mNotificationManager;
	private Notification mNotification;
	private FileWriter mFileWriter;
	private boolean isExternalStorageAvailable = false;
	private boolean isExternalStorageWritable = false;
	private static ArrayList<Messenger> mSubscriberList = new ArrayList<Messenger>();
	private String mFilename;
	private File mFolder;
	private Context mContext;
	private String[] idsArray;
	private boolean isTorqueServiceConnected = false;
	private boolean isClientConnected = false;
	private boolean arePidsUpdated = false;
	
	// Broadcast Reciever
	private static final String ACTION_OBD_CONNECTED="org.prowl.torque.OBD_CONNECTED";
	private static final String ACTION_OBD_DISCONNECTED="org.prowl.torque.OBD_DISCONNECTED";
	private static final String ACTION_OBD_APP_LAUNCHED="org.prowl.torque.APP_LAUNCHED";
	private static final String ACTION_OBD_APP_QUITTING="org.prowl.torque.APP_QUITTING";

    private BroadcastReceiver yourReceiver;

	// Constants - CSV
	private static final String CSV_FILENAME = "OBDII";
	private static final String CSV_FOLDER = "OBDII";

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// REMOVE THIS
		// android.os.Debug.waitForDebugger();
		
		debug("onStartCommand called");
		// HANDLE INCOMING INTENT
		if (!isServiceStarted && intent.getAction().equals(OBDII_ACTION_CONNECT)) { // START SERVICE
			this.mContext = this.getApplicationContext();
			debug("Starting Service: OBDII_ACTION_CONNECT"); 
			getPartialWakeLock();
			setNotification(NOTIFICATION_OBDII_SERVICE_RUNNING);
			setupObdIIUpdates();
			isServiceStarted = true;
		}else if (isServiceStarted && intent.getAction().equals(OBDII_ACTION_DISCONNECT) && mSubscriberList.size() == 0) { // STOP SERVICE
			debug("Stopping Service: OBDII_ACTION_DISCONNECT");
			unbindService(connection);
			stopSelf();
		}else if (isServiceStarted && intent.getAction().equals(OBDII_ACTION_DISCONNECT) && mSubscriberList.size() != 0) { // STOP SERVICE, BUT OTHERS USING
			debug("OBDII_ACTION_DISCONNECT Recieved but other Applications Subscribed");
		}else {
			debug("? onStartCommand: " + intent.getAction() + ", isServiceStarted: " + isServiceStarted);
		}

		// STORE USER ID IF PRESENT IN INTENT
		if(intent.hasExtra(SID_EXTRA)){
			try {
				mSubjectID = intent.getStringExtra(SID_EXTRA);
			}catch(Exception e){
				debug("Exception pulling subject ID from intent: " + e.toString());
			}
		}
		return START_STICKY;
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		final IntentFilter theFilter = new IntentFilter();
        theFilter.addAction(ACTION_OBD_APP_LAUNCHED);
        theFilter.addAction(ACTION_OBD_APP_QUITTING);
        theFilter.addAction(ACTION_OBD_CONNECTED);
        theFilter.addAction(ACTION_OBD_DISCONNECTED);
        this.yourReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                debug("Intent Recieved: " + intent.getAction());
                switch (intent.getAction()) {
				case ACTION_OBD_APP_QUITTING:
					debug("Torque Quit while service is still running! Stopping Service.");
					stopSelf();
					break;
				case ACTION_OBD_CONNECTED:
					sendConnectionUpdate(mMessenger, OBDII_MSG_STATUS_CONNECTED);
					break;
				case ACTION_OBD_DISCONNECTED:
					sendConnectionUpdate(mMessenger, OBDII_MSG_STATUS_DISCONNECTED);
					break;
				default:
					break;
				}
            }
        };
        // Registers the receiver so that your service will listen for broadcasts 
        this.registerReceiver(this.yourReceiver, theFilter);
	}

	/**
	 * Should connect to ITorqueService
	 */
	private void setupObdIIUpdates() {
		
		// Bind to the torque service
		Intent intent = new Intent();
		intent.setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService");
		startService(intent);
		boolean successfulBind = bindService(intent, connection, 0);

		if (successfulBind) {
			usePidUpdateHandler();
		}
	}
	
	  public void usePidUpdateHandler() {
		  mPidUpdateHandler = new Handler();
		  mPidUpdateHandler.postDelayed(mPidUpdateRunnable, 1000);
	  }

	  private boolean mEcuConnectionStatus;
	  private Runnable mPidUpdateRunnable = new Runnable() {

		@Override
		public void run() {
			if ( DEBUG ) Log.v(TAG, "Handler Call To Send PID Message: " + System.currentTimeMillis());
			if (isTorqueServiceConnected) {
				if (!arePidsUpdated) {
					loadSharedPrefPIDs();
				}
				getPidValues(idsArray);
				sendObdIIUpdate();
				appendToCsvFile();
				mMessageHandler.postDelayed(mPidUpdateRunnable, 10);
				//mMessageHandler.post(mPidUpdateRunnable);
			} else {
				debug("Timer Tick, but TorqueService not yet connected");
				mMessageHandler.postDelayed(mPidUpdateRunnable, 5000);
			}
		}
	};

	protected void getPidValues(String[] ids) {
		try {
			//String[] pidInfoString = torqueService.getPIDInformation(ids);
			float[] pidValues = torqueService.getPIDValues(ids);
			long[] pidTimes = torqueService.getPIDUpdateTime(ids);
			for (int i = 0; i < ids.length; i++) {	
				pidsOfInterest[i].setValue(pidValues[i]);
				pidsOfInterest[i].setTime(pidTimes[i]);
			}
		} catch (Exception e) {
			debug("Ex in getPidValues(): ", e);
		}
	}

	/**
	 * Called via BindService(Intent) command, from calling activity
	 * Returns Messenger with ObdIIMessageHandler handler
	 */
	@Override
	public IBinder onBind(Intent arg0) {
		debug("onBind Called");
		isClientConnected = true;
		return mMessenger.getBinder();
	}

	@Override
	public void onRebind(Intent intent) {
		debug("onReBind Called");
		isClientConnected = true;
		super.onRebind(intent);
	}
	
	/**
	 * Called via UnbindService(Intent) command, from calling activity
	 * Does not stop the Service in this instance
	 */
	@Override
	public boolean onUnbind(Intent intent) {
		debug("onUnbind Called");
		isClientConnected = false;
		return super.onUnbind(intent);
	}

	/**
	 * Handler for Messenger given to bound applications
	 * Handles the following Message Types:
	 *  OBDII_MSG_REGISTER = Stores callers return messenger, sends PID Values periodicaly
	 *  OBDII_MSG_UNREGISTER = Removes caller from subscriber list
	 *  OBDII_MSG_UPDATE_TO_SHARED_PREF = Comes From PluginActivity ONLY, states new PID list to send
	 */
	class ObdIIMessageHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			// This is the action 
			int msgType = msg.what;

			switch(msgType) {
			case OBDII_MSG_REGISTER:
				if(!mSubscriberList.contains(msg.replyTo)){
					mSubscriberList.add(msg.replyTo);
					debug("handleMessage: Subscriber added");
				}else{
					debug("handleMessage: Subscriber already subscribed");
				}
				break;
			case OBDII_MSG_UNREGISTER:
				if(mSubscriberList.contains(msg.replyTo)){
					mSubscriberList.remove(msg.replyTo);
					debug("handleMessage: Subscriber unsubscribed");
				}else{
					debug("handleMessage: Subscriber not found during unsubscribe");
				}
				break;
			case OBDII_MSG_UPDATE_TO_SHARED_PREF:
					arePidsUpdated = false;
				break;
			default: 
				super.handleMessage(msg);
			}
		}
	}

	/**
	 * Sends Message to subscribers with PID values:
	 */
	private void sendObdIIUpdate(){
		try {
			Message resp = Message.obtain(); // Put locations Data here
			Bundle bResp = new Bundle();
			bResp.putByteArray(OBDII_EXTRA, PID.toByteArray(pidsOfInterest));
			resp.setData(bResp);
			resp.what = OBDII_MSG_DATA;
			
			for(int i=0; i<mSubscriberList.size(); i++){
				try {
					try {
						mSubscriberList.get(i).send(resp);
					} catch (DeadObjectException e) {
						mSubscriberList.remove(i);
						debug("DeadObjectException, subscriber must have unbind but not unregistered. Subscriber Removed");
					}
					
				} catch (Exception e) {
					debug("Ex sending Message: OBDII_PID_DATA", e);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	

	/**
	 * Send update to connection 
	 */
	private void sendConnectionUpdate(Messenger messenger, int what){
		try {
			Message resp = Message.obtain(); // Put locations Data here
			Bundle bResp = new Bundle();
			resp.setData(bResp);
			resp.what = what;
			messenger.send(resp);
			debug("Message Sent");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Requests partial wake lock from PowerManager
	 * This keeps the service and device's processor working, even when the screen is locked
	 */
	private void getPartialWakeLock() {
		try {
			mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
			mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
			mWakeLock.acquire();
			debug("Partial Wake Lock Aquired");
		} catch (Exception e) {
			debug("Exeption in getPartialWakeLock(): " + e.getMessage());
		}
	}

	/**
	 * Sets notification in Foreground to keep the Service alive
	 * Notifications:
	 *  NOTIFICATION_OBDII_SERVICE_STARTED - Initial Notification
	 *  NOTIFICATION_OBDII_DISABLED - When OBD is disabled, notification is RED, and pressing the notification will take the user to Location Options
	 *  NOTIFICATION_OBDII_ENABLED - Should only really be shown when MOCK OBD Source is being used, or right after OBD is enabled
	 *  NOTIFICATION_OBDII_SEARCHING - When OBD is enabled and searching but no intitial Fix has been aquired
	 *  NOTIFICATION_OBDII_LOCKED - When the initial fix is locked in, GREEN status icon, data being reporter
	 */
	private void setNotification(int notification) {
		String contentTitle = "OBDII Service";
		String contentText = "Error";
		String ticker = "Error";
		PendingIntent pendingIntent = null;
		pendingIntent = PendingIntent.getActivity(this, 0, new Intent(mContext, PluginActivity.class), 0);
		
		int icon = android.R.drawable.ic_menu_directions;

		switch (notification) {
		case NOTIFICATION_OBDII_SERVICE_RUNNING:
			contentText = "OBD Service Logging";
			ticker = "OBDII Service Running and Logging";
			icon = android.R.drawable.ic_menu_manage; //presence_online
			break;
		default:
			break;
		}

		try {
			NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
			.setSmallIcon(icon)
			.setContentTitle(contentTitle)
			.setContentText(contentText)
			.setTicker(ticker)
			.setOngoing(true)
			.setContentIntent(pendingIntent);
			mNotification = notificationBuilder.build();
			mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
			// Clear any currently active Notifications from this service
			mNotificationManager.cancelAll();
			mNotificationManager.notify(notification, mNotification);
			startForeground(notification, mNotification);
			debug( "Notification " + notification + " started");
		} catch (Exception e) {
			debug( "Exception in setNotification() for " + notification + " : " + e.getMessage());
		}
	}

	/**
	 * True IFF external Storage directory is available (i.e. Mounted and Writeable)
	 */
	protected boolean checkStorage() {
		String state = Environment.getExternalStorageState();
		if(Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			isExternalStorageAvailable = isExternalStorageWritable = true;
		} else if(Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			// We can only read the media
			isExternalStorageAvailable = true;
			isExternalStorageWritable = false;
		} else {
			// Something else is wrong. It may be one of many other states, but 
			// we can neither read nor write
			isExternalStorageAvailable = isExternalStorageWritable = false;
		}
		// Return TRUE only if writable storage available
		if(isExternalStorageAvailable & isExternalStorageWritable) {
			debug("External Storage Available and Readable");
			return true;
		}else {
			debug("External storage NOT Available or Readable.");
			return false;
		}
	}

	/**
	 * Creates CSV file named: GPS_YYYYMMDD_HHSS
	 * Heading: "Latitude, Longitude, Altitude, Accuracy, Speed, Bearing, Time"
	 */
	private void createCsvFile() {
		// IF WRITABLE STORAGE IS AVAILABLE
		if(checkStorage()){
			// CHECK TO MAKE SURE FOLDER PATH EXISTS
			mFolder = new File(Environment.getExternalStorageDirectory() + File.separator + CSV_FOLDER);
			if (!mFolder.exists()){
				Boolean var = mFolder.mkdir();
				debug("Directory " + mFolder.getAbsolutePath() + " not present. Created: "+ var.toString().toUpperCase());
			}else{
				debug("Directory " + mFolder.getAbsolutePath() + " exists!");
			}
			// CREATE CSV FILE
			mFilename = mFolder.toString() + File.separator + CSV_FILENAME + getDateForFilename("_yyyyMMdd_HHmm") + ".csv";
		}
		// OPEN CSV FILE TO WRITE TO
		try
		{
			mFileWriter = new FileWriter(mFilename, true);
			debug("Opened File: " + mFilename);
		}
		catch(IOException e)
		{
			e.printStackTrace();
			debug("Exception with FileWriter for " + mFilename + " : " + e.toString());
		}
		// APPEND THE HEADING INFORMATION
		try
		{
			// SUBJECT ID AND START TIME ON FIRST LINE
			mFileWriter.append(
							"Subject Id:, " +
							mSubjectID + '\n' +
							"Date:, " +
							getDateForFilename("MM/dd/yyyy")+ '\n' +
							"Time:, " +
							getDateForFilename("HH:mm:ss (zzz)") + '\n');
			mFileWriter.flush();
			
			// Column Information
			String columnInformation = "Log Time (Millis), ";
			for (PID pid : pidsOfInterest) {
				// Name (unit), Update Time (system), 
				columnInformation = columnInformation + pid.getName() + " (" + pid.getUnit() + "), Update Time (system), ";
			}
			columnInformation = columnInformation + '\n';
			mFileWriter.append(columnInformation);
			mFileWriter.flush();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			debug(CSV_FILENAME);
		}
	}

	/**
	 * Append currently stored mLocation data to CSV file
	 */
	private void appendToCsvFile(){
		try
		{
			String string = System.currentTimeMillis() + ",";
			for (PID pid : pidsOfInterest) {
				// value, time), 
				string = string + pid.getValue() + "," + pid.getTime() + ",";
			}
			string = string + '\n';
			mFileWriter.append(string);
			mFileWriter.flush();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			debug("Exception appending to csv file" + e.toString());
		}
	}

	/**
	 * Returns the Date/Time in the format specified from input string, for the CSV filename, UTC time used
	 */
	private String getDateForFilename(String dateFormat){

		SimpleDateFormat sdf = new SimpleDateFormat(dateFormat, Locale.US);
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		return sdf.format(new Date(System.currentTimeMillis()));
	}

	/**
	 * Called in responce to StopService(Service) command
	 * Removes all locks and notifications, closes the CSV file
	 */
	@Override
	public void onDestroy() {
		debug("onDestroy Called");

		// Stop Handler
		mPidUpdateHandler.removeCallbacks(mPidUpdateRunnable);
		mPidUpdateHandler = null;
		mPidUpdateRunnable = null;
		
		// UNREGISTER RECIEVER
		this.unregisterReceiver(this.yourReceiver);
		
		// REMOVE PARTIAL WAKE LOCK
		try{
			mWakeLock.release();
		}catch(Exception e){
			debug("onDestroy() - Release WakeLock: ", e);
		}

		// REMOVE FOREGROUND NOTIFICATIONS
		try{
			mNotificationManager.cancelAll();
			stopForeground(true);
		}catch(Exception e){
			debug("onDestroy() - Close Notifications: ", e);
		}
		
		// CLOSE FILEWRITER
		try{
			mFileWriter.close();
		}catch(Exception e){
			debug("onDestroy() - Close FileWriter: ", e);
		}

		// REMOVE MESSENGER
		mMessenger = null;
		
		isServiceStarted = false; // Not Really Necessary
		super.onDestroy();
	}

	private void debug(String str){
		if ( DEBUG ) Log.d(TAG, str); 
	}
	private void debug(String str, Exception e){
		if ( DEBUG ) Log.d(TAG, str, e); 
	}

	// iTORQUESERVICE SERVICE CODE

	/**
	 * Bits of service code. You usually won't need to change this.
	 */
	private ServiceConnection connection = new ServiceConnection() {
		public void onServiceConnected(ComponentName arg0, IBinder service) {
			torqueService = ITorqueService.Stub.asInterface(service);
			isTorqueServiceConnected = true;
		};
		public void onServiceDisconnected(ComponentName name) {
			torqueService = null;
			isTorqueServiceConnected = false;
		};
	};

	public void loadSharedPrefPIDs() {
		try {

			// DEFINE SHARED PREF
			this.sharedPref = mContext.getSharedPreferences(mContext.getString(R.string.dcs_preference_key), Context.MODE_MULTI_PROCESS);
			
			// GET "PIDS OF INTEREST" (SELECTED IN PLUGIN ACTIVITY)
			if (sharedPref.contains(mContext.getString(R.string.dcs_checked_items))) {
				idsForPidsOfInterest = new HashSet<String>(sharedPref.getStringSet(mContext.getString(R.string.dcs_checked_items), new HashSet<String>()));
				
				// CREATE PID ARRAY, AND GET INFORMATION FROM THE TORQUE INTERFACE
				pidsOfInterest = new PID[idsForPidsOfInterest.size()];
				idsArray = idsForPidsOfInterest.toArray(new String[0]);
				String[] descriptionsArray = torqueService.getPIDInformation(idsArray);
				for (int i = 0; i < pidsOfInterest.length; i++) {
					pidsOfInterest[i] = new PID(descriptionsArray[i], idsArray[i], PID.STATE.DEFAULT);
				}
				arePidsUpdated = true;
				debug("Shared Prefs loaded, pidsOfInterest:" + idsForPidsOfInterest.toString());
				createCsvFile();
				debug("New CSV File Created for new pidsOfInterest");
			}else{
				debug("Service cannot find and stored PID IDs to send");
			}
		} catch (Exception e) {
			debug("Ex Loading Shared Pref: ", e);
		} 
	}
	

}
