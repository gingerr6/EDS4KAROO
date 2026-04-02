package com.valterc.ki2.karoo.service.device;

import android.content.Context;

import com.valterc.ki2.data.action.KarooActionEvent;
import com.valterc.ki2.data.connection.ConnectionInfo;
import com.valterc.ki2.data.connection.ConnectionStatus;
import com.valterc.ki2.data.device.BatteryInfo;
import com.valterc.ki2.data.device.DeviceId;
import com.valterc.ki2.data.preferences.device.DevicePreferencesView;
import com.valterc.ki2.data.shifting.ShiftingInfo;
import com.valterc.ki2.karoo.service.listeners.KeyedDataStreamWeakListenerList;
import com.valterc.ki2.karoo.service.listeners.BiDataStreamWeakListenerList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class DeviceDataRouter {

    private final BiDataStreamWeakListenerList<DeviceId, ConnectionInfo> connectionInfoListeners;
    private final BiDataStreamWeakListenerList<DeviceId, BatteryInfo> batteryInfoListeners;
    private final BiDataStreamWeakListenerList<DeviceId, BatteryInfo> rdBatteryInfoListeners;
    private final BiDataStreamWeakListenerList<DeviceId, BatteryInfo> lShifterVoltageListeners;
    private final BiDataStreamWeakListenerList<DeviceId, BatteryInfo> rShifterVoltageListeners;
    private final BiDataStreamWeakListenerList<DeviceId, ShiftingInfo> shiftingInfoListeners;
    private final BiDataStreamWeakListenerList<DeviceId, DevicePreferencesView> devicePreferencesListener;


    private final KeyedDataStreamWeakListenerList<DeviceId, ConnectionInfo> connectionInfoUnfilteredListeners;
    private final KeyedDataStreamWeakListenerList<DeviceId, BatteryInfo> batteryInfoUnfilteredListeners;
    private final KeyedDataStreamWeakListenerList<DeviceId, BatteryInfo> rdBatteryInfoUnfilteredListeners;
    private final KeyedDataStreamWeakListenerList<DeviceId, BatteryInfo> lShifterVoltageUnfilteredListeners;
    private final KeyedDataStreamWeakListenerList<DeviceId, BatteryInfo> rShifterVoltageUnfilteredListeners;
    private final KeyedDataStreamWeakListenerList<DeviceId, ShiftingInfo> shiftingInfoUnfilteredListeners;
    private final KeyedDataStreamWeakListenerList<DeviceId, DevicePreferencesView> devicePreferencesUnfilteredListener;

    private final BiDataStreamWeakListenerList<DeviceId, KarooActionEvent> actionEventListeners;

    private final Context context;
    private final Map<DeviceId, DeviceData> deviceDataMap;
    private DeviceId currentDeviceId;

    public DeviceDataRouter(Context context) {
        this.context = context;

        connectionInfoListeners = new BiDataStreamWeakListenerList<>();
        batteryInfoListeners = new BiDataStreamWeakListenerList<>();
        rdBatteryInfoListeners = new BiDataStreamWeakListenerList<>();
        lShifterVoltageListeners = new BiDataStreamWeakListenerList<>();
        rShifterVoltageListeners = new BiDataStreamWeakListenerList<>();
        shiftingInfoListeners = new BiDataStreamWeakListenerList<>();
        devicePreferencesListener = new BiDataStreamWeakListenerList<>();

        connectionInfoUnfilteredListeners = new KeyedDataStreamWeakListenerList<>();
        batteryInfoUnfilteredListeners = new KeyedDataStreamWeakListenerList<>();
        rdBatteryInfoUnfilteredListeners = new KeyedDataStreamWeakListenerList<>();
        lShifterVoltageUnfilteredListeners = new KeyedDataStreamWeakListenerList<>();
        rShifterVoltageUnfilteredListeners = new KeyedDataStreamWeakListenerList<>();
        shiftingInfoUnfilteredListeners = new KeyedDataStreamWeakListenerList<>();
        devicePreferencesUnfilteredListener = new KeyedDataStreamWeakListenerList<>();

        actionEventListeners = new BiDataStreamWeakListenerList<>();

        deviceDataMap = new HashMap<>();
    }

    public void registerConnectionInfoWeakListener(BiConsumer<DeviceId, ConnectionInfo> connectionInfoConsumer) {
        connectionInfoListeners.addListener(connectionInfoConsumer);
    }

    public void unregisterConnectionInfoWeakListener(BiConsumer<DeviceId, ConnectionInfo> connectionInfoConsumer) {
        connectionInfoListeners.removeListener(connectionInfoConsumer);
    }

    public void registerUnfilteredConnectionInfoWeakListener(BiConsumer<DeviceId, ConnectionInfo> connectionInfoConsumer) {
        connectionInfoUnfilteredListeners.addListener(connectionInfoConsumer);
    }

    public void unregisterUnfilteredConnectionInfoWeakListener(BiConsumer<DeviceId, ConnectionInfo> connectionInfoConsumer) {
        connectionInfoUnfilteredListeners.removeListener(connectionInfoConsumer);
    }

    public boolean hasConnectionInfoListeners() {
        return connectionInfoListeners.hasListeners() || connectionInfoUnfilteredListeners.hasListeners();
    }

    public void registerBatteryInfoWeakListener(BiConsumer<DeviceId, BatteryInfo> batteryInfoConsumer) {
        batteryInfoListeners.addListener(batteryInfoConsumer);
    }

    public void unregisterBatteryInfoWeakListener(BiConsumer<DeviceId, BatteryInfo> batteryInfoConsumer) {
        batteryInfoListeners.removeListener(batteryInfoConsumer);
    }

    public void registerUnfilteredBatteryInfoWeakListener(BiConsumer<DeviceId, BatteryInfo> batteryInfoConsumer) {
        batteryInfoUnfilteredListeners.addListener(batteryInfoConsumer);
    }

    public void unregisterUnfilteredBatteryInfoWeakListener(BiConsumer<DeviceId, BatteryInfo> batteryInfoConsumer) {
        batteryInfoUnfilteredListeners.removeListener(batteryInfoConsumer);
    }

    public boolean hasBatteryInfoListeners() {
        return batteryInfoListeners.hasListeners() || batteryInfoUnfilteredListeners.hasListeners();
    }

    public void registerRdBatteryInfoWeakListener(BiConsumer<DeviceId, BatteryInfo> batteryInfoConsumer) {
        rdBatteryInfoListeners.addListener(batteryInfoConsumer);
    }

    public void unregisterRdBatteryInfoWeakListener(BiConsumer<DeviceId, BatteryInfo> batteryInfoConsumer) {
        rdBatteryInfoListeners.removeListener(batteryInfoConsumer);
    }

    public boolean hasRdBatteryInfoListeners() {
        return rdBatteryInfoListeners.hasListeners() || rdBatteryInfoUnfilteredListeners.hasListeners();
    }

    public void registerLShifterVoltageWeakListener(BiConsumer<DeviceId, BatteryInfo> consumer) {
        lShifterVoltageListeners.addListener(consumer);
    }

    public void unregisterLShifterVoltageWeakListener(BiConsumer<DeviceId, BatteryInfo> consumer) {
        lShifterVoltageListeners.removeListener(consumer);
    }

    public boolean hasLShifterVoltageListeners() {
        return lShifterVoltageListeners.hasListeners() || lShifterVoltageUnfilteredListeners.hasListeners();
    }

    public void registerRShifterVoltageWeakListener(BiConsumer<DeviceId, BatteryInfo> consumer) {
        rShifterVoltageListeners.addListener(consumer);
    }

    public void unregisterRShifterVoltageWeakListener(BiConsumer<DeviceId, BatteryInfo> consumer) {
        rShifterVoltageListeners.removeListener(consumer);
    }

    public boolean hasRShifterVoltageListeners() {
        return rShifterVoltageListeners.hasListeners() || rShifterVoltageUnfilteredListeners.hasListeners();
    }

    public void registerShiftingInfoWeakListener(BiConsumer<DeviceId, ShiftingInfo> shiftingInfoConsumer) {
        shiftingInfoListeners.addListener(shiftingInfoConsumer);
    }

    public void unregisterShiftingInfoWeakListener(BiConsumer<DeviceId, ShiftingInfo> shiftingInfoConsumer) {
        shiftingInfoListeners.removeListener(shiftingInfoConsumer);
    }

    public void registerUnfilteredShiftingInfoWeakListener(BiConsumer<DeviceId, ShiftingInfo> shiftingInfoConsumer) {
        shiftingInfoUnfilteredListeners.addListener(shiftingInfoConsumer);
    }

    public void unregisterUnfilteredShiftingInfoWeakListener(BiConsumer<DeviceId, ShiftingInfo> shiftingInfoConsumer) {
        shiftingInfoUnfilteredListeners.removeListener(shiftingInfoConsumer);
    }

    public boolean hasShiftingInfoListeners() {
        return shiftingInfoListeners.hasListeners() || shiftingInfoUnfilteredListeners.hasListeners();
    }

    public void registerDevicePreferencesWeakListener(BiConsumer<DeviceId, DevicePreferencesView> devicePreferencesConsumer) {
        devicePreferencesListener.addListener(devicePreferencesConsumer);
    }

    public void unregisterDevicePreferencesWeakListener(BiConsumer<DeviceId, DevicePreferencesView> devicePreferencesConsumer) {
        devicePreferencesListener.removeListener(devicePreferencesConsumer);
    }

    public void registerUnfilteredDevicePreferencesWeakListener(BiConsumer<DeviceId, DevicePreferencesView> devicePreferencesConsumer) {
        devicePreferencesUnfilteredListener.addListener(devicePreferencesConsumer);
    }

    public void unregisterUnfilteredDevicePreferencesWeakListener(BiConsumer<DeviceId, DevicePreferencesView> devicePreferencesConsumer) {
        devicePreferencesUnfilteredListener.removeListener(devicePreferencesConsumer);
    }

    public boolean hasDevicePreferencesListeners() {
        return devicePreferencesListener.hasListeners() || devicePreferencesUnfilteredListener.hasListeners();
    }

    public void registerActionEventListener(BiConsumer<DeviceId, KarooActionEvent> actionEventConsumer) {
        actionEventListeners.addListener(actionEventConsumer);
    }

    public void unregisterActionEventListener(BiConsumer<DeviceId, KarooActionEvent> actionEventConsumer) {
        actionEventListeners.removeListener(actionEventConsumer);
    }

    public boolean hasKeyListeners() {
        return actionEventListeners.hasListeners();
    }

    private void attemptToUpdateCurrentDevice() {
        if (tryUpdateCurrentDevice()) {
            DeviceData newDeviceData = deviceDataMap.computeIfAbsent(currentDeviceId, DeviceData::new);

            if (newDeviceData.getConnectionInfo() != null) {
                connectionInfoListeners.pushData(currentDeviceId, newDeviceData.getConnectionInfo());
            }

            if (newDeviceData.getBatteryInfo() != null) {
                batteryInfoListeners.pushData(currentDeviceId, newDeviceData.getBatteryInfo());
            }

            if (newDeviceData.getShiftingInfo() != null) {
                shiftingInfoListeners.pushData(currentDeviceId, newDeviceData.getShiftingInfo());
            }

            if (newDeviceData.getPreferences() != null) {
                devicePreferencesListener.pushData(currentDeviceId, newDeviceData.getPreferences());
            }
        }
    }

    private boolean tryUpdateCurrentDevice() {
        List<DeviceData> sortedDeviceData = deviceDataMap.values().stream()
                .filter(deviceData -> {
                    DevicePreferencesView preferences = deviceData.getPreferences();
                    return preferences != null && preferences.isEnabled(context) && !preferences.isSwitchEventsOnly(context);
                }).sorted((a, b) -> {
                    DevicePreferencesView preferencesA = a.getPreferences();
                    DevicePreferencesView preferencesB = b.getPreferences();

                    assert preferencesA != null;
                    assert preferencesB != null;

                    int priority = preferencesA.getPriority(context) - preferencesB.getPriority(context);
                    if (priority != 0) {
                        return priority;
                    }

                    return preferencesA.getName(context).compareToIgnoreCase(preferencesB.getName(context));
                }).collect(Collectors.toList());

        for (DeviceData deviceData : sortedDeviceData) {
            ConnectionInfo connectionInfo = deviceData.getConnectionInfo();
            if (connectionInfo != null && connectionInfo.getConnectionStatus() == ConnectionStatus.ESTABLISHED) {
                if (!Objects.equals(currentDeviceId, deviceData.getDeviceId())) {
                    currentDeviceId = deviceData.getDeviceId();
                    return true;
                }

                return false;
            }
        }

        DeviceData currentDeviceData = deviceDataMap.get(currentDeviceId);
        if (currentDeviceData != null) {
            ConnectionInfo currentDeviceConnectionInfo = currentDeviceData.getConnectionInfo();
            if (currentDeviceConnectionInfo != null &&
                    currentDeviceConnectionInfo.getConnectionStatus() == ConnectionStatus.CONNECTING) {
                return false;
            }
        }

        for (DeviceData deviceData : sortedDeviceData) {
            if (!Objects.equals(currentDeviceId, deviceData.getDeviceId())) {
                currentDeviceId = deviceData.getDeviceId();
                return true;
            }

            return false;
        }

        return false;
    }

    public void clearDevice(DeviceId deviceId) {
        deviceDataMap.remove(deviceId);

        if (Objects.equals(deviceId, currentDeviceId)) {
            currentDeviceId = null;

            // Clear cached last-data in filtered listeners so stale values aren't replayed
            connectionInfoListeners.clearLastData();
            batteryInfoListeners.clearLastData();
            rdBatteryInfoListeners.clearLastData();
            lShifterVoltageListeners.clearLastData();
            rShifterVoltageListeners.clearLastData();
            shiftingInfoListeners.clearLastData();
            devicePreferencesListener.clearLastData();
        }

        // Remove this device's entry from unfiltered (keyed) listeners
        connectionInfoUnfilteredListeners.removeKey(deviceId);
        batteryInfoUnfilteredListeners.removeKey(deviceId);
        rdBatteryInfoUnfilteredListeners.removeKey(deviceId);
        lShifterVoltageUnfilteredListeners.removeKey(deviceId);
        rShifterVoltageUnfilteredListeners.removeKey(deviceId);
        shiftingInfoUnfilteredListeners.removeKey(deviceId);
        devicePreferencesUnfilteredListener.removeKey(deviceId);
    }

    public void onConnectionInfo(DeviceId deviceId, ConnectionInfo connectionInfo) {
        DeviceData deviceData = deviceDataMap.computeIfAbsent(deviceId, DeviceData::new);
        deviceData.setConnectionInfo(connectionInfo);

        if (Objects.equals(deviceId, currentDeviceId)) {
            connectionInfoListeners.pushData(deviceId, connectionInfo);
        }

        connectionInfoUnfilteredListeners.pushData(deviceId, connectionInfo);

        // When a device disconnects, clear its cached data so stale values
        // are not replayed to listeners when a different device connects.
        if (connectionInfo.getConnectionStatus() == ConnectionStatus.CLOSED) {
            clearDevice(deviceId);
        }

        attemptToUpdateCurrentDevice();
    }

    public void onBattery(DeviceId deviceId, BatteryInfo batteryInfo) {
        DeviceData deviceData = deviceDataMap.computeIfAbsent(deviceId, DeviceData::new);
        deviceData.setBatteryInfo(batteryInfo);

        if (Objects.equals(deviceId, currentDeviceId)) {
            batteryInfoListeners.pushData(deviceId, batteryInfo);
        }

        batteryInfoUnfilteredListeners.pushData(deviceId, batteryInfo);
    }

    public void onRdBattery(DeviceId deviceId, BatteryInfo batteryInfo) {
        if (Objects.equals(deviceId, currentDeviceId)) {
            rdBatteryInfoListeners.pushData(deviceId, batteryInfo);
        }

        rdBatteryInfoUnfilteredListeners.pushData(deviceId, batteryInfo);
    }

    public void onLShifterVoltage(DeviceId deviceId, BatteryInfo info) {
        if (Objects.equals(deviceId, currentDeviceId)) {
            lShifterVoltageListeners.pushData(deviceId, info);
        }
        lShifterVoltageUnfilteredListeners.pushData(deviceId, info);
    }

    public void onRShifterVoltage(DeviceId deviceId, BatteryInfo info) {
        if (Objects.equals(deviceId, currentDeviceId)) {
            rShifterVoltageListeners.pushData(deviceId, info);
        }
        rShifterVoltageUnfilteredListeners.pushData(deviceId, info);
    }

    public void onShifting(DeviceId deviceId, ShiftingInfo shiftingInfo) {
        DeviceData deviceData = deviceDataMap.computeIfAbsent(deviceId, DeviceData::new);
        deviceData.setShiftingInfo(shiftingInfo);

        if (Objects.equals(deviceId, currentDeviceId)) {
            shiftingInfoListeners.pushData(deviceId, shiftingInfo);
        }

        shiftingInfoUnfilteredListeners.pushData(deviceId, shiftingInfo);
    }

    public void onDevicePreferences(DeviceId deviceId, DevicePreferencesView preferences) {
        DeviceData deviceData = deviceDataMap.computeIfAbsent(deviceId, DeviceData::new);
        deviceData.setPreferences(preferences);

        if (Objects.equals(deviceId, currentDeviceId)) {
            devicePreferencesListener.pushData(deviceId, preferences);
        }

        attemptToUpdateCurrentDevice();

        devicePreferencesUnfilteredListener.pushData(deviceId, preferences);
    }

    public void onActionEvent(DeviceId deviceId, KarooActionEvent actionEvent) {
        actionEventListeners.pushData(deviceId, actionEvent, false);
    }

}
