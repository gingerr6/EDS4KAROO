package com.valterc.ki2.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import com.valterc.ki2.data.device.DeviceId;
import com.valterc.ki2.data.device.DeviceType;

/**
 * Converts between {@link BluetoothDevice} and {@link DeviceId} for Wheeltop EDS BLE devices.
 *
 * Encoding:
 *   deviceTypeValue  = DeviceType.WHEELTOP_SHIFTING.getValue() = 3
 *   deviceNumber     = first 4 MAC bytes as big-endian int  (AA:BB:CC:DD → 0xAABBCCDD)
 *   transmissionType = last  2 MAC bytes as int             (EE:FF       → 0x0000EEFF)
 */
public final class BleDeviceMapper {

    private BleDeviceMapper() {}

    /** Returns true if the given DeviceId represents a Wheeltop BLE device. */
    public static boolean isWheeltopDevice(DeviceId deviceId) {
        return deviceId != null && deviceId.getDeviceType() == DeviceType.WHEELTOP_SHIFTING;
    }

    /**
     * Create a {@link DeviceId} from a {@link BluetoothDevice}.
     * The MAC address is encoded losslessly into the three DeviceId integer fields.
     */
    public static DeviceId fromBluetoothDevice(BluetoothDevice device) {
        String mac = device.getAddress(); // "AA:BB:CC:DD:EE:FF"
        String[] parts = mac.split(":");
        int deviceNumber = (Integer.parseInt(parts[0], 16) << 24)
                         | (Integer.parseInt(parts[1], 16) << 16)
                         | (Integer.parseInt(parts[2], 16) << 8)
                         |  Integer.parseInt(parts[3], 16);
        int transmissionType = (Integer.parseInt(parts[4], 16) << 8)
                             |  Integer.parseInt(parts[5], 16);
        return new DeviceId(deviceNumber, DeviceType.WHEELTOP_SHIFTING.getValue(), transmissionType);
    }

    /**
     * Reconstruct the MAC address string from a Wheeltop {@link DeviceId}.
     */
    public static String toMacAddress(DeviceId deviceId) {
        int n = deviceId.getDeviceNumber();
        int t = deviceId.getTransmissionType();
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                (n >> 24) & 0xFF, (n >> 16) & 0xFF, (n >> 8) & 0xFF, n & 0xFF,
                (t >> 8) & 0xFF, t & 0xFF);
    }

    /**
     * Get a {@link BluetoothDevice} from an adapter using a Wheeltop {@link DeviceId}.
     * The returned device may not be connected or even nearby — it is just a reference
     * to the remote device by address.
     */
    public static BluetoothDevice toBluetoothDevice(BluetoothAdapter adapter, DeviceId deviceId) {
        return adapter.getRemoteDevice(toMacAddress(deviceId));
    }
}
