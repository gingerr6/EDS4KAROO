package com.valterc.ki2.karoo.datatypes.text

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.valterc.ki2.data.connection.ConnectionInfo
import com.valterc.ki2.data.device.BatteryInfo
import com.valterc.ki2.data.device.DeviceId
import com.valterc.ki2.karoo.Ki2ExtensionContext
import com.valterc.ki2.karoo.datatypes.views.NotAvailable
import com.valterc.ki2.karoo.datatypes.views.TextView
import com.valterc.ki2.karoo.datatypes.views.Waiting
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.function.BiConsumer

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ShiftingBatteryPercentageDataType(private val extensionContext: Ki2ExtensionContext) :
    DataTypeImpl(extensionContext.extension, "DATATYPE_SHIFTING_BATTERY_PERCENTAGE") {

    private val glance = GlanceRemoteViews()
    private var connectionInfo: ConnectionInfo? = null
    private var rawValue: Int? = null

    private var connectionInfoListener: BiConsumer<DeviceId, ConnectionInfo>? = null
    private var voltageListener: BiConsumer<DeviceId, BatteryInfo>? = null

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))
        emitter.onNext(ShowCustomStreamState(message = "", color = null))

        connectionInfoListener = BiConsumer { _: DeviceId, info: ConnectionInfo ->
            connectionInfo = info
            CoroutineScope(Dispatchers.IO).launch { emitView(context, config, emitter) }
        }

        voltageListener = BiConsumer { _: DeviceId, info: BatteryInfo ->
            rawValue = info.value
            CoroutineScope(Dispatchers.IO).launch { emitView(context, config, emitter) }
        }

        extensionContext.serviceClient.registerConnectionInfoWeakListener(connectionInfoListener!!)
        extensionContext.serviceClient.registerBatteryInfoWeakListener(voltageListener!!)

        emitter.setCancellable {
            extensionContext.serviceClient.unregisterConnectionInfoWeakListener(connectionInfoListener!!)
            extensionContext.serviceClient.unregisterBatteryInfoWeakListener(voltageListener!!)
            connectionInfoListener = null
            voltageListener = null
        }
    }

    private suspend fun emitView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val result = when {
            rawValue != null && rawValue!! > 0 ->
                glance.compose(context, DpSize.Unspecified) {
                    TextView(
                        "${BatteryInfo.toPercentage(rawValue!!)}%",
                        dataAlignment = config.alignment,
                        fontSize = config.textSize
                    )
                }
            connectionInfo?.isClosed == true ->
                glance.compose(context, DpSize.Unspecified) {
                    NotAvailable(dataAlignment = config.alignment)
                }
            else ->
                glance.compose(context, DpSize.Unspecified) {
                    Waiting(dataAlignment = config.alignment)
                }
        }
        emitter.updateView(result.remoteViews)
    }
}
