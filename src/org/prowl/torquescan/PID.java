package org.prowl.torquescan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;

import android.os.Debug;
import android.util.Log;

public class PID implements Serializable{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private String id;
	private String description;
	private String name;
	private float value;
	private String unit; // this unit does not change
	private long time;
	private int state = 0;
	public class STATE {
		public static final int DEFAULT= 0;
		public static final int SUPPORTED= 1;
		public static final int ACTIVE= 2;
	}
	
//	private boolean dataToLog = false;
	
	public PID(String description, String id, float value, int pid_state) {
		this.setDescriptionNameAndUnits(description);
		this.setId(id);
		this.setValue(value);
		this.setState(pid_state);
	}
	
	public PID(String description, String id, int pid_state) { // No Float Passed to Constructor
		this.setDescriptionNameAndUnits(description);
		this.setId(id);
		this.setValue(0.0f);
		this.setState(pid_state);
	}
	
	public void setDescriptionNameAndUnits(String description) {
		String[] splitString = description.split(",");
		this.setDescription(description);
		this.setName(splitString[0]);
		this.setUnit(splitString[2]);
	}
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public float getValue() {
		return value;
	}
	public void setValue(float value) {
		this.value = value;
	}
	public String getUnit() {
		return unit;
	}
	public void setUnit(String unit) {
		this.unit = unit;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public int getState() {
		return state;
	}
	public void setState(int state) {
		this.state = state;
	}
//	public boolean isDataToLog() {
//		return dataToLog;
//	}
//	public void setDataToLog(boolean dataToLog) {
//		this.dataToLog = dataToLog;
//	}
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public static byte[] toByteArray(PID[] pidArray){
		
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ObjectOutputStream os = new ObjectOutputStream(out);
			os.writeObject(pidArray);
			return out.toByteArray();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
		
	}
	
	public static PID[] fromByteArray(byte[] byteArray) throws StreamCorruptedException{
		
		try {
			ByteArrayInputStream out = new ByteArrayInputStream(byteArray);
			ObjectInputStream os = new ObjectInputStream(out);
			Object object = os.readObject();
			Object[] objects = (Object[]) object;
			return (PID[]) objects;
		} catch (StreamCorruptedException e) {
			throw new StreamCorruptedException();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		return null;
	}
}
