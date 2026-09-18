package com.dimapp.android.homeyautomotive.screens

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import com.dimapp.android.homeyautomotive.R
import com.dimapp.android.homeyautomotive.api.HomeyPayload
import com.dimapp.android.homeyautomotive.core.DependencyManager
import com.dimapp.android.homeyautomotive.geofence.GeofenceManager
import com.dimapp.android.homeyautomotive.storage.TokenStorage

/**
 * Screen that displays details for a specific Homey hub and allows the user
 * to select it as the active hub or disconnect it from the vehicle.
 *
 * This screen uses a [MessageTemplate] to display the hub status cleanly,
 * and passes the result back to [HomeySelectionScreen] by popping itself.
 */
class HomeyDetailScreen(
    carContext: CarContext,
    private val homey: HomeyPayload,
    private val isActive: Boolean,
    private val onActionComplete: () -> Unit
) : Screen(carContext) {

    private val storage = DependencyManager.getTokenStorage(carContext)

    override fun onGetTemplate(): Template {
        val statusStr = carContext.getString(if (isActive) R.string.homey_detail_active else R.string.homey_detail_inactive)
        val statusMessage = carContext.getString(R.string.homey_detail_status, statusStr)
        val message = if (homey.id == "demo") statusMessage else "ID: ${homey.id}\n$statusMessage"

        val header = Header.Builder()
            .setTitle(homey.name)
            .setStartHeaderAction(Action.BACK)
            .build()

        val builder = MessageTemplate.Builder(message)
            .setHeader(header)

        if (!isActive) {
            builder.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.homey_detail_select))
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        storage.switchActiveHomey(homey.id)
                        DependencyManager.getDeviceRepository(carContext).invalidateCache()
                        DependencyManager.getFlowRepository(carContext).invalidateCache()
                        GeofenceManager.reregisterFromStorage(carContext)
                        CarToast.makeText(
                            carContext,
                            carContext.getString(R.string.homey_selection_toast_active, homey.name),
                            CarToast.LENGTH_SHORT
                        ).show()
                        screenManager.pop()
                        onActionComplete()
                    })
                    .build()
            )
        }

        builder.addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.homey_selection_disconnect_hub))
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    storage.removeHubToken(homey.id)
                    DependencyManager.getDeviceRepository(carContext).invalidateCache()
                    DependencyManager.getFlowRepository(carContext).invalidateCache()
                    GeofenceManager.reregisterFromStorage(carContext)
                    if (storage.getAllHubTokens().isEmpty()) {
                        screenManager.popToRoot()
                    } else {
                        screenManager.pop()
                        onActionComplete()
                    }
                })
                .build()
        )

        return builder.build()
    }
}
