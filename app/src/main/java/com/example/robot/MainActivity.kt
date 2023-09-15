package com.example.robot

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.robot.ui.theme.RobotTheme
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.launch
import okhttp3.Response

class MainActivity : ComponentActivity() {
    private val ID = UUID.randomUUID().toString()

    private val LogTag: String = MainActivity::class.java.simpleName

    private var webSocketOpen = false
    private var webSocketQueue: String? = null

    private lateinit var webSocket: WebSocket
    private lateinit var controllerId: String
    private lateinit var eglContext: EglBase.Context
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var rtcConfig: PeerConnection.RTCConfiguration
    private lateinit var localVideoTrack: VideoTrack
    private var peerConnection: PeerConnection? = null


    private data class Control(val throttle: Int, val steering: Int)

    private val controlChannel = Channel<Control>(CONFLATED)

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
            lifecycle.coroutineScope.launch {
                controlChannel.send(Control(message.getInt("throttle"), message.getInt("steering")))
            }
        }
    }

    private inner class PeerConnectionObserver : PeerConnection.Observer {
        override fun onDataChannel(p0: DataChannel?) {
            Log.d(LogTag, "onDataChannel")
            p0!!.registerObserver(DataChannelObserver())
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            Log.d(LogTag, "onIceConnectionReceivingChange")
        }

        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            Log.d(LogTag, "iceconnectionstate changed to $p0")
            if (p0 == PeerConnection.IceConnectionState.FAILED) {
                val message = JSONObject().put("to", controllerId).put("message", "failure").toString()
                if (webSocketOpen) {
                    Log.d(LogTag, "send $message")
                    webSocket.send(message)
                } else {
                    Log.d(LogTag, "enqueue $message")
                    webSocketQueue = message
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

        override fun onIceCandidate(p0: IceCandidate?) {
            Log.d(LogTag, "onIceCandidate")
            val message = JSONObject()
            message.put("to", controllerId)
            val candidate = JSONObject()
            candidate.put("candidate", p0!!.sdp)
            candidate.put(
                "sdpMid", p0.sdpMid
            )
            candidate.put("sdpMLineIndex", p0.sdpMLineIndex)
            message.put("message", candidate)
            Log.d(LogTag, "send ice candidate $message")
            webSocket.send(message.toString())
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
            Log.d(LogTag, "onIceCandidatesRemoved")
        }
    }

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
                    peerConnection!!.addTrack(localVideoTrack)
                    peerConnection!!.setRemoteDescription(DefaultSdpObserver(setSuccessCallback = {
                        peerConnection!!.createAnswer(DefaultSdpObserver(createSuccessCallback = { p0 ->
                            peerConnection!!.setLocalDescription(
                                DefaultSdpObserver(setSuccessCallback = {
                                    val json = JSONObject()
                                    json.put("to", controllerId)
                                    json.put(
                                        "message",
                                        JSONObject().put("type", peerConnection!!.localDescription.type.canonicalForm())
                                            .put("sdp", peerConnection!!.localDescription.description)
                                    )
                                    Log.d(LogTag, "send answer $json")
                                    webSocket.send(json.toString())
                                }), p0
                            )
                        }), MediaConstraints())
                    }), offer)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            webSocketOpen = false
            Log.e(LogTag, "websocket failure", t)
            Thread.sleep(1000)
            reconnectCallback()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, "peer requested closing");
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            webSocketOpen = false
            Log.e(LogTag, "websocket closed")
            Thread.sleep(1000)
            reconnectCallback()
        }
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocketOpen = true
            Log.e(LogTag, "websocket open")
            if (webSocketQueue != null) {
                webSocket.send(webSocketQueue!!)
                webSocketQueue = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDriver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)[0]
        val speedController = usbDriver.ports[0] // Most devices have just one port (port 0)
        Log.d(LogTag, "creates usb port $speedController")
        try {
            speedController.open(usbManager.openDevice(usbDriver.device))
            speedController.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            speedController.dtr = true
            Log.d(LogTag, "opened usb device")
            lifecycle.coroutineScope.launch {
                while (true) {
                    val control = controlChannel.receive()
                    Log.d(LogTag, "send to speedController $control")
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
        } catch (e: Exception) {
            Log.e(LogTag, "error opening usb device $e")
        }

        val request = okhttp3.Request.Builder().url("ws://192.168.0.45:8080/connect?id=$ID").build()
        fun connectWebSocket() {
            webSocket = OkHttpClient().newWebSocket(request, SignalerListener(::connectWebSocket))
        }
        connectWebSocket()
        Log.d(LogTag, "connected to websocket")


        val initializationOptions =
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        eglContext = EglBase.create().eglBaseContext
        peerConnectionFactory =
            PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options())
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()

        rtcConfig = PeerConnection.RTCConfiguration(
            arrayListOf(
                //PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }

        Log.d(LogTag, "peerConnectionFactory created")

        val cameraEnumerator = Camera2Enumerator(applicationContext)
        lateinit var capturer: VideoCapturer
        cameraEnumerator.deviceNames.forEach { cameraName ->
            if (cameraEnumerator.isBackFacing(cameraName)) {
                capturer = cameraEnumerator.createCapturer(cameraName, null)
            }
        }
        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "SurfaceTextureHelperThread", eglContext
        )
        val videoSource by lazy {
            peerConnectionFactory.createVideoSource(capturer.isScreencast).apply {
                capturer.initialize(
                    surfaceTextureHelper, applicationContext, this.capturerObserver
                )
                capturer.startCapture(325, 240, 30) // 1920x1080, 1440x1080, 1088x1088, 1280x720, 1056x704, 1024x768, 960x720, 960x540, 800x450, 720x720, 720x480, 640x480, 352x288, 320x240, 256x144, 176x144
            }
        }
        localVideoTrack = peerConnectionFactory.createVideoTrack(
            "video${UUID.randomUUID()}", videoSource
        )
        Log.d(LogTag, "added camera track to peerConnection")

        setContent {
            RobotTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(LogTag, "onDestroy clean up")
        webSocket.close(1000, "app closed")
        peerConnection!!.close()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!", modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RobotTheme {
        Greeting("Android")
    }
}
