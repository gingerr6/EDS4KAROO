package com.valterc.ki2.ble.scanner;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;

import com.valterc.ki2.ble.BleManager;
import com.valterc.ki2.ble.EdsProtocol;

import timber.log.Timber;

/**
 * Scans for EDS BLE devices advertising the Nordic UART Service.
 */
public class BleScanner {

    private static final int TIME_MS_ATTEMPT_START_SCAN = 2000;

    private final BleManager bleManager;
    private final IBleResultListener resultListener;
    private final Handler handler;

    private boolean scanEnabled;
    private BluetoothLeScanner leScanner;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String name = getDeviceName(result);
            Timber.v("BLE device seen: name=%s addr=%s", name, result.getDevice().getAddress());
            if (name != null && name.startsWith(EdsProtocol.DEVICE_NAME_PREFIX)) {
                Timber.d("EDS device found: %s (%s) rssi=%d",
                        name, result.getDevice().getAddress(), result.getRssi());
                resultListener.onBleDeviceFound(result.getDevice(), result.getRssi());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Timber.w("BLE scan failed: errorCode=%d", errorCode);
            leScanner = null;
            if (scanEnabled) {
                handler.postDelayed(BleScanner.this::startScanInternal,
                        (long) (TIME_MS_ATTEMPT_START_SCAN * (1 + 2 * Math.random())));
            }
        }
    };

    public BleScanner(BleManager bleManager, IBleResultListener resultListener) {
        this.bleManager = bleManager;
        this.resultListener = resultListener;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Start scanning for EDS devices. Safe to call multiple times. */
    public void startScan() {
        if (scanEnabled) return;
        scanEnabled = true;
        handler.post(this::startScanInternal);
    }

    private void startScanInternal() {
        if (!scanEnabled || leScanner != null) return;

        if (!bleManager.isBluetoothReady()) {
            Timber.w("Bluetooth not ready, will retry scan");
            handler.postDelayed(this::startScanInternal,
                    (long) (TIME_MS_ATTEMPT_START_SCAN * (1 + 2 * Math.random())));
            return;
        }

        BluetoothAdapter adapter = bleManager.getAdapter();
        leScanner = adapter.getBluetoothLeScanner();
        if (leScanner == null) {
            Timber.w("LE scanner not available, will retry");
            handler.postDelayed(this::startScanInternal,
                    (long) (TIME_MS_ATTEMPT_START_SCAN * (1 + 2 * Math.random())));
            return;
        }

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            // No service UUID filter — EDS devices don't advertise NUS in their ad packet.
            // Name-prefix filtering is done in onScanResult.
            leScanner.startScan(null, settings, scanCallback);
            Timber.i("BLE scan started");
        } catch (SecurityException e) {
            Timber.e(e, "Missing BLE scan permission");
            leScanner = null;
        }
    }

    /** Stop scanning. */
    public void stopScan() {
        scanEnabled = false;
        handler.post(this::stopScanInternal);
    }

    private void stopScanInternal() {
        scanEnabled = false;
        BluetoothLeScanner scanner = this.leScanner;
        this.leScanner = null;
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
                Timber.i("BLE scan stopped");
            } catch (SecurityException e) {
                Timber.e(e, "Missing BLE scan permission on stop");
            }
        }
    }

    private String getDeviceName(ScanResult result) {
        // Prefer the advertised name from the scan record
        ScanRecord record = result.getScanRecord();
        if (record != null && record.getDeviceName() != null) {
            return record.getDeviceName();
        }
        try {
            return result.getDevice().getName();
        } catch (SecurityException e) {
            return null;
        }
    }
}
