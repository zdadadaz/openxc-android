package com.openxc.enabler.preferences;

import android.content.Context;
import android.util.Log;

import com.openxc.enabler.R;
import com.openxc.sources.DataSourceException;
import com.openxc.sources.bluetooth.BluetoothVehicleDataSource;

/**
 * Enable or disable receiving vehicle data from a Bluetooth CAN device.
 */
public class BluetoothSourcePreferenceManager extends VehiclePreferenceManager {
    private final static String TAG = "BluetoothSourcePreferenceManager";

    private BluetoothVehicleDataSource mBluetoothSource;

    public BluetoothSourcePreferenceManager(Context context) {
        super(context);
    }

    public void close() {
        super.close();
        stopBluetooth();
    }

    protected PreferenceListener createPreferenceListener() {
        return new PreferenceListener() {
            private int[] WATCHED_PREFERENCE_KEY_IDS = {
                R.string.bluetooth_checkbox_key,
                R.string.bluetooth_mac_key,
            };

            protected int[] getWatchedPreferenceKeyIds() {
                return WATCHED_PREFERENCE_KEY_IDS;
            }

            public void readStoredPreferences() {
                setBluetoothSourceStatus(getPreferences().getBoolean(
                            getString(R.string.bluetooth_checkbox_key), false));
            }
        };
    }

    private synchronized void setBluetoothSourceStatus(boolean enabled) {
        Log.i(TAG, "Setting bluetooth data source to " + enabled);
        if(enabled) {
            String deviceAddress = getPreferenceString(
                    R.string.bluetooth_mac_key);
            if(deviceAddress != null ) {
                if(mBluetoothSource == null ||
                        !mBluetoothSource.getAddress().equals(deviceAddress)) {
                    stopBluetooth();

                    try {
                        mBluetoothSource = new BluetoothVehicleDataSource(
                                getContext(), deviceAddress);
                    } catch(DataSourceException e) {
                        Log.w(TAG, "Unable to add Bluetooth source", e);
                        return;
                    }
                    getVehicleManager().addSource(mBluetoothSource);
                    getVehicleManager().addController(mBluetoothSource);
                } else {
                    Log.d(TAG, "Bluetooth connection to address " + deviceAddress
                            + " already running");
                }
            } else {
                Log.d(TAG, "No Bluetooth device MAC set yet (" + deviceAddress +
                        "), not starting source");
            }
        } else {
            stopBluetooth();
        }
    }

    private synchronized void stopBluetooth() {
        getVehicleManager().removeSource(mBluetoothSource);
        getVehicleManager().removeController(mBluetoothSource);
        if(mBluetoothSource != null) {
            mBluetoothSource.close();
            mBluetoothSource = null;
        }
    }
}
