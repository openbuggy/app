package com.example.robot

import android.os.Bundle
import android.util.Log
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
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val LogTag: String = MainActivity::class.java.simpleName

    lateinit var webSocket: WebSocket
    lateinit var peerConnection: PeerConnection
    lateinit var controllerId: String

    private inner class PeerConnectionObserver : PeerConnection.Observer {
        override fun onDataChannel(p0: DataChannel?) {
            Log.d(LogTag, "onDataChannel")
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            Log.d(LogTag, "onIceConnectionReceivingChange")
        }

        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            Log.d(LogTag, "onIceConnectionChange")
            if (p0 == PeerConnection.IceConnectionState.DISCONNECTED) {
                Log.d(LogTag, "closing channel")
                //channel?.close()
            }
        }

        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            Log.d(LogTag, "onIceGatheringChange")
            if (p0 == PeerConnection.IceGatheringState.COMPLETE) {
                val json = JSONObject()
                json.put("to", controllerId)
                json.put(
                    "message",
                    JSONObject().put("type", peerConnection.localDescription.type.canonicalForm())
                        .put("sdp", peerConnection.localDescription.description)
                )
                webSocket.send(json.toString())
            }
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
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
            Log.d(LogTag, "onIceCandidatesRemoved")
        }
    }

    private inner class DefaultSdpObserver(
        val createSuccessCallback: (SessionDescription?) -> Unit = {}, val setSuccessCallback: () -> Unit = {}
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
            Log.e(LogTag, "onSetFailure")
        }
    }

    private inner class SignalerListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(LogTag, "onMessage")
            val message = JSONObject(text)
            if (message.getString("type") == "rtc") {
                controllerId = message.getString("from")
                val offerJson = message.getJSONObject("message")
                val offer = SessionDescription(
                    SessionDescription.Type.OFFER, offerJson.getString("sdp")
                )
                //we have remote offer, let's create answer for that
                peerConnection.setRemoteDescription(DefaultSdpObserver(setSuccessCallback = {
                    peerConnection.createAnswer(DefaultSdpObserver(createSuccessCallback = { p0 ->
                        peerConnection.setLocalDescription(
                            DefaultSdpObserver(setSuccessCallback = {
                                Log.d(LogTag, "local description set")
                            }), p0
                        )
                    }), MediaConstraints())
                }), offer)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = okhttp3.Request.Builder().url("ws://192.168.0.45:8080/connect").build()
        webSocket = OkHttpClient().newWebSocket(request, SignalerListener())

        val initializationOptions =
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        val eglContext = EglBase.create().eglBaseContext
        val peerConnectionFactory =
            PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options())
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(
            arrayListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peerConnection =
            peerConnectionFactory.createPeerConnection(rtcConfig, PeerConnectionObserver())!!


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
                capturer.startCapture(320, 240, 30)
            }
        }
        val localVideoTrack: VideoTrack by lazy {
            peerConnectionFactory.createVideoTrack(
                "video${UUID.randomUUID()}", videoSource
            )
        }
        peerConnection.addTrack(localVideoTrack)

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
