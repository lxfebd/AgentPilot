package com.agentpilot.app

import android.app.Application
import com.agentpilot.app.feature.auth.AuthSessionStore

/**
 * Pre-warms the encrypted auth session store off the main thread.
 *
 * AuthSessionStore opens EncryptedSharedPreferences, which does Keystore key
 * generation + file IO — on many devices 50–300 ms on a cold start. First
 * composition reads it synchronously to decide login vs home; warming it here
 * (Application.onCreate runs before any Activity) turns that read into a cache
 * hit. If warm-up is still in flight when composition runs, the read falls back
 * to the previous blocking path — no regression, just no early win that launch.
 */
class AgentPilotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            Thread.currentThread().name = "auth-session-prewarm"
            try {
                AuthSessionStore(this@AgentPilotApplication)
            } catch (_: Throwable) {
                // Keep the app alive; reading later degrades to a plain store.
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }
}
