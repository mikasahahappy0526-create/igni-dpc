package app.igni.dpc.policy

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * Persists packages this DPC has hidden so they can be restored.
 * Uses device-protected storage so boot-time reapply works before first unlock.
 */
class HiddenStore(context: Context) {

    private val prefs = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, MODE_PRIVATE)

    fun snapshot(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet().orEmpty()
    }

    fun mutableCopy(): MutableSet<String> = snapshot().toMutableSet()

    fun replace(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN, packages.toSet()).apply()
    }

    fun markApplied() {
        prefs.edit().putBoolean(KEY_APPLIED, true).apply()
    }

    fun hasApplied(): Boolean = prefs.getBoolean(KEY_APPLIED, false)

    companion object {
        private const val PREFS = "igni_hidden_apps"
        private const val KEY_HIDDEN = "hidden_packages"
        private const val KEY_APPLIED = "applied"
    }
}
