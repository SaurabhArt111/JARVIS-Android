package com.jarvis.assistant.platform

import android.app.KeyguardManager
import android.content.Context
import com.jarvis.core.lock.LockState

/** Reads the real keyguard state for the lock policy. JARVIS never bypasses it; it only reads it so personal data can stay hidden while the phone is locked. */
class LockStateReader(context: Context) {
    private val km = context.applicationContext.getSystemService(KeyguardManager::class.java)

    /** Set by the lock-screen code when the user passed the system's own authentication through JARVIS's unlock route (0 = never). */
    @Volatile var authenticatedAtMs: Long = 0L

    fun read(): LockState {
        val locked = try { km.isDeviceLocked } catch (_: Exception) { true }
        val secure = try { km.isDeviceSecure } catch (_: Exception) { true }
        // On any doubt assume the stricter state: locked and secure.
        return LockState(deviceLocked = locked, secure = secure, authenticatedAtMs = authenticatedAtMs)
    }
}
