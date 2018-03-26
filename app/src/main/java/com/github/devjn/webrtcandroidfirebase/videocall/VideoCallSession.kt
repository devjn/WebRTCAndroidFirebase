package com.github.devjn.webrtcandroidfirebase.videocall


import android.content.Context
import android.util.Log
import android.widget.Toast
import com.github.devjn.webrtcandroidfirebase.FirebaseData
import com.github.devjn.webrtcandroidfirebase.R
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import org.webrtc.*
import java.util.concurrent.Executors


/**
 * Created by @author devjn on 02-10-18
 * devjn@jn-arts.com
 * VideoCallSession
 */

enum class VideoCallStatus(val label: Int, val color: Int) {
    UNKNOWN(R.string.status_unknown, R.color.colorUnknown),
    CONNECTING(R.string.status_connecting, R.color.colorConnecting),
    DIALING(R.string.status_dialing, R.color.colorMatching),
    FAILED(R.string.status_failed, R.color.colorFailed),
    CONNECTED(R.string.status_connected, R.color.colorConnected),
    FINISHED(R.string.status_finished, R.color.colorConnected);
}

data class VideoRenderers(private var localView: SurfaceViewRenderer?, private var remoteView: SurfaceViewRenderer?) {
    val localRenderer: (VideoRenderer.I420Frame) -> Unit = { f ->
        localView?.renderFrame(f) ?: sink(f)
    }
    //            if (localView == null) this::sink else { f -> localView!!.renderFrame(f) }
    val remoteRenderer: (VideoRenderer.I420Frame) -> Unit = { f ->
        remoteView?.renderFrame(f) ?: sink(f)
    }
//            if (remoteView == null) this::sink else { f -> remoteView!!.renderFrame(f) }


    fun updateViewRenders(localView: SurfaceViewRenderer, remoteView: SurfaceViewRenderer) {
        this.localView = localView
        this.remoteView = remoteView
    }

    private fun sink(frame: VideoRenderer.I420Frame) {
        Log.w("VideoRenderer", "Missing surface view, dropping frame")
        VideoRenderer.renderFrameDone(frame)
    }
}

