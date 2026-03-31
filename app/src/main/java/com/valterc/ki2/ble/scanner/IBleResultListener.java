package com.valterc.ki2.ble.scanner;

import android.bluetooth.BluetoothDevice;

public interface IBleResultListener {
    /**
     * Called when an EDS BLE device is found during a scan.
     *
     * @param device The discovered Bluetooth device.
     * @param rssi   Signal strength in dBm.
     */
    void onBleDeviceFound(BluetoothDevice device, int rssi);
}
