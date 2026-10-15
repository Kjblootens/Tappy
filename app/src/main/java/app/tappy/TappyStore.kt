package app.tappy

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent app state, mirrored into a [StateFlow] so the UI and the blocker see changes immediately. */
object TappyStore {

    data class State(
        val blocking: Boolean = false,
        val blockedPackages: Set<String> = emptySet(),
        val pairedTagId: String? = null,
        val blockStartedAt: Long = 0L,
        val emergencyUnlocksLeft: Int = EMERGENCY_UNLOCKS,
    )

    const val EMERGENCY_UNLOCKS = 3

    private const val KEY_BLOCKING = "blocking"
    private const val KEY_PACKAGES = "blocked_packages"
    private const val KEY_TAG = "paired_tag"
    private const val KEY_STARTED = "block_started_at"
    private const val KEY_EMERGENCY = "emergency_unlocks_left"

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("tappy", Context.MODE_PRIVATE)
        _state.value = State(
            blocking = prefs.getBoolean(KEY_BLOCKING, false),
            blockedPackages = prefs.getStringSet(KEY_PACKAGES, emptySet())!!.toSet(),
            pairedTagId = prefs.getString(KEY_TAG, null),
            blockStartedAt = prefs.getLong(KEY_STARTED, 0L),
            emergencyUnlocksLeft = prefs.getInt(KEY_EMERGENCY, EMERGENCY_UNLOCKS),
        )
    }

    @Synchronized
    private fun update(transform: (State) -> State) {
        val next = transform(_state.value)
        // commit() rather than apply(): the block state must survive an immediate kill/reboot.
        prefs.edit()
            .putBoolean(KEY_BLOCKING, next.blocking)
            .putStringSet(KEY_PACKAGES, next.blockedPackages)
            .putString(KEY_TAG, next.pairedTagId)
            .putLong(KEY_STARTED, next.blockStartedAt)
            .putInt(KEY_EMERGENCY, next.emergencyUnlocksLeft)
            .commit()
        _state.value = next
    }

    /** Selection and pairing are frozen while a block is active. */
    fun setBlockedPackages(packages: Set<String>): Boolean {
        if (_state.value.blocking) return false
        update { it.copy(blockedPackages = packages) }
        return true
    }

    fun setPairedTag(tagId: String?): Boolean {
        if (_state.value.blocking) return false
        update { it.copy(pairedTagId = tagId) }
        return true
    }

    fun setBlocking(blocking: Boolean) = update {
        it.copy(blocking = blocking, blockStartedAt = if (blocking) System.currentTimeMillis() else 0L)
    }

    fun consumeEmergencyUnlock(): Boolean {
        val s = _state.value
        if (!s.blocking || s.emergencyUnlocksLeft <= 0) return false
        update { it.copy(blocking = false, blockStartedAt = 0L, emergencyUnlocksLeft = it.emergencyUnlocksLeft - 1) }
        return true
    }
}
