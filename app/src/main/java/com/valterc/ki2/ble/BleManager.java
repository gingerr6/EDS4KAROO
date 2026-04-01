package com.valterc.ki2.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import timber.log.Timber;

/**
 * Manages access to the system Bluetooth adapter.
 *
 * Provides the BluetoothAdapter and tracks adapter state changes so that
 * higher-level components (scanner, connections) can react to BT enable/disable.
 */
public class BleManager {

    public interface AdapterStateListener {
        void onBluetoothEnabled();
        void onBluetoothDisabled();
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private AdapterStateListener stateListener;
    private boolean disposed;

    private final BroadcastReceiver adapterStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            Timber.i("BLE adapter state changed: %d", state);
            if (stateListener == null) return;
            if (state == BluetoothAdapter.STATE_ON) {
                stateListener.onBluetoothEnabled();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                stateListener.onBluetoothDisabled();
            }
        }
    };

    public BleManager(Context context) {
        this.context = context;
        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        if (this.adapter == null) {
            Timber.w("Bluetooth not available on this device");
        }

        context.registerReceiver(adapterStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    public void setAdapterStateListener(AdapterStateListener listener) {
        this.stateListener = listener;
    }

    /** Returns the system BluetoothAdapter, or null if BT is not available. */
    public BluetoothAdapter getAdapter() {
        return adapter;
    }

    /** Returns true if Bluetooth is available and currently enabled. */
    public boolean isBluetoothReady() {
        return adapter != null && adapter.isEnabled();
    }

    public void dispose() {
        disposed = true;
        try {
            context.unregisterReceiver(adapterStateReceiver);
        } catch (Exception e) {
            // Not registered
        }
    }

    public boolean isDisposed() {
        return disposed;
    }
}
