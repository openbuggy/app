package com.example.robot

import android.Manifest
import android.hardware.usb.UsbManager
import android.location.Location
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.robot.ui.theme.RobotTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Queue
import java.util.UUID


class MainActivity : ComponentActivity() {
    companion object {
        private val LogTag = MainActivity::class.java.simpleName
    }

    private val ID = UUID.randomUUID().toString()

    private lateinit var webSocket: WebSocket
    private val webSocketSendChannel = Channel<String>()
    private lateinit var controllerId: String
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var rtcConfig: PeerConnection.RTCConfiguration
    private lateinit var videoTrack: VideoTrack
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private data class Control(val throttle: Int, val steering: Int)

    private val controlChannel = Channel<Control>(CONFLATED)

    private inner class DefaultSdpObserver(
        val createSuccessCallback: (SessionDescription?) -> Unit = {},
        val setSuccessCallback: () -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {
            Log.d(LogTag, "onCreateSuccess")
            createSuccessCallback(p0)
        }

        override fun onCreateFailure(p0: String?) {
            Log.e(LogTag, "onCreateFailure")
        }

        override fun onSetSuccess() {
            Log.d(LogTag, "onSetSuccess")
            setSuccessCallback()
        }

        override fun onSetFailure(p0: String?) {
            Log.e(LogTag, "onSetFailure $p0")
        }
    }

    private inner class SignalerListener(val reconnectCallback: () -> Unit) : WebSocketListener() {
        private var sendJob: Job? = null
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(LogTag, "received message from signaler $text")
            val message = JSONObject(text)
            if (message.getString("type") == "rtc") {
                val rtcMessage = message.getJSONObject("message")
                if (rtcMessage.has("candidate")) {
                    peerConnection!!.addIceCandidate(
                        IceCandidate(
                            rtcMessage.getString("sdpMid"),
                            rtcMessage.getInt("sdpMLineIndex"), rtcMessage.getString("candidate")
                        )
                    )
                } else if (rtcMessage.has("sdp")) {
                    controllerId = message.getString("from")
                    val offer = SessionDescription(
                        SessionDescription.Type.OFFER, rtcMessage.getString("sdp")
                    )
                    peerConnection =
                        peerConnectionFactory.createPeerConnection(
                            rtcConfig,
                            PeerConnectionObserver()
                        )!!
                    peerConnection!!.addTrack(videoTrack)
                    peerConnection!!.setRemoteDescription(DefaultSdpObserver(setSuccessCallback = {
                        peerConnection!!.createAnswer(DefaultSdpObserver(createSuccessCallback = { answer ->
                            peerConnection!!.setLocalDescription(
                                DefaultSdpObserver(setSuccessCallback = {
                                    val messageAnswer = JSONObject()
                                        .put("to", controllerId)
                                        .put(
                                            "message", JSONObject()
                                                .put(
                                                    "type",
                                                    peerConnection!!.localDescription.type.canonicalForm()
                                                )
                                                .put(
                                                    "sdp",
                                                    peerConnection!!.localDescription.description
                                                )
                                        )
                                        .toString()
                                    Log.d(LogTag, "send answer $messageAnswer")
                                    this@MainActivity.lifecycleScope.launch {
                                        webSocketSendChannel.send(messageAnswer)
                                    }
                                }), answer
                            )
                        }), MediaConstraints())
                    }), offer)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(LogTag, "websocket failure", t)
            sendJob?.cancel()
            Thread.sleep(1000)
            reconnectCallback()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, "peer requested closing")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.e(LogTag, "websocket closed")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.e(LogTag, "websocket open")
            sendJob = this@MainActivity.lifecycleScope.launch(Dispatchers.Default) {
                while (true) {
                    webSocket.send(webSocketSendChannel.receive())
                }
            }
        }
    }

    private inner class DataChannelObserver : DataChannel.Observer {
        override fun onBufferedAmountChange(p0: Long) {
            Log.d(LogTag, "onBufferedAmountChange")
        }

        override fun onStateChange() {
            Log.d(LogTag, "onStateChange")
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            val text = StandardCharsets.UTF_8.decode(buffer!!.data).toString()
            //Log.d(LogTag, "datachannel message $text")
            val message = JSONObject(text)
            this@MainActivity.lifecycleScope.launch {
                controlChannel.send(Control(message.getInt("throttle"), message.getInt("steering")))
            }
        }
    }

