package com.valterc.ki2.karoo.shifting

import com.valterc.ki2.data.device.DeviceId
import com.valterc.ki2.data.shifting.ShiftingInfo
import com.valterc.ki2.karoo.Ki2ExtensionContext
import com.valterc.ki2.karoo.RideHandler
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.math.abs

class ShiftCountHandler(extensionContext: Ki2ExtensionContext) : RideHandler(extensionContext) {

    private var previouslyUsedShiftingInfo: ShiftingInfo? = null
    private var currentDeviceId: DeviceId? = null
    private var baselineSettleCount = 0
    private val listeners: MutableList<Consumer<ShiftCountHandler>> = mutableListOf()

    var frontShiftCount = 0
        private set
    var rearShiftCount = 0
        private set
    val shiftCount
        get() = frontShiftCount + rearShiftCount

    val shiftingInfoConsumer =
        BiConsumer<DeviceId, ShiftingInfo> { deviceId: DeviceId, shiftingInfo: ShiftingInfo ->
            // When the active device changes, reset baseline so we don't
            // compare stale gear values from the previous device.
            if (deviceId != currentDeviceId) {
                currentDeviceId = deviceId
                previouslyUsedShiftingInfo = shiftingInfo
                baselineSettleCount = BASELINE_SETTLE_EVENTS
                return@BiConsumer
            }

            if (previouslyUsedShiftingInfo == null) {
                previouslyUsedShiftingInfo = shiftingInfo
                baselineSettleCount = BASELINE_SETTLE_EVENTS
                return@BiConsumer
            }

            updateShiftCount(shiftingInfo)
        }

    init {
        // Register immediately so we count shifts from the start
        extensionContext.serviceClient.registerShiftingInfoWeakListener(shiftingInfoConsumer)
    }

    private fun updateShiftCount(shiftingInfo: ShiftingInfo) {
        val previousShiftingInfo = previouslyUsedShiftingInfo ?: return

        // After a baseline reset, skip a few events to let the initialization
        // sequence settle (e.g. onRearGearChanged fires before onFrontGearChanged
        // with stale default frontGear, causing a phantom +1 FD count).
        if (baselineSettleCount > 0) {
            baselineSettleCount--
            previouslyUsedShiftingInfo = shiftingInfo
            return
        }

        frontShiftCount += abs(previousShiftingInfo.frontGear - shiftingInfo.frontGear)
        rearShiftCount += abs(previousShiftingInfo.rearGear - shiftingInfo.rearGear)

        previouslyUsedShiftingInfo = shiftingInfo
        listeners.forEach { it.accept(this) }
    }

    override fun onRideStart() {
        previouslyUsedShiftingInfo = null
        currentDeviceId = null
        baselineSettleCount = 0
        frontShiftCount = 0
        rearShiftCount = 0
    }

    override fun onRideResume() {
        previouslyUsedShiftingInfo = null
        baselineSettleCount = 0
    }

    override fun onRideEnd() {
        frontShiftCount = 0
        rearShiftCount = 0
        previouslyUsedShiftingInfo = null
        currentDeviceId = null
        baselineSettleCount = 0
    }

    companion object {
        // Number of events to skip after baseline reset to let gear values settle.
        // During device init, onRearGearChanged fires before onFrontGearChanged,
        // so the first 1-2 events may have stale default frontGear values.
        private const val BASELINE_SETTLE_EVENTS = 2
    }

    fun addListener(listener: Consumer<ShiftCountHandler>) {
        listeners.add(listener)
    }

    fun removeListener(listener: Consumer<ShiftCountHandler>) {
        listeners.remove(listener)
    }

    fun stream(): Flow<ShiftCountHandler> {
        return callbackFlow {

            val listener = Consumer<ShiftCountHandler> { handler: ShiftCountHandler ->
                trySend(handler)
            }

            addListener(listener)
            awaitClose {
                removeListener(listener)
            }
        }
    }

}