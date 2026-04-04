package com.valterc.ki2.ble.connection;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.valterc.ki2.ble.EdsPacket;
import com.valterc.ki2.ble.EdsProtocol;

import timber.log.Timber;

/**
 * Manages a single BLE GATT connection to an EDS device.
 *
 * Connection flow:
 *   1. connectGatt()
 *   2. onConnectionStateChange → CONNECTED → discoverServices()
 *   3. onServicesDiscovered → find NUS, enable TX notifications (write CCCD)
 *   4. onDescriptorWrite → send getKey packet
 *   5. onCharacteristicChanged (getKey response) → store session key, send startRead
 *   6. onCharacteristicChanged (startRead ACK) → request block 0
 *   7. onCharacteristicChanged (ASCII blocks) → accumulate, request next block
 *   8. After last block → parse device info, notify listener → READY
 *   9. onCharacteristicChanged (CMD_FRONT_STATUS_REPORT / CMD_SERVER_REPORT_GEAR) → emit gear events
 */
public class BleDeviceConnection {

    private enum State {
        IDLE,
        CONNECTING,
        DISCOVERING,
        ENABLING_NOTIFY,
        KEY_HANDSHAKE,
        READING_INFO,
        REFRESHING_INFO,
        READY,
        DISCONNECTED
    }

    private static final int RECONNECT_DELAY_MS        = 1_500;
    private static final int WRITE_TIMEOUT_MS           = 3_000;
    private static final long BATTERY_REFRESH_INTERVAL_MS = 5 * 60 * 1_000L; // 5 minutes
    private static final long RSSI_POLL_INTERVAL_MS      = 30_000L; // 30 seconds
    private static final int MAX_DIRECT_CONNECT_RETRIES = 3;
    private static final int CONNECTION_TIMEOUT_MS       = 10_000;
    private static final long AUTO_CONNECT_TIMEOUT_MS    = 30_000L; // 30s for background connect

    private final Context context;
    private final BluetoothDevice device;
    private final IBleConnectionListener listener;
    private final Handler handler;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic; // write  (client → device)
    private BluetoothGattCharacteristic txCharacteristic; // notify (device → client)

    private State state = State.IDLE;
    private int sessionKey;
    private int nextBlock;
    private final StringBuilder infoBuffer = new StringBuilder();
    private final Runnable refreshBatteryRunnable = this::refreshBatteryInfo;
    private final Runnable rssiPollRunnable = this::pollRssi;
    private final Runnable connectionTimeoutRunnable = this::onConnectionTimeout;

    private int connectRetries = 0;

    // Wheeltop FD always has 2 chainrings regardless of Q_TOTAL
    private static final int FD_GEAR_MAX = 2;

