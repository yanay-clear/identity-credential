package com.android.mdl.appreader.util

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.navigation.NavDeepLinkBuilder
import com.android.identity.DataTransport
import com.android.identity.NfcEngagementHelper
import com.android.identity.PresentationHelper
import com.android.identity.PresentationSession
import com.android.mdl.appreader.MainActivity
import com.android.mdl.appreader.R
import com.android.mdl.appreader.document.RequestDocumentList
import com.android.mdl.appreader.document.RequestMdlUsTransportation
import com.android.mdl.appreader.transfer.Communication
import com.android.mdl.appreader.transfer.ConnectionSetup
import com.android.mdl.appreader.transfer.CredentialStore
import com.android.mdl.appreader.transfer.SessionSetup
import com.android.mdl.appreader.transfer.TransferManager

class NfcReaderEngagementHandler : HostApduService() {

  private lateinit var engagementHelper: NfcEngagementHelper
  private lateinit var session: PresentationSession
  private lateinit var communication: Communication
  private lateinit var transferManager: TransferManager
  private var presentation: PresentationHelper? = null

  private fun launchTransferScreen() {
    val launchAppIntent = Intent(applicationContext, MainActivity::class.java)
    launchAppIntent.action = Intent.ACTION_VIEW
    launchAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    launchAppIntent.addCategory(Intent.CATEGORY_DEFAULT)
    launchAppIntent.addCategory(Intent.CATEGORY_BROWSABLE)
    applicationContext.startActivity(launchAppIntent)

    val requestDocumentList = RequestDocumentList()
    requestDocumentList.addRequestDocument(RequestMdlUsTransportation)
    val args = Bundle()
    args.putSerializable("requestDocumentList", requestDocumentList)
    val pendingIntent = NavDeepLinkBuilder(applicationContext)
      .setGraph(R.navigation.nav_graph)
      .setArguments(args)
      .setDestination(R.id.Transfer)
      .setComponentName(MainActivity::class.java)
      .createPendingIntent()
    pendingIntent.send(applicationContext, 0, null)
    transferManager.updateStatus(TransferStatus.CONNECTING)
  }

  private val nfcEngagementListener = object : NfcEngagementHelper.Listener {
    override fun onDeviceConnecting() {
      log("Engagement Listener: Device Connecting. Launching Transfer Screen")
      launchTransferScreen()
    }

    override fun onDeviceConnected(transport: DataTransport) {

      val data = transport.message
      if (data == null) {
       log("onMessageReceived but no message")
        return
      }

      if (presentation != null) {
        log("Engagement Listener: Device Connected -> ignored due to active presentation")
        return
      }

      log("Engagement Listener: Device Connected via NfcReaderEngagementHandler")
      val builder = PresentationHelper.Builder(
        applicationContext,
        presentationListener,
        applicationContext.mainExecutor(),
        session
      )
      builder.useForwardEngagement(
        transport,
        engagementHelper.deviceEngagement,
        engagementHelper.handover
      )
      presentation = builder.build()

      presentation?.setSendSessionTerminationMessage(true)

      communication.setupPresentation(presentation!!)
      presentation?.setReverseReaderKey(session.ephemeralKeyPair, data)

      val sessionEncryptionReader = transferManager.initVerificationHelperWithNFCReverseEngagement(
        transport = transport,
        encodedDeviceKey = data,
        ephemeralKeyPair = session.ephemeralKeyPair
      )
      presentation?.setSessionEnryptionReader(sessionEncryptionReader)
      transferManager.updateStatus(TransferStatus.CONNECTED)
    }

    override fun onError(error: Throwable) {
      log("Engagement Listener: onError -> ${error.message}")
      transferManager.updateStatus(TransferStatus.ERROR)
      engagementHelper.close()
    }
  }


  private val presentationListener = object : PresentationHelper.Listener {
    override fun onDeviceKey() {
      log("Presentation Listener: onDeviceKey")
    }

    override fun onDeviceResponse(deviceRequestBytes: ByteArray) {
      log("Presentation Listener: OnDeviceRequest")

      transferManager.setDeviceResponse(deviceRequestBytes)
    }

    override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {
      log("Presentation Listener: onDeviceDisconnected")
      transferManager.updateStatus(TransferStatus.DISCONNECTED)
    }

    override fun onError(error: Throwable) {
      log("Presentation Listener: onError -> ${error.message}")
      transferManager.updateStatus(TransferStatus.ERROR)
    }
  }

  override fun onCreate() {
    super.onCreate()
    log("onCreate")
    session = SessionSetup(CredentialStore(applicationContext)).createSession()
    communication = Communication.getInstance(applicationContext)
    transferManager = TransferManager.getInstance(applicationContext)
    transferManager.setCommunication(session, communication)
    transferManager.usingReverseEngagement = true
    val connectionSetup = ConnectionSetup(applicationContext)
    val builder = NfcEngagementHelper.Builder(
      applicationContext,
      session,
      connectionSetup.getConnectionOptions(),
      nfcEngagementListener,
      applicationContext.mainExecutor()
    )
    if (PreferencesHelper.shouldUseStaticHandover()) {
      builder.useStaticHandover(connectionSetup.getConnectionMethods())
    } else {
      builder.useNegotiatedHandover()
    }
    builder.useRole(DataTransport.ROLE_MDOC_READER)
    engagementHelper = builder.build()
  }

  override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
    log("processCommandApdu: Command-> ${FormatUtil.encodeToString(commandApdu)}")
    return engagementHelper.nfcProcessCommandApdu(commandApdu)
  }

  override fun onDeactivated(reason: Int) {
    log("onDeactivated: reason-> $reason")
    engagementHelper.nfcOnDeactivated(reason)

    // We need to close the NfcEngagementHelper but if we're doing it as the reader moves
    // out of the field, it's too soon as it may take a couple of seconds to establish
    // the connection, triggering onDeviceConnected() callback above.
    //
    // In fact, the reader _could_ actually take a while to establish the connection...
    // for example the UI in the mdoc doc reader might have the operator pick the
    // transport if more than one is offered. In fact this is exactly what we do in
    // our mdoc reader.
    //
    // So we give the reader 15 seconds to do this...
    //
    val timeoutSeconds = 15
    Handler(Looper.getMainLooper()).postDelayed(
      {
        if (presentation == null) {
          logWarning("reader didn't connect inside $timeoutSeconds seconds, closing")
          engagementHelper.close()
        }
      }, timeoutSeconds * 1000L
    )
  }
}