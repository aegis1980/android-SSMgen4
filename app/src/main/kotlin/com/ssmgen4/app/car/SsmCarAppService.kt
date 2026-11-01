package com.ssmgen4.app.car

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point the Android Auto host binds to. Declared in the manifest with
 * `androidx.car.app.category.NAVIGATION`, which is the category that grants the
 * surface access the dials are drawn through.
 */
class SsmCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds are driven by the Desktop Head Unit, which is not a known host.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session {
        Log.i(TAG, "session requested by ${hostInfo?.packageName}")
        return SsmSession()
    }

    private companion object {
        const val TAG = "SsmGen4Car"
    }
}
