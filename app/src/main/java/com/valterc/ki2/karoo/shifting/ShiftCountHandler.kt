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
    private val listeners: MutableList<Consumer<ShiftCountHandler>> = mutableListOf()

    var frontShiftCount = 0
        private set
    var rearShiftCount = 0
        private set
    val shiftCount
        get() = frontShiftCount + rearShiftCount

    val shiftingInfoConsumer =
        BiConsumer<DeviceId, ShiftingInfo> { _: DeviceId, shiftingInfo: ShiftingInfo ->
            if (previouslyUsedShiftingInfo == null) {
                previouslyUsedShiftingInfo = shiftingInfo
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

        frontShiftCount += abs(previousShiftingInfo.frontGear - shiftingInfo.frontGear)
        rearShiftCount += abs(previousShiftingInfo.rearGear - shiftingInfo.rearGear)

        previouslyUsedShiftingInfo = shiftingInfo
        listeners.forEach { it.accept(this) }
    }

    override fun onRideStart() {
        previouslyUsedShiftingInfo = null
        frontShiftCount = 0
        rearShiftCount = 0
    }

    override fun onRideResume() {
        // Reset baseline so first shift after resume is counted
        previouslyUsedShiftingInfo = null
    }

    override fun onRideEnd() {
        frontShiftCount = 0
        rearShiftCount = 0
        previouslyUsedShiftingInfo = null
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