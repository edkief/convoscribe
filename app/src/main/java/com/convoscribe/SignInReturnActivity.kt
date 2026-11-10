package com.convoscribe

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.convoscribe.settings.PendingSignIn

/**
 * Where authz-proxy's approval page sends the browser back after a
 * device sign-in (issue #95), at `convoscribe://api-key?api_key_request=…&state=…`.
 * The return carries no key: it only brings ConvoScribe forward on the
 * Server page, which checks the request and collects the key. A return
 * whose `state` is not the waiting sign-in's was not started here, and
 * is ignored. Invisible; closes at once.
 */
class SignInReturnActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = (applicationContext as ConvoApp).container.settingsRepository.pendingSignIn()
        if (isOurReturn(intent?.data, pending)) startActivity(MainActivity.openSignInIntent(this))
        finish()
    }

    companion object {
        /** Whether [uri] answers the sign-in [pending]: the app's return address with its `state`. */
        fun isOurReturn(uri: Uri?, pending: PendingSignIn?): Boolean {
            if (uri == null || pending == null) return false
            if (uri.scheme != "convoscribe" || uri.host != "api-key") return false
            return uri.getQueryParameter("state") == pending.state
        }
    }
}
