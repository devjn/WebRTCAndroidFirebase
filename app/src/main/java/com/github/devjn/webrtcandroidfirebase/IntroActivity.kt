package com.github.devjn.webrtcandroidfirebase

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.widget.Button
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.ErrorCodes
import com.firebase.ui.auth.IdpResponse
import com.google.firebase.auth.FirebaseAuth
import java.util.*


class IntroActivity : AppCompatActivity() {

    private val RC_SIGN_IN = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            // already signed in
            startActivity(Intent(this, LobbyActivity::class.java))
            finish()
            return
        }

        val startButton: Button = findViewById(R.id.btn_start)
        startButton.setOnClickListener {
            startActivityForResult(
                    AuthUI.getInstance()
                            .createSignInIntentBuilder()
                            .setLogo(R.drawable.webrtc_logo)
                            .setIsSmartLockEnabled(!BuildConfig.DEBUG /* credentials */, true /* hints */)
                            .setAvailableProviders(Arrays.asList(
                                    AuthUI.IdpConfig.EmailBuilder().build(),
                                    AuthUI.IdpConfig.GoogleBuilder().build()
                            )).build(),
                    RC_SIGN_IN)
        }

    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)
        // RC_SIGN_IN is the request code you passed into startActivityForResult(...) when starting the sign in flow.
        if (requestCode == RC_SIGN_IN) {
            val response = IdpResponse.fromResultIntent(data)

            // Successfully signed in
            if (resultCode == Activity.RESULT_OK) {
                startActivity(Intent(this, LobbyActivity::class.java))
                finish()
                return
            } else {
                // Sign in failed
                if (response == null) {
                    // User pressed back button
                    showSnackbar(R.string.sign_in_cancelled)
                    return
                }

                if (response.errorCode == ErrorCodes.NO_NETWORK) {
                    showSnackbar(R.string.no_internet_connection)
                    return
                }

                if (response.errorCode == ErrorCodes.UNKNOWN_ERROR) {
                    showSnackbar(R.string.unknown_error)
                    return
                }
            }

            showSnackbar(R.string.unknown_sign_in_response)
        }
    }

    private fun showSnackbar(stringRes: Int) {
        Snackbar.make(findViewById(R.id.root)!!, stringRes, Snackbar.LENGTH_LONG).show()
    }


}