class VideoCallSession(
        private val context: Context,
        private val isOfferingPeer: Boolean,
        private val onStatusChangedListener: (VideoCallStatus) -> Unit,
        private val signaler: FirebaseSignaler,
        val videoRenderers: VideoRenderers) {

    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    private var mediaStream: MediaStream? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoTrack: VideoTrack? = null

    private val eglBase = EglBase.create()


    private val videoHeight = 1280
    private val videoWidth = 720
    private val videoFPS = 30

    val renderContext: EglBase.Context
        get() = eglBase.eglBaseContext

    class SimpleRTCEventHandler(
            private val onIceCandidateCb: (IceCandidate) -> Unit,
            private val onAddStreamCb: (MediaStream) -> Unit,
            private val onRemoveStreamCb: (MediaStream) -> Unit) : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate != null) onIceCandidateCb(candidate)
        }

        override fun onAddStream(stream: MediaStream?) {
            if (stream != null) onAddStreamCb(stream)
        }

        override fun onRemoveStream(stream: MediaStream?) {
            if (stream != null) onRemoveStreamCb(stream)
        }

        override fun onDataChannel(chan: DataChannel?) {
            Log.w(TAG, "onDataChannel: $chan")
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            Log.w(TAG, "onIceConnectionReceivingChange: $p0")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            Log.w(TAG, "onIceConnectionChange: $newState")
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Log.w(TAG, "onIceGatheringChange: $newState")
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Log.w(TAG, "onSignalingChange: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.w(TAG, "onIceCandidatesRemoved: $candidates")
        }

        override fun onRenegotiationNeeded() {
            Log.w(TAG, "onRenegotiationNeeded")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    private val factory: PeerConnectionFactory by lazy {
        //Initialize PeerConnectionFactory globals.
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableVideoHwAcceleration(true)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        //Create a new PeerConnectionFactory instance - using Hardware encoder and decoder.
        val options = PeerConnectionFactory.Options()
        options.networkIgnoreMask = 0
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                renderContext, /* enableIntelVp8Encoder */true, /* enableH264HighProfile */true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(renderContext)
        PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()
    }


    init {
        signaler.messageHandler = this::onMessage
        this.onStatusChangedListener(VideoCallStatus.DIALING)
        executor.execute(this::init)
    }

    private fun init() {
        val iceServers = arrayListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        createPeerConnection(iceServers)
        setupMediaDevices()

        call()
    }

    private fun call() {
        val ref = FirebaseData.getCallStatusReference(signaler.callerID)
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                if (dataSnapshot.exists() && dataSnapshot.getValue(Boolean::class.java)!!) {
                    ref.removeEventListener(this)
                    onStatusChangedListener(VideoCallStatus.CONNECTING)
                    start()
                }
            }

            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "databaseError:", e.toException())
                ref.removeEventListener(this)
            }
        })
    }


    /**
     * Creating the local peerconnection instance
     */
    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.apply {
            // TCP candidates are only useful when connecting to a server that supports ICE-TCP.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

            enableCpuOveruseDetection = true
            enableDtlsSrtp = true
            // Use ECDSA encryption.
            keyType = PeerConnection.KeyType.ECDSA
        }

        val rtcEvents = SimpleRTCEventHandler(this::handleLocalIceCandidate, this::addRemoteStream, this::removeRemoteStream)

        peerConnection = factory.createPeerConnection(rtcConfig, rtcEvents)
    }


    private fun start() {
        signaler.init()
        executor.execute(this::maybeCreateOffer)
    }

    private fun maybeCreateOffer() {
        if (isOfferingPeer) {
            peerConnection?.createOffer(SDPCreateCallback(this::createDescriptorCallback), defaultPcConstraints())
        }
    }

    private fun defaultPcConstraints(): MediaConstraints {
        val pcConstraints = MediaConstraints()
        pcConstraints.optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        return pcConstraints
    }

    private fun handleLocalIceCandidate(candidate: IceCandidate) {
        Log.w(TAG, "Local ICE candidate: $candidate")
        signaler.sendCandidate(candidate.sdpMLineIndex, candidate.sdpMid, candidate.sdp)
    }

    private fun addRemoteStream(stream: MediaStream) {
        onStatusChangedListener(VideoCallStatus.CONNECTED)
        Log.i(TAG, "Got remote stream: $stream")
        executor.execute {
            if (stream.videoTracks.isNotEmpty()) {
                val remoteVideoTrack = stream.videoTracks.first()
                remoteVideoTrack.setEnabled(true)
                remoteVideoTrack.addRenderer(VideoRenderer(videoRenderers.remoteRenderer))
            }
        }
    }

    private fun removeRemoteStream(@Suppress("UNUSED_PARAMETER") _stream: MediaStream) {
        // We lost the stream, lets finish
        Log.w(TAG, "Bye")
        onStatusChangedListener(VideoCallStatus.FINISHED)
    }

    private fun handleRemoteCandidate(label: Int, id: String, strCandidate: String) {
        Log.i(TAG, "Got remote ICE candidate $strCandidate")
        executor.execute {
            val candidate = IceCandidate(id, label, strCandidate)
            peerConnection?.addIceCandidate(candidate)
        }
    }

    private fun setupMediaDevices() {
        mediaStream = factory.createLocalMediaStream(STREAM_LABEL)

        mediaStream?.addTrack(setupVideoTrack(isFront))

        audioSource = factory.createAudioSource(createAudioConstraints())
        val audioTrack = factory.createAudioTrack(AUDIO_TRACK_LABEL, audioSource)

        mediaStream?.addTrack(audioTrack)

        peerConnection?.addStream(mediaStream)
    }

    private fun setupVideoTrack(front: Boolean): VideoTrack? {
        val camera = if (useCamera2()) Camera2Enumerator(context) else Camera1Enumerator(false)

        videoCapturer = if (front) createFrontCameraCapturer(camera) else createBackCameraCapturer(camera)
        val videoSource = factory.createVideoSource(videoCapturer)
        videoCapturer?.startCapture(videoHeight, videoWidth, videoFPS)
        val videoRenderer = VideoRenderer(videoRenderers.localRenderer)

        videoTrack = factory.createVideoTrack(VIDEO_TRACK_LABEL, videoSource)
        videoTrack?.addRenderer(videoRenderer)
        return videoTrack
    }

    private fun createAudioConstraints(): MediaConstraints {
        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        return audioConstraints
    }

    private fun handleRemoteDescriptor(sdp: String) {
        if (isOfferingPeer) {
            peerConnection?.setRemoteDescription(SDPSetCallback({ setError ->
                if (setError != null) {
                    Log.e(TAG, "setRemoteDescription failed: $setError")
                }
            }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
        } else {
            peerConnection?.setRemoteDescription(SDPSetCallback({ setError ->
                if (setError != null) {
                    Log.e(TAG, "setRemoteDescription failed: $setError")
                } else {
                    peerConnection?.createAnswer(SDPCreateCallback(this::createDescriptorCallback), MediaConstraints())
                }
            }), SessionDescription(SessionDescription.Type.OFFER, sdp))
        }
    }

    private fun createDescriptorCallback(result: SDPCreateResult) {
        when (result) {
            is SDPCreateSuccess -> {
                peerConnection?.setLocalDescription(SDPSetCallback({ setResult ->
                    Log.i(TAG, "SetLocalDescription: $setResult")
                }), result.descriptor)
                signaler.sendSDP(result.descriptor.description)
            }
            is SDPCreateFailure -> Log.e(TAG, "Error creating offer: ${result.reason}")
        }
    }

    private fun onMessage(message: ClientMessage) {
        when (message) {
            is SDPMessage -> {
                handleRemoteDescriptor(message.sdp)
            }
            is ICEMessage -> {
                handleRemoteCandidate(message.label, message.id, message.candidate)
            }
            is PeerLeft -> {
                onStatusChangedListener(VideoCallStatus.FINISHED)
            }
        }
    }


    fun terminate() {
        signaler.close()
        try {
            videoCapturer?.stopCapture()
        } catch (ex: Exception) {
        }

        videoCapturer?.dispose()
        videoSource?.dispose()

        audioSource?.dispose()

        peerConnection?.dispose()

        factory.dispose()

        eglBase.release()
    }

    private var isFront = true

    fun toggleCamera() {
        isFront = !isFront
        mediaStream?.removeTrack(videoTrack)
        videoTrack?.dispose()
        mediaStream?.addTrack(setupVideoTrack(isFront))
    }

    private fun createFrontCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        //find the front facing camera and return it.
        deviceNames
                .filter { enumerator.isFrontFacing(it) }
                .mapNotNull { enumerator.createCapturer(it, null) }
                .forEach { return it }

        return null
    }

    private fun createBackCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.")
        val deviceNames = enumerator.deviceNames
        //find the front facing camera and return it.
        deviceNames
                .filter { enumerator.isBackFacing(it) }
                .mapNotNull {
                    Logging.d(TAG, "Creating other camera capturer.")
                    enumerator.createCapturer(it, null)
                }
                .forEach { return it }

        Toast.makeText(context, "No back camera found!", Toast.LENGTH_SHORT).show()
        return createFrontCameraCapturer(enumerator)
    }

    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(context)
    }

    companion object {

        fun connect(context: Context, id: String, isOffer: Boolean, videoRenderers: VideoRenderers, callback: (VideoCallStatus) -> Unit): VideoCallSession {
            val firebaseHandler = FirebaseSignaler(id)
            return VideoCallSession(context, isOffer, callback, firebaseHandler, videoRenderers)
        }

        private const val STREAM_LABEL = "remoteStream"
        private const val VIDEO_TRACK_LABEL = "remoteVideoTrack"
        private const val AUDIO_TRACK_LABEL = "remoteAudioTrack"
        private const val TAG = "VideoCallSession"
        private val executor = Executors.newSingleThreadExecutor()
    }


}