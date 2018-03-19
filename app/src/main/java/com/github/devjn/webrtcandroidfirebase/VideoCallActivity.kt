package com.github.devjn.webrtcandroidfirebase

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.github.devjn.webrtcandroidfirebase.components.GLCircleDrawer
import com.github.devjn.webrtcandroidfirebase.videocall.VideoCallSession
import com.github.devjn.webrtcandroidfirebase.videocall.VideoCallStatus
import com.github.devjn.webrtcandroidfirebase.videocall.VideoRenderers
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer


class VideoCallActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_AUDIO_PERMISSION_REQUEST = 1
        private const val TAG = "VideoCallActivity"

        fun startCall(context: Context, id: String) {
            val starter = Intent(context, VideoCallActivity::class.java)
            starter.putExtra("offer", true)
            starter.putExtra("id", id)
            context.startActivity(starter)
        }

        fun receiveCall(context: Context, id: String) {
            val starter = Intent(context, VideoCallActivity::class.java)
            starter.putExtra("offer", false)
            starter.putExtra("id", id)
            context.startActivity(starter)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)

        if (savedInstanceState == null) {
            val fragment = CallFragment()
            fragment.isOffer = intent.getBooleanExtra("offer", false)
            fragment.id = intent.getStringExtra("id")

            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment, "CallFragment")
                    .addToBackStack(null)
                    .commit()
        }
    }


    class CallFragment : Fragment() {
        private var videoSession: VideoCallSession? = null
        private lateinit var statusTextView: TextView
        private lateinit var localVideoView: SurfaceViewRenderer
        private lateinit var remoteVideoView: SurfaceViewRenderer
        private var audioManager: AudioManager? = null
        private var savedMicrophoneState: Boolean? = null
        private var savedAudioMode: Int? = null

        var isOffer = false
        lateinit var id: String

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            retainInstance = true

            audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            savedAudioMode = audioManager?.mode
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

            savedMicrophoneState = audioManager?.isMicrophoneMute
            audioManager?.isMicrophoneMute = false
            audioManager?.isSpeakerphoneOn = true
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            val root = inflater.inflate(R.layout.activity_video_call, container, false)

            statusTextView = root.findViewById(R.id.status_text)
            localVideoView = root.findViewById(R.id.pip_video)
            remoteVideoView = root.findViewById(R.id.remote_video)

            val hangup: ImageButton = root.findViewById(R.id.hangup_button)
            hangup.setOnClickListener { activity!!.finish() }

            val toggle: ImageButton = root.findViewById(R.id.btn_toggle_camera)
            toggle.setOnClickListener { videoSession?.toggleCamera() }

            return root
        }

        override fun onActivityCreated(savedInstanceState: Bundle?) {
            super.onActivityCreated(savedInstanceState)
            if (savedInstanceState == null)
                handlePermissions()
            else videoSession?.let {
                initVideoVews()
                it.videoRenderers.updateViewRenders(localVideoView, remoteVideoView)
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            localVideoView.release()
            remoteVideoView.release()
        }

        override fun onDestroy() {
            super.onDestroy()
            videoSession?.terminate()

            if (savedAudioMode !== null) {
                audioManager?.mode = savedAudioMode!!
            }
            if (savedMicrophoneState != null) {
                audioManager?.isMicrophoneMute = savedMicrophoneState!!
            }
        }

        private fun onStatusChanged(newStatus: VideoCallStatus) {
            Log.d(TAG, "New call status: $newStatus")
            if (!isAdded) {
                Log.w(TAG, "onStatusChanged, but is not added : $newStatus")
                return
            }
            activity?.runOnUiThread {
                when (newStatus) {
                    VideoCallStatus.FINISHED -> activity!!.finish()
                    else -> {
                        statusTextView.text = resources.getString(newStatus.label)
                        statusTextView.setTextColor(ContextCompat.getColor(context!!, newStatus.color))
                    }
                }
            }
        }

        private fun handlePermissions() {
            val canAccessCamera = ContextCompat.checkSelfPermission(context!!, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val canRecordAudio = ContextCompat.checkSelfPermission(context!!, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!canAccessCamera || !canRecordAudio) {
                ActivityCompat.requestPermissions(activity!!, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), CAMERA_AUDIO_PERMISSION_REQUEST)
            } else {
                startVideoSession()
            }
        }

        private fun startVideoSession() {
            videoSession = VideoCallSession.connect(context!!, id, isOffer, VideoRenderers(localVideoView, remoteVideoView), this::onStatusChanged)
            initVideoVews()
        }

        private fun initVideoVews() {
            localVideoView.apply {
                init(videoSession?.renderContext, null, EglBase.CONFIG_RGBA, GLCircleDrawer())
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
//                setZOrderMediaOverlay(true)
                //To make transparent
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setEnableHardwareScaler(true)
                setMirror(true)
            }

            remoteVideoView.apply {
                init(videoSession?.renderContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
            }
//        remoteVideoView?.setMirror(true)
        }

        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
            Log.w(TAG, "onRequestPermissionsResult: $requestCode $permissions $grantResults")
            when (requestCode) {
                CAMERA_AUDIO_PERMISSION_REQUEST -> {
                    if (grantResults.isNotEmpty() && grantResults.first() == PackageManager.PERMISSION_GRANTED) {
                        startVideoSession()
                    } else {
                        activity!!.finish()
                    }
                    return
                }
            }
        }

    }
}
