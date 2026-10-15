package app.tappy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

/** Starts and ends blocks, and applies the extra device-owner policies when available. */
object BlockController {

    enum class TagResult { NOT_PAIRED, WRONG_TAG, NO_APPS, SETUP_INCOMPLETE, ENGAGED, RELEASED }

    private const val TAG = "Tappy"

    fun onTagScanned(context: Context, tagId: String): TagResult {
        val state = TappyStore.state.value
        return when {
            state.pairedTagId == null -> TagResult.NOT_PAIRED
            !state.pairedTagId.equals(tagId, ignoreCase = true) -> TagResult.WRONG_TAG
            state.blocking -> {
                release(context)
                TagResult.RELEASED
            }
            state.blockedPackages.isEmpty() -> TagResult.NO_APPS
            !isProtectionReady(context) -> TagResult.SETUP_INCOMPLETE
            else -> {
                engage(context)
                TagResult.ENGAGED
            }
        }
    }

    fun describe(result: TagResult): String = when (result) {
        TagResult.NOT_PAIRED -> "No tag paired yet. Open Tappy to pair one."
        TagResult.WRONG_TAG -> "This isn't your Tappy tag."
        TagResult.NO_APPS -> "Pick some apps to block in Tappy first."
        TagResult.SETUP_INCOMPLETE -> "Finish setup in Tappy first (blocker service and uninstall protection)."
        TagResult.ENGAGED -> "Blocked. Tap again to unlock."
        TagResult.RELEASED -> "Unlocked. Your apps are back."
    }

    private fun engage(context: Context) {
        TappyStore.setBlocking(true)
        applyOwnerPolicies(context, blocking = true)
    }

    private fun release(context: Context) {
        applyOwnerPolicies(context, blocking = false)
        TappyStore.setBlocking(false)
    }

    fun emergencyRelease(context: Context): Boolean {
        if (!TappyStore.state.value.blocking) return false
        applyOwnerPolicies(context, blocking = false)
        return TappyStore.consumeEmergencyUnlock()
    }

    /** A block only starts if it can actually be enforced and Tappy can't simply be uninstalled. */
    fun isProtectionReady(context: Context): Boolean =
        isAdminActive(context) && (BlockerService.isEnabled(context) || isDeviceOwner(context))

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(TappyAdminReceiver.component(context))

    /**
     * As device owner Tappy can do this properly: suspend the blocked apps at the OS level,
     * hard-block its own uninstall and disable safe mode (which would otherwise bypass it).
     */
    private fun applyOwnerPolicies(context: Context, blocking: Boolean) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = TappyAdminReceiver.component(context)
        val packages = TappyStore.state.value.blockedPackages.toTypedArray()
        try {
            dpm.setUninstallBlocked(admin, context.packageName, blocking)
            val failed = dpm.setPackagesSuspended(admin, packages, blocking)
            if (failed.isNotEmpty()) Log.w(TAG, "Could not (un)suspend: ${failed.joinToString()}")
            if (blocking) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Device owner policy failed", e)
        }
    }

    /** Lets the user hand back device-owner rights (only allowed while not blocking). */
    fun clearDeviceOwner(context: Context): Boolean {
        if (TappyStore.state.value.blocking || !isDeviceOwner(context)) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        applyOwnerPolicies(context, blocking = false)
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
        return true
    }

    /** Deactivates uninstall protection so the app can be removed (only allowed while not blocking). */
    fun deactivateAdmin(context: Context): Boolean {
        if (TappyStore.state.value.blocking || isDeviceOwner(context)) return false
        context.getSystemService(DevicePolicyManager::class.java)
            .removeActiveAdmin(TappyAdminReceiver.component(context))
        return true
    }
}
