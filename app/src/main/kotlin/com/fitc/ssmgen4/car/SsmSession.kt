package com.fitc.ssmgen4.car

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fitc.ssmgen4.data.MetricSource
import com.fitc.ssmgen4.data.SsmMetricSource
import com.fitc.ssmgen4.ssm.Dials

/**
 * One connection from the Android Auto host. Creates the data source and wires the
 * surface callback before handing back the screen.
 */
class SsmSession : Session() {

    private var surfaceCallback: GaugeSurfaceCallback? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Register the surface callback again now the host is up. When Android Auto
                // reopens the app as the car connects, onCreateScreen can run before the host's
                // app service is ready; that call fails ("Could not retrieve host while
                // dispatching call setSurfaceListener"), the library drops it, and the surface
                // never arrives. Registering twice is harmless.
                surfaceCallback?.let {
                    Log.i(TAG, "session started on the car, registering for the surface")
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(it)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(TAG, "session destroyed")
                surfaceCallback?.release()
                surfaceCallback = null
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "session started, car API level ${carContext.carAppApiLevel}")

        // For bench testing on the Desktop Head Unit with no car, swap in
        // SimulatedMetricSource(Dials.CAR.map { it.metric }, carContext.getString(R.string.car_status_simulated)).
        val source: MetricSource = SsmMetricSource(carContext, Dials.CAR)

        val callback = GaugeSurfaceCallback(carContext, source, Dials.DEFAULT.map { it.metric })
        surfaceCallback = callback
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)

        return GaugeScreen(carContext, callback)
    }

    private companion object {
        const val TAG = "SsmGen4Car"
    }
}
