package com.android.mdl.appreader.transfer

import android.content.Context
import com.android.identity.ConnectionMethod
import com.android.identity.ConnectionMethodBle
import com.android.identity.DataTransportOptions
import com.android.identity.DataTransportOptions.Builder
import com.android.mdl.appreader.util.PreferencesHelper
import java.util.UUID

class ConnectionSetup(
    private val context: Context
) {

    fun getConnectionOptions(): DataTransportOptions {
        val builder = Builder()
            .setBleUseL2CAP(PreferencesHelper.isBleL2capEnabled(context))
            .setBleClearCache(PreferencesHelper.isBleClearCacheEnabled(context))
        return builder.build()
    }

    fun getConnectionMethods(): List<ConnectionMethod> {
        val connectionMethods = ArrayList<ConnectionMethod>()
        val randomUUID = UUID.randomUUID()
//        if (PreferencesHelper.isBleDataRetrievalEnabled(context)) {
//            connectionMethods.add(ConnectionMethodBle(false, true, null, randomUUID))
//        }
        if (PreferencesHelper.isBleDataRetrievalPeripheralModeEnabled()) {
            connectionMethods.add(ConnectionMethodBle(true, false, randomUUID, null))
        }
        return connectionMethods
    }
}