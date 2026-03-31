package com.valterc.ki2.ble.connection;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

/**
 * Manages BLE connections to multiple EDS devices, keyed by MAC address.
 * Analogous to AntConnectionManager.
 */
public class BleConnectionManager {

    private final Context context;
    private final Map<String, BleDeviceConnection> connectionMap = new HashMap<>();

    public BleConnectionManager(Context context) {
        this.context = context;
    }

    /**
     * Ensure connections exist only for the given set of devices,
     * disconnecting any devices not in the list.
     */
    public void connectOnly(Collection<BluetoothDevice> devices,
                            IBleConnectionListener listener) {
        Collection<String> targetAddresses = new ArrayList<>();
        for (BluetoothDevice d : devices) {
            targetAddresses.add(d.getAddress());
        }

        // Disconnect any device no longer in the target set
        for (String address : new ArrayList<>(connectionMap.keySet())) {
            if (!targetAddresses.contains(address)) {
                disconnect(address);
            }
        }

        connect(devices, listener);
    }

    /** Connect to all given devices (skips already-connected devices). */
    public void connect(Collection<BluetoothDevice> devices, IBleConnectionListener listener) {
        for (BluetoothDevice device : devices) {
            connect(device, listener);
        }
    }

    /** Connect to a single device. No-op if already connected or connecting. */
    public void connect(BluetoothDevice device, IBleConnectionListener listener) {
        String address = device.getAddress();
        BleDeviceConnection existing = connectionMap.get(address);
        if (existing != null && existing.isReady()) {
            Timber.d("Already connected to %s", address);
            return;
        }
        if (existing != null) {
            existing.disconnect();
        }
        Timber.i("Creating connection for %s", address);
        BleDeviceConnection connection = new BleDeviceConnection(context, device, listener);
        connectionMap.put(address, connection);
        connection.connect();
    }

    /** Disconnect a device by MAC address. */
    public void disconnect(String address) {
        BleDeviceConnection connection = connectionMap.remove(address);
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** Disconnect a device by BluetoothDevice. */
    public void disconnect(BluetoothDevice device) {
        disconnect(device.getAddress());
    }

    /** Disconnect all managed devices. */
    public void disconnectAll() {
        for (BleDeviceConnection connection : new ArrayList<>(connectionMap.values())) {
            connection.disconnect();
        }
        connectionMap.clear();
    }

    /** Returns the connection for the given address, or null. */
    public BleDeviceConnection getConnection(String address) {
        return connectionMap.get(address);
    }

    /**
     * Reconnect any devices that are currently not in the ready state
     * (e.g. after a disconnection event).
     */
    public void reconnectAll(IBleConnectionListener listener) {
        for (Map.Entry<String, BleDeviceConnection> entry : connectionMap.entrySet()) {
            if (!entry.getValue().isReady()) {
                BleDeviceConnection old = entry.getValue();
                BleDeviceConnection fresh = new BleDeviceConnection(context, old.getDevice(), listener);
                connectionMap.put(entry.getKey(), fresh);
                fresh.connect();
            }
        }
    }

    /** Returns true if at least one connection is fully ready. */
    public boolean hasActiveConnection() {
        for (BleDeviceConnection connection : connectionMap.values()) {
            if (connection.isReady()) return true;
        }
        return false;
    }
}
