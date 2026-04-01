package com.valterc.ki2.services;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcelable;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.preference.PreferenceManager;

import com.valterc.ki2.R;
import com.valterc.ki2.ble.BleDeviceMapper;
import com.valterc.ki2.ble.BleManager;
import com.valterc.ki2.ble.EdsProtocol;
import com.valterc.ki2.ble.connection.BleConnectionManager;
import com.valterc.ki2.ble.connection.BleDeviceConnection;
import com.valterc.ki2.ble.connection.IBleConnectionListener;
import com.valterc.ki2.ble.scanner.BleScanner;
import com.valterc.ki2.ble.scanner.IBleResultListener;
import com.valterc.ki2.data.action.KarooActionEvent;
import com.valterc.ki2.data.connection.ConnectionDataManager;
import com.valterc.ki2.data.connection.ConnectionStatus;
import com.valterc.ki2.data.connection.ConnectionsDataManager;
import com.valterc.ki2.data.device.BatteryInfo;
import com.valterc.ki2.data.device.DeviceId;
import com.valterc.ki2.data.device.DeviceStore;
import com.valterc.ki2.data.device.DeviceType;
import com.valterc.ki2.data.info.DataType;
import com.valterc.ki2.data.info.Manufacturer;
import com.valterc.ki2.data.info.ManufacturerInfo;
import com.valterc.ki2.data.message.Message;
import com.valterc.ki2.data.message.MessageManager;
import com.valterc.ki2.data.message.RideStatusMessage;
import com.valterc.ki2.data.message.UpdateAvailableMessage;
import com.valterc.ki2.data.preferences.PreferencesStore;
import com.valterc.ki2.data.preferences.PreferencesView;
import com.valterc.ki2.data.preferences.device.DevicePreferences;
import com.valterc.ki2.data.preferences.device.DevicePreferencesStore;
import com.valterc.ki2.data.preferences.device.DevicePreferencesView;
import com.valterc.ki2.data.ride.RideStatus;
import com.valterc.ki2.data.shifting.BuzzerType;
import com.valterc.ki2.data.shifting.FrontTeethPattern;
import com.valterc.ki2.data.shifting.RearTeethPattern;
import com.valterc.ki2.data.shifting.ShiftingInfo;
import com.valterc.ki2.data.shifting.ShiftingMode;
import com.valterc.ki2.data.switches.SwitchEvent;
import com.valterc.ki2.data.update.ReleaseInfo;
import com.valterc.ki2.input.InputManager;
import com.valterc.ki2.services.callbacks.IActionCallback;
import com.valterc.ki2.services.callbacks.IBatteryCallback;
import com.valterc.ki2.services.callbacks.IConnectionDataInfoCallback;
import com.valterc.ki2.services.callbacks.IConnectionInfoCallback;
import com.valterc.ki2.services.callbacks.IDevicePreferencesCallback;
import com.valterc.ki2.services.callbacks.IManufacturerInfoCallback;
import com.valterc.ki2.services.callbacks.IMessageCallback;
import com.valterc.ki2.services.callbacks.IPreferencesCallback;
import com.valterc.ki2.services.callbacks.IScanCallback;
import com.valterc.ki2.services.callbacks.IShiftingCallback;
import com.valterc.ki2.services.callbacks.ISwitchCallback;
import com.valterc.ki2.services.debug.DebugHelper;
import com.valterc.ki2.services.handler.ServiceHandler;
import com.valterc.ki2.update.background.BackgroundUpdateChecker;
import com.valterc.ki2.update.background.IUpdateCheckerListener;
import com.valterc.ki2.update.post.PostUpdateActions;
import com.valterc.ki2.update.post.PostUpdateContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import timber.log.Timber;

