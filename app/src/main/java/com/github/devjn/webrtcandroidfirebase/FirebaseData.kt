package com.github.devjn.webrtcandroidfirebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase


/**
 * Created by @author Jahongir on 13-Feb-18
 * devjn@jn-arts.com
 * FirebaseData
 */
object FirebaseData {

    var myID: String = ""
    const val CALLS = "calls"

    val database = FirebaseDatabase.getInstance()


    fun getCallDataPath(id: String) = "${CALLS}/$id/data"
    fun getCallStatusPath(id: String) = "${CALLS}/$id/status"

    fun getCallDataReference(id: String) = database.getReference("${CALLS}/$id/data")!!
    fun getCallStatusReference(id: String) = database.getReference("${CALLS}/$id/status")!!
    fun getCallIdReference(id: String) = database.getReference("${CALLS}/$id/id")!!

    fun init() {
        val auth = FirebaseAuth.getInstance()!!
        auth.currentUser?.let {
            myID = it.uid
            database.getReference("users/${myID}/online").onDisconnect().setValue(false)
            database.getReference("users/${myID}").setValue(ContactData(it.displayName, true))
        }

    }

}

data class ContactData(val name: String? = "", val online: Boolean = false)