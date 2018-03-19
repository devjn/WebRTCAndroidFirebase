package com.github.devjn.webrtcandroidfirebase.videocall

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
* Created by Rafael on 01-23-18.
*/


interface SDPCreateResult

data class SDPCreateSuccess(val descriptor: SessionDescription) : SDPCreateResult
data class SDPCreateFailure(val reason: String?) : SDPCreateResult

class SDPCreateCallback(private val callback: (SDPCreateResult) -> Unit) : SdpObserver {

    override fun onSetFailure(reason: String?) { }

    override fun onSetSuccess() { }

    override fun onCreateSuccess(descriptor: SessionDescription) = callback(SDPCreateSuccess(descriptor))

    override fun onCreateFailure(reason: String?) = callback(SDPCreateFailure(reason))
}

class SDPSetCallback(private val callback: (String?) -> Unit) : SdpObserver {

    override fun onSetFailure(reason: String?) = callback(reason)

    override fun onSetSuccess() = callback(null)

    override fun onCreateSuccess(descriptor: SessionDescription?) { }

    override fun onCreateFailure(reason: String?) { }
}