    private inner class PeerConnectionObserver : PeerConnection.Observer {
        override fun onDataChannel(channel: DataChannel?) {
            Log.d(LogTag, "onDataChannel")
            dataChannel = channel
            dataChannel!!.registerObserver(DataChannelObserver())
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            Log.d(LogTag, "onIceConnectionReceivingChange")
        }

        override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
            Log.d(LogTag, "iceconnectionstate changed to $iceConnectionState")
            if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                peerConnection!!.close()
                dataChannel?.close()
                peerConnection = null
                dataChannel = null

                val message = JSONObject()
                    .put("to", controllerId)
                    .put("message", "failure")
                    .toString()
                this@MainActivity.lifecycleScope.launch {
                    Log.d(LogTag, "send $message")
                    webSocketSendChannel.send(message)
                }
            }
        }

        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            Log.d(LogTag, "onIceGatheringChange")
        }

        override fun onAddStream(p0: MediaStream?) {
            Log.d(LogTag, "onAddStream")
        }

        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            Log.d(LogTag, "onSignalingChange")
        }

        override fun onRemoveStream(p0: MediaStream?) {
            Log.d(LogTag, "onRemoveStream")
        }

        override fun onRenegotiationNeeded() {
            Log.d(LogTag, "onRenegotiationNeeded")
        }

        override fun onIceCandidate(iceCandidate: IceCandidate?) {
            Log.d(LogTag, "onIceCandidate")
            val message = JSONObject()
                .put("to", controllerId)
                .put(
                    "message", JSONObject()
                        .put("candidate", iceCandidate!!.sdp)
                        .put("sdpMid", iceCandidate.sdpMid)
                        .put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
                )
                .toString()
            Log.d(LogTag, "send ice candidate $message")
            this@MainActivity.lifecycleScope.launch {
                webSocketSendChannel.send(message)
            }
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
            Log.d(LogTag, "onIceCandidatesRemoved")
        }
    }

    private inner class UsbListener : SerialInputOutputManager.Listener {
        private var queue: Queue<Byte> = LinkedList()
        override fun onNewData(data: ByteArray?) {
            if (dataChannel?.state() == DataChannel.State.OPEN) {
                data!!.forEach { queue.add(it) }
                if (queue.size > 3) {
                    Log.d(LogTag, "received usb ${data}")
                    val message = JSONObject()
                        .put("type", "battery")
                        .put(
                            "voltageA",
                            (queue.remove().toInt() and 0xff) or ((queue.remove()
                                .toInt() and 0xff) shl 8)
                        )
                        .put(
                            "voltageB",
                            (queue.remove().toInt() and 0xff) or ((queue.remove()
                                .toInt() and 0xff) shl 8)
                        )
                        .toString()
                    Log.d(LogTag, "send datachannel $message")
                    dataChannel!!.send(
                        DataChannel.Buffer(
                            ByteBuffer.wrap(
                                message.toByteArray(Charsets.UTF_8)
                            ), false
                        )
                    )
                }
            }
        }

        override fun onRunError(e: java.lang.Exception?) {
            Log.e(LogTag, "usb error ${e?.message}")
        }
    }

    private suspend fun writeSpeedController(speedController: UsbSerialPort) {
        while (true) {
            val control = controlChannel.receive()
            //Log.d(LogTag, "send to speedController $control")
            speedController.write(
                byteArrayOf(
                    control.throttle.toByte(),
                    (control.throttle shr 8).toByte(),
                    control.steering.toByte(),
                    (control.steering shr 8).toByte()
                ), 1000
            )
        }
    }

    private suspend fun sendPhoneState(
        batteryManager: BatteryManager,
        connectivityManager: ConnectivityManager,
        telephonyManager: TelephonyManager,
        locationClient: FusedLocationProviderClient
    ) {
        while (true) {
            if (dataChannel?.state() == DataChannel.State.OPEN) {
                val networkCapabilities =
                    connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                try {
                    locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { location: Location? ->
                            // Got last known location. In some rare situations this
                            val message = JSONObject()
                                .put("type", "phoneState")
                                .put(
                                    "battery",
                                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                )
                                .put("signal", telephonyManager.signalStrength?.level)
                                .put("bandwidthUp", networkCapabilities?.linkUpstreamBandwidthKbps)
                                .put(
                                    "bandwidthDown",
                                    networkCapabilities?.linkDownstreamBandwidthKbps
                                )
                                .put(
                                    "location", JSONObject()
                                        .put("latitude", location?.latitude)
                                        .put("longitude", location?.longitude)
                                        .put("speed", location?.speed)
                                )
                                .toString()
                            Log.d(LogTag, "send datachannel $message")
                            dataChannel!!.send(
                                DataChannel.Buffer(
                                    ByteBuffer.wrap(
                                        message.toByteArray(Charsets.UTF_8)
                                    ), false
                                )
                            )
                        }
                } catch (e: SecurityException) {
                    Log.e(LogTag, "location permission not granted ${e.message}")
                }
            }
            delay(1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d(LogTag, "location permission granted")
            } else {
                Log.e(LogTag, "location permission not granted")
                finish()
            }
        }.launch(Manifest.permission.ACCESS_FINE_LOCATION) // for some android devised also coarse location necessary

        val usbManager = getSystemService(UsbManager::class.java)
        val usbDriver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (usbDriver.isEmpty()) {
            Log.e(LogTag, "no usb device")
            finish()
        }

        val speedController = usbDriver[0].ports[0] // Most devices have just one port (port 0)
        Log.d(LogTag, "creates usb port $speedController")
        speedController.open(usbManager.openDevice(usbDriver[0].device))
        speedController.setParameters(
            115200,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )
        speedController.dtr = true
        Log.d(LogTag, "opened usb device")
        SerialInputOutputManager(speedController, UsbListener()).start()
        this.lifecycleScope.launch(Dispatchers.Default)
        {
            writeSpeedController(speedController)
        }

        val webSocketRequest =
            okhttp3.Request.Builder().url("ws://34.147.125.161:5003/connect?id=$ID").build()

        fun connectWebSocket() {
            webSocket =
                OkHttpClient().newWebSocket(
                    webSocketRequest,
                    SignalerListener(::connectWebSocket)
                )
        }
        connectWebSocket()
        Log.d(LogTag, "connected to websocket")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        val eglContext = EglBase.create().eglBaseContext
        peerConnectionFactory =
            PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options())
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()
        rtcConfig = PeerConnection.RTCConfiguration(
            arrayListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
        )
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        Log.d(LogTag, "peerConnectionFactory created")

        val cameraEnumerator = Camera2Enumerator(applicationContext)
        val capturer =
            cameraEnumerator.createCapturer(cameraEnumerator.deviceNames.find { cameraName ->
                cameraEnumerator.isBackFacing(cameraName)
            }!!, null)
        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "SurfaceTextureHelperThread", eglContext
        )
        val videoSource by lazy {
            peerConnectionFactory.createVideoSource(capturer.isScreencast).apply {
                capturer.initialize(
                    surfaceTextureHelper, applicationContext, this.capturerObserver
                )
                capturer.startCapture(
                    320,
                    240,
                    30
                ) // 1920x1080, 1440x1080, 1088x1088, 1280x720, 1056x704, 1024x768, 960x720, 960x540, 800x450, 720x720, 720x480, 640x480, 352x288, 320x240, 256x144, 176x144
            }
        }
        videoTrack = peerConnectionFactory.createVideoTrack(
            "video${UUID.randomUUID()}", videoSource
        )
        Log.d(LogTag, "added camera track to peerConnection")

        this.lifecycleScope.launch(Dispatchers.Default)
        {
            sendPhoneState(
                getSystemService(BatteryManager::class.java),
                getSystemService(ConnectivityManager::class.java),
                getSystemService(TelephonyManager::class.java),
                LocationServices.getFusedLocationProviderClient(this@MainActivity),
            )
        }

        setContent {
            RobotTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Text(text = "Robot", modifier = Modifier)
                }
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(LogTag, "onDestroy clean up")
        webSocket.close(1000, "app closed")
        peerConnection?.close()
        dataChannel?.close()
        // add speedController.close in writeSpeedController
    }
}

