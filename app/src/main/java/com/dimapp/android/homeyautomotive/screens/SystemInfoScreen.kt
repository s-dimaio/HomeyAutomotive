package com.dimapp.android.homeyautomotive.screens

import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.pm.PackageInfoCompat
import com.dimapp.android.homeyautomotive.R

/**
 * Screen displaying detailed system and environment information.
 *
 * Public class.
 * Shows Android version, Car App API level, Host details, and App version.
 * Inclues a back button in the header to return to the previous screen.
 *
 * @property carContext The context used for resource and service access.
 * @constructor Creates a [SystemInfoScreen] instance.
 */
class SystemInfoScreen(carContext: CarContext) : Screen(carContext) {

    /**
     * Returns the template to display.
     *
     * Public method.
     * Builds a [ListTemplate] with multiple informational rows and a back action.
     *
     * @public
     * @return [ListTemplate] containing AAOS system details.
     */
    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // 1. Android Version
        listBuilder.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.info_android_version))
                .addText(carContext.getString(R.string.info_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
                .build()
        )

        // 2. Car App API Level
        val carAppApiLevel = carContext.carAppApiLevel
        listBuilder.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.info_car_api_level))
                .addText(carContext.getString(R.string.info_car_api_level_value, carAppApiLevel))
                .build()
        )

        // 3. Host Information
        val hostInfo = carContext.hostInfo
        if (hostInfo != null) {
            val hostPackageName = hostInfo.packageName
            val hostVersionName = try {
                carContext.packageManager.getPackageInfo(hostPackageName, 0).versionName ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.info_host_label))
                    .addText(carContext.getString(R.string.info_host_value, hostPackageName, hostVersionName))
                    .build()
            )
        }

        // 4. App Version
        val packageInfo = carContext.packageManager.getPackageInfo(carContext.packageName, 0)
        val versionName = packageInfo.versionName ?: "N/A"
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

        listBuilder.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.info_app_version_label))
                .addText(carContext.getString(R.string.info_app_version_value, versionName, versionCode))
                .build()
        )

        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.info_header_title))
            .setStartHeaderAction(Action.BACK)
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }
}
