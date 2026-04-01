package com.valterc.ki2.ble.connection;

import android.bluetooth.BluetoothDevice;

/**
 * Listener for EDS BLE device connection events and data updates.
 */
public interface IBleConnectionListener {

    /** Called when the GATT connection is established and the device is fully initialised. */
    void onConnected(BluetoothDevice device);

    /** Called when the device disconnects (intentionally or due to error). */
    void onDisconnected(BluetoothDevice device);

    /**
     * Called when the front derailleur gear position changes.
     *
     * @param device    The source device.
     * @param gear      1-based current gear index.
     * @param totalGears Total number of front gears (chainrings).
     */
    void onFrontGearChanged(BluetoothDevice device, int gear, int totalGears);

    /**
     * Called when the rear derailleur gear position changes.
     *
     * @param device     The source device.
     * @param gear       1-based current gear index.
     * @param totalGears Total number of rear gears (sprockets).
     */
    void onRearGearChanged(BluetoothDevice device, int gear, int totalGears);

    /**
     * Called when device info has been read after connection.
     *
     * @param device         The source device.
     * @param leftVersion    Left shifter firmware version string.
     * @param rightVersion   Right shifter firmware version string.
     * @param leftPowerRaw   Left shifter raw battery value (L_POWER).
     * @param rightPowerRaw  Right shifter raw battery value (R_POWER).
     * @param fdPowerRaw     Front derailleur raw battery value (Q_POWER).
     * @param rdPowerRaw     Rear derailleur/hub raw battery value (H_POWER).
     * @param racingMode     Racing mode (0=Normal, 1=Race) from device config.
     */
    void onDeviceInfo(BluetoothDevice device,
                      String leftVersion, String rightVersion,
                      int leftPowerRaw, int rightPowerRaw,
                      int fdPowerRaw, int rdPowerRaw,
                      int racingMode);

    /**
     * Called when BLE RSSI (signal strength) is read.
     *
     * @param device The source device.
     * @param rssi   Signal strength in dBm.
     */
    void onSignalStrength(BluetoothDevice device, int rssi);
}