    // Cached gear state
    private int frontGear = -1;  // -1 = never reported
    private int frontGearMax = FD_GEAR_MAX;
    private int rearGear = -1;   // -1 = never reported
    private int rearGearMax = 11; // default; updated from device info

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.i("[%s] GATT connected, discovering services", deviceAddress());
                setState(State.DISCOVERING);
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    Timber.e(e, "Permission denied on discoverServices");
                    disconnect();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i("[%s] GATT disconnected (status=%d)", deviceAddress(), status);
                handleDisconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("[%s] Service discovery failed: %d", deviceAddress(), status);
                disconnect();
                return;
            }

            BluetoothGattService nus = gatt.getService(EdsProtocol.UUID_NUS_SERVICE);
            if (nus == null) {
                Timber.w("[%s] NUS service not found", deviceAddress());
                retryOrDisconnect();
                return;
            }

            rxCharacteristic = nus.getCharacteristic(EdsProtocol.UUID_NUS_RX);
            txCharacteristic = nus.getCharacteristic(EdsProtocol.UUID_NUS_TX);

            if (rxCharacteristic == null || txCharacteristic == null) {
                Timber.w("[%s] NUS characteristics not found", deviceAddress());
                retryOrDisconnect();
                return;
            }

            Timber.i("[%s] NUS service found, enabling notifications", deviceAddress());
            setState(State.ENABLING_NOTIFY);
            enableTxNotifications();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (state != State.ENABLING_NOTIFY) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("[%s] Failed to enable notifications: %d", deviceAddress(), status);
                disconnect();
                return;
            }
            Timber.i("[%s] Notifications enabled, starting key handshake", deviceAddress());
            setState(State.KEY_HANDSHAKE);
            writePacket(EdsProtocol.buildGetKeyPacket());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("[%s] Characteristic write failed: %d", deviceAddress(), status);
            }
        }

        @Override
        @SuppressWarnings("deprecation") // getValue() needed for API < 33; new override handles API 33+
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw == null || raw.length == 0) return;
            handleIncoming(raw);
        }

        // API 33+ override
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            if (value == null || value.length == 0) return;
            handleIncoming(value);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && state == State.READY) {
                listener.onSignalStrength(device, rssi);
            }
        }
    };

    public BleDeviceConnection(Context context, BluetoothDevice device,
                               IBleConnectionListener listener) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Initiate a GATT connection to the device. */
    public void connect() {
        if (state != State.IDLE && state != State.DISCONNECTED) return;
        // Use autoConnect after initial direct attempts are exhausted so Android
        // connects in the background whenever the device starts advertising.
        boolean useAutoConnect = connectRetries >= MAX_DIRECT_CONNECT_RETRIES;
        Timber.i("[%s] Connecting… (attempt %d, autoConnect=%b)",
                deviceAddress(), connectRetries + 1, useAutoConnect);
        setState(State.CONNECTING);
        infoBuffer.setLength(0);
        nextBlock = 0;
        sessionKey = 0;
        frontGear = -1;
        rearGear = -1;

        try {
            gatt = device.connectGatt(context, useAutoConnect, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            handler.removeCallbacks(connectionTimeoutRunnable);
            long timeout = useAutoConnect ? AUTO_CONNECT_TIMEOUT_MS : CONNECTION_TIMEOUT_MS;
            handler.postDelayed(connectionTimeoutRunnable, timeout);
        } catch (SecurityException e) {
            Timber.e(e, "Permission denied on connectGatt");
            setState(State.IDLE);
        }
    }

    private void onConnectionTimeout() {
        if (state == State.READY || state == State.IDLE || state == State.DISCONNECTED) return;
        Timber.w("[%s] Connection timeout in state %s", deviceAddress(), state);
        retryOrDisconnect();
    }

    /** Cleanly disconnect from the device. */
    public void disconnect() {
        Timber.i("[%s] Disconnecting", deviceAddress());
        closeGatt();
        handleDisconnect();
    }

    /** Returns true if the connection is fully established and ready. */
    public boolean isReady() {
        return state == State.READY;
    }

    /** Returns true if the connection is in progress (connecting, handshaking, or reading info). */
    public boolean isActive() {
        return state != State.IDLE && state != State.DISCONNECTED;
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    /**
     * Send an arbitrary EDS command packet to the device.
     * Uses unobfuscated format (u8=0) and writeWithoutResponse, matching the official app.
     * Must only be called when the connection is ready.
     */
    public void sendCommand(int cmd, byte[] payload) {
        if (state != State.READY) {
            Timber.w("[%s] Cannot send command 0x%02X — not ready", deviceAddress(), cmd);
            return;
        }
        byte[] packet = EdsProtocol.buildPacket(sessionKey, cmd, payload);
        Timber.d("[%s] Sending cmd=0x%02X raw: %s", deviceAddress(), cmd, bytesToHex(packet));
        writePacket(packet);
    }

    // -------------------------------------------------------------------------
    // Incoming data dispatch
    // -------------------------------------------------------------------------

    private void handleIncoming(byte[] raw) {
        if (EdsProtocol.isAsciiBlock(raw)) {
            handleAsciiBlock(raw);
            return;
        }

        EdsPacket packet = EdsProtocol.decode(raw);
        if (packet == null) {
            Timber.v("[%s] Ignoring undecoded packet (len=%d)", deviceAddress(), raw.length);
            return;
        }

        Timber.v("[%s] RX %s", deviceAddress(), packet);
        dispatchPacket(packet);
    }

    private void dispatchPacket(EdsPacket packet) {
        switch (packet.cmd) {
            case EdsProtocol.CMD_GET_KEY:
                onGetKeyResponse(packet);
                break;
            case EdsProtocol.CMD_START_READ:
                onStartReadResponse(packet);
                break;
            case EdsProtocol.CMD_FRONT_STATUS_REPORT:
                onFrontStatusReport(packet);
                break;
            case EdsProtocol.CMD_SERVER_REPORT_GEAR:
                onRearGearReport(packet);
                break;
            default:
                Timber.v("[%s] Unhandled cmd=0x%02X", deviceAddress(), packet.cmd);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Protocol state machine handlers
    // -------------------------------------------------------------------------

    private void onGetKeyResponse(EdsPacket packet) {
        if (state != State.KEY_HANDSHAKE) return;
        if (packet.payload.length < 1) {
            Timber.w("[%s] getKey response has no payload", deviceAddress());
            disconnect();
            return;
        }
        sessionKey = packet.payload[0] & 0xFF;
        Timber.i("[%s] Session key: 0x%02X, sending startRead", deviceAddress(), sessionKey);
        writePacket(EdsProtocol.buildStartReadPacket(sessionKey));
        // startRead ACK will come as CMD_START_READ response
    }

    private void onStartReadResponse(EdsPacket packet) {
        if (state == State.KEY_HANDSHAKE) {
            Timber.i("[%s] startRead ACK, reading block 0", deviceAddress());
            setState(State.READING_INFO);
        } else if (state == State.READY) {
            Timber.d("[%s] startRead ACK (refresh), reading block 0", deviceAddress());
            setState(State.REFRESHING_INFO);
        } else {
            return;
        }
        infoBuffer.setLength(0);
        nextBlock = 0;
        requestNextBlock();
    }

    private void handleAsciiBlock(byte[] raw) {
        if (state != State.READING_INFO && state != State.REFRESHING_INFO) return;

        int blockIndex = EdsProtocol.parseAsciiBlockIndex(raw);
        String content = EdsProtocol.parseAsciiBlock(raw);
        Timber.d("[%s] ASCII block %d: '%s'", deviceAddress(), blockIndex, content);

        if (!content.isEmpty()) {
            infoBuffer.append(content);
        }

        if (content.length() < EdsProtocol.ASCII_BLOCK_CONTENT_SIZE) {
            // Short block = last block
            finishInfoRead();
        } else {
            nextBlock = blockIndex + 1;
            requestNextBlock();
        }
    }

    private void requestNextBlock() {
        writePacket(EdsProtocol.buildReadBlockPacket(sessionKey, nextBlock));
    }

    private void finishInfoRead() {
        String info = infoBuffer.toString();
        Timber.i("[%s] Device info: %s", deviceAddress(), info);

        String lVer   = EdsProtocol.parseInfoValue(info, "L_Ver");
        String rVer   = EdsProtocol.parseInfoValue(info, "R_Ver");
        String lPower = EdsProtocol.parseInfoValue(info, "L_POWER");
        String rPower = EdsProtocol.parseInfoValue(info, "R_POWER");
        String qPower = EdsProtocol.parseInfoValue(info, "Q_POWER");
        String hPower = EdsProtocol.parseInfoValue(info, "H_POWER");

        // Rear gear count and current position (inverted: Wheeltop 1=smallest cog, view expects 1=largest)
        rearGearMax = parseIntSafe(EdsProtocol.parseInfoValue(info, "TOTAL_CNT"), rearGearMax);
        int rawRearGear = parseIntSafe(EdsProtocol.parseInfoValue(info, "NUM"), -1);
        int initialRearGear = rawRearGear > 0 ? (rearGearMax + 1 - rawRearGear) : -1;

        // Front derailleur: Q_TOTAL/Q_NUM are raw micro-positions (1-6);
        // map through mapFrontGear() to get actual chainring (1=small, 2=big)
        frontGearMax = FD_GEAR_MAX;
        int rawFrontPos = parseIntSafe(EdsProtocol.parseInfoValue(info, "Q_NUM"), -1);
        int initialFrontGear = rawFrontPos > 0 ? mapFrontGear(rawFrontPos) : -1;

        int leftPowerRaw  = parseIntSafe(lPower, 0);
        int rightPowerRaw = parseIntSafe(rPower, 0);
        int fdPowerRaw    = parseIntSafe(qPower, 0);
        int rdPowerRaw    = parseIntSafe(hPower, 0);

        int racingMode = parseIntSafe(EdsProtocol.parseInfoValue(info, "RacingMode"), 0);
        Timber.d("[%s] RacingMode=%d", deviceAddress(), racingMode);

        boolean isRefresh = (state == State.REFRESHING_INFO);
        setState(State.READY);
        connectRetries = 0;
        handler.removeCallbacks(connectionTimeoutRunnable);

        if (!isRefresh) {
            listener.onConnected(device);
        }

        listener.onDeviceInfo(device,
                lVer != null ? lVer : "",
                rVer != null ? rVer : "",
                leftPowerRaw, rightPowerRaw,
                fdPowerRaw, rdPowerRaw,
                racingMode);

        if (!isRefresh) {
            // Fire initial gear events only on first connect
            if (initialRearGear > 0) {
                rearGear = initialRearGear;
                Timber.d("[%s] Initial RD gear %d/%d", deviceAddress(), rearGear, rearGearMax);
                listener.onRearGearChanged(device, rearGear, rearGearMax);
            }
            if (initialFrontGear > 0) {
                frontGear = initialFrontGear;
                Timber.d("[%s] Initial FD gear %d/%d", deviceAddress(), frontGear, frontGearMax);
                listener.onFrontGearChanged(device, frontGear, frontGearMax);
            }
        }

        // Schedule next battery refresh and start RSSI polling
        handler.removeCallbacks(refreshBatteryRunnable);
        handler.postDelayed(refreshBatteryRunnable, BATTERY_REFRESH_INTERVAL_MS);
        handler.removeCallbacks(rssiPollRunnable);
        handler.post(rssiPollRunnable);
    }

    private void refreshBatteryInfo() {
        if (state != State.READY) return;
        Timber.d("[%s] Requesting battery refresh", deviceAddress());
        writePacket(EdsProtocol.buildStartReadPacket(sessionKey));
    }

    private void pollRssi() {
        if (state != State.READY || gatt == null) return;
        try {
            gatt.readRemoteRssi();
        } catch (SecurityException e) {
            Timber.e(e, "Permission denied on readRemoteRssi");
        }
        handler.postDelayed(rssiPollRunnable, RSSI_POLL_INTERVAL_MS);
    }

    /**
     * Map raw FD position (1-6) to chainring gear (1=small, 2=big).
     * Wheeltop FD has 6 micro-positions; only resting positions matter:
     *   Position 5 = small chainring (gear 1)
     *   Position 3 = big chainring (gear 2)
     *   Other positions are transient micro-shifts — ignored.
     * Returns -1 for transient positions.
     */
    /**
     * Map raw FD position (1-6) to chainring gear (1=small, 2=big).
     * Positions 1,2,3 = big chainring; positions 4,5,6 = small chainring.
     */
    private static int mapFrontGear(int rawPosition) {
        if (rawPosition >= 1 && rawPosition <= 3) return 2; // big chainring
        if (rawPosition >= 4 && rawPosition <= 6) return 1; // small chainring
        return -1;
    }

    private void onFrontStatusReport(EdsPacket packet) {
        if (packet.payload.length < 5) return;
        Timber.d("[%s] FD raw payload: %s", deviceAddress(), bytesToHex(packet.payload));
        int rawPosition = (packet.payload[4] & 0xFF);
        int gear = mapFrontGear(rawPosition);
        if (gear < 0) return; // transient micro-shift, ignore

        // Only emit gear events when READY (gear max values aren't set until info read completes)
        if (state != State.READY) {
            frontGear = gear;
            return;
        }
        if (gear != frontGear) {
            frontGear = gear;
            Timber.d("[%s] FD gear %d/%d", deviceAddress(), frontGear, FD_GEAR_MAX);
            listener.onFrontGearChanged(device, frontGear, FD_GEAR_MAX);
        }
    }

    private void onRearGearReport(EdsPacket packet) {
        if (packet.payload.length < 5) return;
        Timber.d("[%s] RD raw payload: %s", deviceAddress(), bytesToHex(packet.payload));
        // Gear index is at byte[4]; byte[0] is a constant status byte (0x51)
        int rawGear = (packet.payload[4] & 0xFF);
        if (rawGear < 1) rawGear = 1;
        // Wheeltop reports gear 1 = smallest cog, but DrivetrainView expects
        // gear 1 = largest cog. Invert the gear index.
        int gear = rearGearMax + 1 - rawGear;
        if (gear < 1) gear = 1;
        // Only emit gear events when READY (gear max values aren't set until info read completes)
        if (state != State.READY) {
            rearGear = gear;
            return;
        }
        if (gear != rearGear) {
            rearGear = gear;
            Timber.d("[%s] RD gear %d/%d (raw=%d)", deviceAddress(), rearGear, rearGearMax, rawGear);
            listener.onRearGearChanged(device, rearGear, rearGearMax);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x ", b));
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // GATT helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void enableTxNotifications() {
        try {
            gatt.setCharacteristicNotification(txCharacteristic, true);
            BluetoothGattDescriptor cccd =
                    txCharacteristic.getDescriptor(EdsProtocol.UUID_CCCD);
            if (cccd == null) {
                Timber.e("[%s] CCCD descriptor not found on TX characteristic", deviceAddress());
                disconnect();
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(cccd)) {
                Timber.e("[%s] Failed to write CCCD descriptor", deviceAddress());
                disconnect();
            }
        } catch (SecurityException e) {
            Timber.e(e, "Permission denied enabling notifications");
            disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    private void writePacket(byte[] packet) {
        if (gatt == null || rxCharacteristic == null) return;
        try {
            rxCharacteristic.setValue(packet);
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (!gatt.writeCharacteristic(rxCharacteristic)) {
                Timber.w("[%s] writeCharacteristic returned false", deviceAddress());
            }
        } catch (SecurityException e) {
            Timber.e(e, "Permission denied on writeCharacteristic");
        }
    }

    private void retryOrDisconnect() {
        closeGatt();
        connectRetries++;
        if (connectRetries <= MAX_DIRECT_CONNECT_RETRIES) {
            Timber.i("[%s] Retrying direct connection in %dms (attempt %d)",
                    deviceAddress(), RECONNECT_DELAY_MS, connectRetries);
        } else {
            Timber.i("[%s] Switching to autoConnect (attempt %d)",
                    deviceAddress(), connectRetries);
        }
        setState(State.DISCONNECTED);
        handler.postDelayed(this::connect, RECONNECT_DELAY_MS);
    }

    private void handleDisconnect() {
        State previous = state;
        setState(State.DISCONNECTED);
        closeGatt();
        handler.removeCallbacks(rssiPollRunnable);
        handler.removeCallbacks(refreshBatteryRunnable);
        handler.removeCallbacks(connectionTimeoutRunnable);
        rxCharacteristic = null;
        txCharacteristic = null;
        if (previous != State.IDLE && previous != State.DISCONNECTED) {
            listener.onDisconnected(device);
        }
    }

    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.close();
            } catch (SecurityException e) {
                Timber.e(e, "Permission denied on gatt.close");
            }
            gatt = null;
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void setState(State newState) {
        Timber.v("[%s] %s → %s", deviceAddress(), state, newState);
        state = newState;
    }

    private String deviceAddress() {
        try {
            return device.getAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static int parseIntSafe(String s, int defaultValue) {
        if (s == null) return defaultValue;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