public class Ki2Service extends Service
        implements IBleResultListener, IBleConnectionListener, IUpdateCheckerListener {

    // -------------------------------------------------------------------------
    // Per-device gear state (keyed by MAC address)
    // -------------------------------------------------------------------------

    private static class BleGearState {
        int frontGear    = 1;
        int frontGearMax = 1;
        int rearGear     = 1;
        int rearGearMax  = 11;
        int racingMode   = 0;
        boolean racingModeManuallySet = false;
        String leftVersion  = "";
        String rightVersion = "";
    }

    // -------------------------------------------------------------------------
    // Service setup
    // -------------------------------------------------------------------------

    public static Intent getIntent() {
        Intent serviceIntent = new Intent();
        serviceIntent.setComponent(new ComponentName("com.valterc.ki2", "com.valterc.ki2.services.Ki2Service"));
        return serviceIntent;
    }

    // -------------------------------------------------------------------------
    // Callback lists (unchanged from original)
    // -------------------------------------------------------------------------

    private final RemoteCallbackList<IConnectionDataInfoCallback> callbackListConnectionDataInfo
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IConnectionInfoCallback> callbackListConnectionInfo
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IManufacturerInfoCallback> callbackListManufacturerInfo
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IBatteryCallback> callbackListBattery
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IBatteryCallback> callbackListBatteryRd
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IBatteryCallback> callbackListShifterL
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IBatteryCallback> callbackListShifterR
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IShiftingCallback> callbackListShifting
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<ISwitchCallback> callbackListSwitch
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IActionCallback> callbackListAction
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IScanCallback> callbackListScan
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IMessageCallback> callbackListMessage
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IPreferencesCallback> callbackListPreferences
            = new RemoteCallbackList<>();
    private final RemoteCallbackList<IDevicePreferencesCallback> callbackListDevicePreferences
            = new RemoteCallbackList<>();

    // -------------------------------------------------------------------------
    // AIDL binder
    // -------------------------------------------------------------------------

    private final IKi2Service.Stub binder = new IKi2Service.Stub() {

        @Override
        public void registerConnectionDataInfoListener(IConnectionDataInfoCallback callback) {
            if (callback == null) return;
            callbackListConnectionDataInfo.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        callback.onConnectionDataInfo(m.getDeviceId(), m.buildConnectionDataInfo());
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterConnectionDataInfoListener(IConnectionDataInfoCallback callback) {
            if (callback != null) callbackListConnectionDataInfo.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerConnectionInfoListener(IConnectionInfoCallback callback) {
            if (callback == null) return;
            callbackListConnectionInfo.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        callback.onConnectionInfo(m.getDeviceId(), m.buildConnectionInfo());
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterConnectionInfoListener(IConnectionInfoCallback callback) {
            if (callback != null) callbackListConnectionInfo.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerShiftingListener(IShiftingCallback callback) {
            if (callback == null) return;
            callbackListShifting.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        ShiftingInfo info = (ShiftingInfo) m.getData(DataType.SHIFTING);
                        if (info != null) callback.onShifting(m.getDeviceId(), info);
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterShiftingListener(IShiftingCallback callback) {
            if (callback != null) callbackListShifting.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerBatteryListener(IBatteryCallback callback) {
            if (callback == null) return;
            callbackListBattery.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        BatteryInfo info = (BatteryInfo) m.getData(DataType.BATTERY);
                        if (info != null) callback.onBattery(m.getDeviceId(), info);
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterBatteryListener(IBatteryCallback callback) {
            if (callback != null) callbackListBattery.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerRdBatteryListener(IBatteryCallback callback) {
            if (callback == null) return;
            callbackListBatteryRd.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        BatteryInfo info = (BatteryInfo) m.getData(DataType.BATTERY_RD);
                        if (info != null) callback.onBattery(m.getDeviceId(), info);
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterRdBatteryListener(IBatteryCallback callback) {
            if (callback != null) callbackListBatteryRd.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerLShifterVoltageListener(IBatteryCallback callback) {
            if (callback == null) return;
            callbackListShifterL.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        BatteryInfo info = (BatteryInfo) m.getData(DataType.SHIFTER_L_VOLTAGE);
                        if (info != null) callback.onBattery(m.getDeviceId(), info);
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterLShifterVoltageListener(IBatteryCallback callback) {
            if (callback != null) callbackListShifterL.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerRShifterVoltageListener(IBatteryCallback callback) {
            if (callback == null) return;
            callbackListShifterR.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        BatteryInfo info = (BatteryInfo) m.getData(DataType.SHIFTER_R_VOLTAGE);
                        if (info != null) callback.onBattery(m.getDeviceId(), info);
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterRShifterVoltageListener(IBatteryCallback callback) {
            if (callback != null) callbackListShifterR.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerManufacturerInfoListener(IManufacturerInfoCallback callback) {
            if (callback == null) return;
            callbackListManufacturerInfo.register(callback);
            serviceHandler.postAction(() -> {
                for (ConnectionDataManager m : connectionsDataManager.getDataManagers()) {
                    try {
                        ManufacturerInfo info = (ManufacturerInfo) m.getData(DataType.MANUFACTURER_INFO);
                        if (info != null) callback.onManufacturerInfo(m.getDeviceId(), info);
                    } catch (RemoteException e) { break; }
                }
            });
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterManufacturerInfoListener(IManufacturerInfoCallback callback) {
            if (callback != null) callbackListManufacturerInfo.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerActionListener(IActionCallback callback) {
            if (callback != null) callbackListAction.register(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterActionListener(IActionCallback callback) {
            if (callback != null) callbackListAction.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerSwitchListener(ISwitchCallback callback) {
            if (callback != null) callbackListSwitch.register(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void unregisterSwitchListener(ISwitchCallback callback) {
            if (callback != null) callbackListSwitch.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public void registerScanListener(IScanCallback callback) {
            if (callback != null) callbackListScan.register(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processScan);
        }

        @Override
        public void unregisterScanListener(IScanCallback callback) {
            if (callback != null) callbackListScan.unregister(callback);
            serviceHandler.postRetriableAction(Ki2Service.this::processScan);
        }

        @Override
        public void registerMessageListener(IMessageCallback callback) {
            if (callback == null) return;
            callbackListMessage.register(callback);
            serviceHandler.postAction(() -> {
                for (Message message : messageManager.getMessages()) {
                    try { callback.onMessage(message); } catch (RemoteException e) { break; }
                }
            });
        }

        @Override
        public void unregisterMessageListener(IMessageCallback callback) {
            if (callback != null) callbackListMessage.unregister(callback);
        }

        @Override
        public void registerPreferencesListener(IPreferencesCallback callback) {
            if (callback == null) return;
            callbackListPreferences.register(callback);
            serviceHandler.postAction(() -> {
                try { callback.onPreferences(preferencesStore.getPreferences()); } catch (RemoteException e) { /* ignore */ }
            });
        }

        @Override
        public void unregisterPreferencesListener(IPreferencesCallback callback) {
            if (callback != null) callbackListPreferences.unregister(callback);
        }

        @Override
        public void registerDevicePreferencesListener(IDevicePreferencesCallback callback) {
            if (callback == null) return;
            callbackListDevicePreferences.register(callback);
            serviceHandler.postAction(() -> {
                Set<Map.Entry<DeviceId, DevicePreferencesView>> entries =
                        devicePreferencesStore.getDevicePreferences().entrySet();
                for (Map.Entry<DeviceId, DevicePreferencesView> entry : entries) {
                    try {
                        callback.onDevicePreferences(entry.getKey(), entry.getValue());
                    } catch (RemoteException e) { break; }
                }
            });
        }

        @Override
        public void unregisterDevicePreferencesListener(IDevicePreferencesCallback callback) {
            if (callback != null) callbackListDevicePreferences.unregister(callback);
        }

        @Override
        public void sendMessage(Message message) { onMessage(message); }

        @Override
        public void clearMessage(String key) { messageManager.clearMessage(key); }

        @Override
        public void clearMessages() { messageManager.clearMessages(); }

        @Override
        public List<Message> getMessages() { return messageManager.getMessages(); }

        @Override
        public PreferencesView getPreferences() { return preferencesStore.getPreferences(); }

        @Override
        public DevicePreferencesView getDevicePreferences(DeviceId deviceId) {
            return devicePreferencesStore.getDevicePreferences(deviceId);
        }

        @Override
        public void restartDeviceScan() {
            serviceHandler.postRetriableAction(() -> {
                bleScanner.stopScan();
                processScan();
            });
        }

        @Override
        public void restartDeviceConnections() {
            serviceHandler.postRetriableAction(() -> {
                bleConnectionManager.disconnectAll();
                connectionsDataManager.clearConnections();
                processConnections();
            });
        }

        @Override
        public void changeShiftMode(DeviceId deviceId) throws RemoteException {
            serviceHandler.postRetriableAction(() -> {
                String mac = BleDeviceMapper.toMacAddress(deviceId);
                BleGearState state = gearStateMap.get(mac);
                if (state == null) {
                    Timber.w("changeShiftMode: no gear state for %s", mac);
                    return;
                }

                // Toggle racing mode: 0 → 1, 1 → 0
                state.racingMode = state.racingMode == 0 ? 1 : 0;
                state.racingModeManuallySet = true;
                Timber.d("changeShiftMode: toggled to racingMode=%d for %s", state.racingMode, mac);

                // Send BLE command to set racing mode on device
                // Protocol: cmd=0x93, payload = 0x01 (race ON) or 0x02 (race OFF)
                BleDeviceConnection connection = bleConnectionManager.getConnection(mac);
                if (connection != null && connection.isReady()) {
                    byte modePayload = (byte) (state.racingMode == 1 ? 0x01 : 0x02);
                    connection.sendCommand(EdsProtocol.CMD_SET_PROTECTION_THRESHOLD,
                            new byte[]{modePayload});
                    Timber.d("changeShiftMode: sent cmd=0x93 payload=0x%02X", modePayload);
                }

                // Re-emit shifting info so the UI updates
                onData(deviceId, DataType.SHIFTING, buildShiftingInfo(state));
            });
        }

        @Override
        public void reconnectDevice(DeviceId deviceId) {
            serviceHandler.postRetriableAction(() -> {
                String mac = BleDeviceMapper.toMacAddress(deviceId);
                bleConnectionManager.disconnect(mac);
                connectionsDataManager.removeConnection(deviceId);

                connectionsDataManager.addConnection(deviceId);
                BluetoothDevice device = bleManager.getAdapter().getRemoteDevice(mac);
                bleConnectionManager.connect(device, Ki2Service.this);
            });
        }

        @Override
        public void saveDevice(DeviceId deviceId) {
            deviceStore.saveDevice(deviceId);
            serviceHandler.postRetriableAction(() -> {
                processConnections();
                DevicePreferencesView view = devicePreferencesStore.getDevicePreferences(deviceId);
                if (view != null) {
                    serviceHandler.postRetriableAction(() ->
                            broadcastData(callbackListDevicePreferences,
                                    () -> view,
                                    (cb, dp) -> cb.onDevicePreferences(deviceId, dp)));
                }
            });
        }

        @Override
        public void deleteDevice(DeviceId deviceId) {
            deviceStore.deleteDevice(deviceId);
            devicePreferencesStore.deletePreferences(deviceId);
            serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
        }

        @Override
        public List<DeviceId> getSavedDevices() {
            return new ArrayList<>(deviceStore.getDevices());
        }
    };

    // -------------------------------------------------------------------------
    // Broadcast receivers
    // -------------------------------------------------------------------------

    private final BroadcastReceiver receiverReconnectDevices = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Timber.i("Received reconnect devices broadcast");
            serviceHandler.postRetriableAction(() -> bleConnectionManager.reconnectAll(Ki2Service.this));
        }
    };

    private final BroadcastReceiver receiverInRide = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Timber.i("Received In Ride broadcast");
            serviceHandler.postRetriableAction(() -> onMessage(new RideStatusMessage(RideStatus.ONGOING)));
        }
    };

    // -------------------------------------------------------------------------
    // Service fields
    // -------------------------------------------------------------------------

    private MessageManager messageManager;
    private BleManager bleManager;
    private BleScanner bleScanner;
    private BleConnectionManager bleConnectionManager;
    private ServiceHandler serviceHandler;
    private DeviceStore deviceStore;
    private ConnectionsDataManager connectionsDataManager;
    private InputManager inputManager;
    private BackgroundUpdateChecker backgroundUpdateChecker;
    private PreferencesStore preferencesStore;
    private DevicePreferencesStore devicePreferencesStore;

    private final Map<String, BleGearState> gearStateMap = new HashMap<>();

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent arg0) { return binder; }

    @Override
    public void onCreate() {
        PostUpdateActions.executePreInit(new PostUpdateContext(this, deviceStore));

        messageManager      = new MessageManager();
        bleManager          = new BleManager(this);
        bleScanner          = new BleScanner(bleManager, this);
        bleConnectionManager = new BleConnectionManager(this);
        serviceHandler      = new ServiceHandler();
        deviceStore         = new DeviceStore(this);
        connectionsDataManager = new ConnectionsDataManager();
        inputManager        = new InputManager(this);
        backgroundUpdateChecker = new BackgroundUpdateChecker(this, this);
        preferencesStore    = new PreferencesStore(this, this::onPreferences);
        devicePreferencesStore = new DevicePreferencesStore(this, this::onDevicePreferences);

        bleManager.setAdapterStateListener(new BleManager.AdapterStateListener() {
            @Override
            public void onBluetoothEnabled() {
                serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
            }

            @Override
            public void onBluetoothDisabled() {
                bleConnectionManager.disconnectAll();
                connectionsDataManager.clearConnections();
            }
        });

        DebugHelper.init(deviceStore);
        PostUpdateActions.executePostInit(new PostUpdateContext(this, deviceStore));
        devicePreferencesStore.setDevices(deviceStore.getDevices());

        registerReceiver(receiverReconnectDevices,
                new IntentFilter("io.hammerhead.action.RECONNECT_DEVICES"), Context.RECEIVER_EXPORTED);
        registerReceiver(receiverInRide,
                new IntentFilter("io.hammerhead.action.IN_RIDE"), Context.RECEIVER_EXPORTED);
        Timber.i("Service created");
    }

    @Override
    public void onDestroy() {
        Timber.i("Service destroyed");
        bleConnectionManager.disconnectAll();
        bleManager.dispose();

        callbackListManufacturerInfo.kill();
        callbackListBattery.kill();
        callbackListBatteryRd.kill();
        callbackListShifterL.kill();
        callbackListShifterR.kill();
        callbackListShifting.kill();
        callbackListSwitch.kill();
        callbackListScan.kill();
        callbackListConnectionDataInfo.kill();
        callbackListConnectionInfo.kill();
        callbackListAction.kill();
        callbackListMessage.kill();
        backgroundUpdateChecker.dispose();
        serviceHandler.dispose();

        unregisterReceiver(receiverReconnectDevices);
        unregisterReceiver(receiverInRide);
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Scan and connection management
    // -------------------------------------------------------------------------

    private void processScan() {
        if (callbackListScan.getRegisteredCallbackCount() != 0) {
            bleScanner.startScan();
        } else {
            bleScanner.stopScan();
        }
    }

    private void processConnections() {
        Collection<DeviceId> allDevices = deviceStore.getDevices();
        devicePreferencesStore.setDevices(allDevices);

        if (hasActiveCallbacks()) {
            if (bleManager.isBluetoothReady()) {
                List<DeviceId> enabledDeviceIds = allDevices.stream()
                        .filter(id -> id.getDeviceType() == DeviceType.WHEELTOP_SHIFTING)
                        .filter(id -> new DevicePreferences(this, id).isEnabled())
                        .collect(Collectors.toList());

                List<BluetoothDevice> enabledBleDevices = enabledDeviceIds.stream()
                        .map(id -> bleManager.getAdapter()
                                .getRemoteDevice(BleDeviceMapper.toMacAddress(id)))
                        .collect(Collectors.toList());

                connectionsDataManager.addConnections(enabledDeviceIds);
                // Set CONNECTING status for devices that aren't yet connected
                for (DeviceId id : enabledDeviceIds) {
                    String mac = BleDeviceMapper.toMacAddress(id);
                    BleDeviceConnection conn = bleConnectionManager.getConnection(mac);
                    if (conn == null || !conn.isReady()) {
                        onConnectionStatus(id, ConnectionStatus.CONNECTING);
                    }
                }
                bleConnectionManager.connectOnly(enabledBleDevices, this);
                connectionsDataManager.setConnections(enabledDeviceIds);
            }
        } else {
            bleConnectionManager.disconnectAll();
            connectionsDataManager.clearConnections();
        }
    }

    private boolean hasActiveCallbacks() {
        return callbackListSwitch.getRegisteredCallbackCount() != 0
                || callbackListConnectionInfo.getRegisteredCallbackCount() != 0
                || callbackListBattery.getRegisteredCallbackCount() != 0
                || callbackListBatteryRd.getRegisteredCallbackCount() != 0
                || callbackListShifterL.getRegisteredCallbackCount() != 0
                || callbackListShifterR.getRegisteredCallbackCount() != 0
                || callbackListConnectionDataInfo.getRegisteredCallbackCount() != 0
                || callbackListManufacturerInfo.getRegisteredCallbackCount() != 0
                || callbackListShifting.getRegisteredCallbackCount() != 0
                || callbackListAction.getRegisteredCallbackCount() != 0;
    }

    // -------------------------------------------------------------------------
    // IBleResultListener — scan results
    // -------------------------------------------------------------------------

    @Override
    public void onBleDeviceFound(BluetoothDevice device, int rssi) {
        DeviceId deviceId = BleDeviceMapper.fromBluetoothDevice(device);
        serviceHandler.postAction(() -> broadcastScanResult(deviceId));
    }

    // -------------------------------------------------------------------------
    // IBleConnectionListener — connection + data events
    // -------------------------------------------------------------------------

    @Override
    public void onConnected(BluetoothDevice device) {
        DeviceId deviceId = BleDeviceMapper.fromBluetoothDevice(device);
        gearStateMap.put(device.getAddress(), new BleGearState());
        onConnectionStatus(deviceId, ConnectionStatus.ESTABLISHED);
    }

    @Override
    public void onDisconnected(BluetoothDevice device) {
        DeviceId deviceId = BleDeviceMapper.fromBluetoothDevice(device);
        gearStateMap.remove(device.getAddress());
        onConnectionStatus(deviceId, ConnectionStatus.CLOSED);
    }

    @Override
    public void onFrontGearChanged(BluetoothDevice device, int gear, int totalGears) {
        DeviceId deviceId = BleDeviceMapper.fromBluetoothDevice(device);
        BleGearState state = gearStateOrCreate(device);
        state.frontGear    = gear;
        state.frontGearMax = totalGears;
        onData(deviceId, DataType.SHIFTING, buildShiftingInfo(state));
    }

    @Override
    public void onRearGearChanged(BluetoothDevice device, int gear, int totalGears) {
        DeviceId deviceId = BleDeviceMapper.fromBluetoothDevice(device);
        BleGearState state = gearStateOrCreate(device);
        state.rearGear    = gear;
        state.rearGearMax = totalGears;
        onData(deviceId, DataType.SHIFTING, buildShiftingInfo(state));
    }

    @Override
    public void onDeviceInfo(BluetoothDevice device,
                             String leftVersion, String rightVersion,
                             int leftPowerRaw, int rightPowerRaw,
                             int fdPowerRaw, int rdPowerRaw,
                             int racingMode) {
        DeviceId deviceId = BleDeviceMapper.fromBluetoothDevice(device);
        BleGearState state = gearStateOrCreate(device);
        state.leftVersion  = leftVersion;
        state.rightVersion = rightVersion;
        if (!state.racingModeManuallySet) {
            state.racingMode = racingMode;
        }

        // Manufacturer / firmware info
        ManufacturerInfo mfrInfo = new ManufacturerInfo(
                device.getAddress(),      // componentId = MAC address
                "",                       // hardwareVersion (not provided by EDS)
                Manufacturer.EDS,
                "EDS",                    // modelNumber
                device.getAddress(),      // serialNumber = MAC address
                leftVersion + "/" + rightVersion
        );
        onData(deviceId, DataType.MANUFACTURER_INFO, mfrInfo);

        // All power values are raw voltage × 100 (e.g. 780 = 7.80 V)
        Timber.d("Battery raw: L=%d R=%d FD=%d RD=%d", leftPowerRaw, rightPowerRaw, fdPowerRaw, rdPowerRaw);
        onData(deviceId, DataType.BATTERY, new BatteryInfo(fdPowerRaw));
        onData(deviceId, DataType.BATTERY_RD, new BatteryInfo(rdPowerRaw));
        onData(deviceId, DataType.SHIFTER_L_VOLTAGE, new BatteryInfo(leftPowerRaw));
        onData(deviceId, DataType.SHIFTER_R_VOLTAGE, new BatteryInfo(rightPowerRaw));

        // Emit shifting info so racing mode shows up in the UI
        onData(deviceId, DataType.SHIFTING, buildShiftingInfo(state));
    }

    // -------------------------------------------------------------------------
    // Internal data helpers
    // -------------------------------------------------------------------------

    private BleGearState gearStateOrCreate(BluetoothDevice device) {
        return gearStateMap.computeIfAbsent(device.getAddress(), k -> new BleGearState());
    }

    private ShiftingInfo buildShiftingInfo(BleGearState state) {
        return new ShiftingInfo(
                BuzzerType.DEFAULT,
                state.frontGear, state.frontGearMax,
                state.rearGear,  state.rearGearMax,
                FrontTeethPattern.UNKNOWN,
                RearTeethPattern.UNKNOWN,
                state.racingMode == 1 ? ShiftingMode.RACE : ShiftingMode.NORMAL
        );
    }

    private void onConnectionStatus(DeviceId deviceId, ConnectionStatus connectionStatus) {
        if (!serviceHandler.isOnServiceHandlerThread()) {
            serviceHandler.postAction(() -> onConnectionStatus(deviceId, connectionStatus));
            return;
        }

        boolean sendUpdate = connectionsDataManager.onConnectionStatus(deviceId, connectionStatus);
        if (sendUpdate) {
            broadcastData(callbackListConnectionInfo,
                    () -> connectionsDataManager.buildConnectionInfo(deviceId),
                    (cb, info) -> cb.onConnectionInfo(deviceId, info));
            broadcastData(callbackListConnectionDataInfo,
                    () -> connectionsDataManager.buildConnectionDataInfo(deviceId),
                    (cb, info) -> cb.onConnectionDataInfo(deviceId, info));
        }
    }

    private void onData(DeviceId deviceId, DataType dataType, Parcelable data) {
        if (dataType == DataType.UNKNOWN) return;

        serviceHandler.postAction(() -> {
            boolean sendUpdate = connectionsDataManager.onData(deviceId, dataType, data);
            if (sendUpdate) {
                switch (dataType) {
                    case SHIFTING:
                        broadcastData(callbackListShifting,
                                () -> (ShiftingInfo) connectionsDataManager.getData(deviceId, dataType),
                                (cb, info) -> cb.onShifting(deviceId, info));
                        break;

                    case BATTERY:
                        broadcastData(callbackListBattery,
                                () -> (BatteryInfo) connectionsDataManager.getData(deviceId, dataType),
                                (cb, info) -> cb.onBattery(deviceId, info));
                        break;

                    case BATTERY_RD:
                        broadcastData(callbackListBatteryRd,
                                () -> (BatteryInfo) connectionsDataManager.getData(deviceId, dataType),
                                (cb, info) -> cb.onBattery(deviceId, info));
                        break;

                    case SHIFTER_L_VOLTAGE:
                        broadcastData(callbackListShifterL,
                                () -> (BatteryInfo) connectionsDataManager.getData(deviceId, dataType),
                                (cb, info) -> cb.onBattery(deviceId, info));
                        break;

                    case SHIFTER_R_VOLTAGE:
                        broadcastData(callbackListShifterR,
                                () -> (BatteryInfo) connectionsDataManager.getData(deviceId, dataType),
                                (cb, info) -> cb.onBattery(deviceId, info));
                        break;

                    case SWITCH:
                        SwitchEvent switchEvent = (SwitchEvent) connectionsDataManager.getData(deviceId, dataType);
                        if (switchEvent != null) {
                            broadcastData(callbackListSwitch,
                                    () -> switchEvent,
                                    (cb, se) -> cb.onSwitchEvent(deviceId, se));
                            KarooActionEvent actionEvent = inputManager.onSwitch(switchEvent);
                            if (actionEvent != null) {
                                broadcastData(callbackListAction,
                                        () -> actionEvent,
                                        (cb, ae) -> cb.onActionEvent(deviceId, ae));
                            }
                        }
                        break;

                    case MANUFACTURER_INFO:
                        broadcastData(callbackListManufacturerInfo,
                                () -> (ManufacturerInfo) connectionsDataManager.getData(deviceId, dataType),
                                (cb, info) -> cb.onManufacturerInfo(deviceId, info));
                        break;

                    default:
                        break;
                }

                broadcastData(callbackListConnectionDataInfo,
                        () -> connectionsDataManager.getDataManager(deviceId).buildConnectionDataInfo(),
                        (cb, info) -> cb.onConnectionDataInfo(deviceId, info));

                connectionsDataManager.clearEvents(deviceId);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Broadcasting
    // -------------------------------------------------------------------------

    private void broadcastScanResult(DeviceId deviceId) {
        int count = callbackListScan.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbackListScan.getBroadcastItem(i).onScanResult(deviceId);
            } catch (RemoteException e) { /* ignore */ }
        }
        callbackListScan.finishBroadcast();
    }

    private <TData,
             TCallback extends IInterface,
             TCallbackList extends RemoteCallbackList<TCallback>>
    void broadcastData(TCallbackList callbackList,
                       Supplier<TData> dataSupplier,
                       UnsafeBroadcastInvoker<TCallback, TData> broadcastConsumer) {
        int count = callbackList.getRegisteredCallbackCount();
        if (count == 0) return;

        TData data = dataSupplier.get();
        if (data == null) return;

        count = callbackList.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                broadcastConsumer.invoke(callbackList.getBroadcastItem(i), data);
            } catch (RemoteException e) { /* ignore */ }
        }
        callbackList.finishBroadcast();
    }

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    private void onMessage(Message message) {
        if (message == null) return;

        messageManager.messageReceived(message);
        serviceHandler.postRetriableAction(() ->
                broadcastData(callbackListMessage, () -> message, IMessageCallback::onMessage));

        SharedPreferences prefs;
        SharedPreferences.Editor editor;

        switch (message.getMessageType()) {
            case RIDE_STATUS:
                RideStatusMessage rideStatusMessage = RideStatusMessage.parse(message);
                if (rideStatusMessage != null) {
                    if (rideStatusMessage.getRideStatus() == RideStatus.ONGOING) {
                        serviceHandler.postRetriableAction(() -> {
                            if (!bleConnectionManager.hasActiveConnection()) {
                                bleConnectionManager.reconnectAll(this);
                            }
                        });
                    } else if (rideStatusMessage.getRideStatus() == RideStatus.FINISHED) {
                        backgroundUpdateChecker.tryCheckForUpdates();
                    }
                }
                break;

            case AUDIO_ALERT_TOGGLE:
                prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean audioEnabled = prefs.getBoolean(
                        getString(R.string.preference_audio_alerts_enabled),
                        getResources().getBoolean(R.bool.default_preference_audio_alerts_enabled));
                editor = prefs.edit();
                editor.putBoolean(getString(R.string.preference_audio_alerts_enabled), !audioEnabled);
                editor.apply();
                break;

            case AUDIO_ALERT_DISABLE:
                prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putBoolean(getString(R.string.preference_audio_alerts_enabled), false).apply();
                break;

            case AUDIO_ALERT_ENABLE:
                prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putBoolean(getString(R.string.preference_audio_alerts_enabled), true).apply();
                break;
        }
    }

    // -------------------------------------------------------------------------
    // IUpdateCheckerListener
    // -------------------------------------------------------------------------

    @Override
    public void onNewUpdateAvailable(ReleaseInfo releaseInfo) {
        serviceHandler.postAction(() -> onMessage(new UpdateAvailableMessage(releaseInfo)));
    }

    // -------------------------------------------------------------------------
    // Preferences callbacks
    // -------------------------------------------------------------------------

    private void onPreferences(PreferencesView preferencesView) {
        serviceHandler.postRetriableAction(() ->
                broadcastData(callbackListPreferences, () -> preferencesView, IPreferencesCallback::onPreferences));
    }

    private void onDevicePreferences(DeviceId deviceId, DevicePreferencesView devicePreferencesView) {
        serviceHandler.postRetriableAction(() ->
                broadcastData(callbackListDevicePreferences,
                        () -> devicePreferencesView,
                        (cb, dp) -> cb.onDevicePreferences(deviceId, dp)));
        serviceHandler.postRetriableAction(Ki2Service.this::processConnections);
    }
}
