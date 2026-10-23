package com.automation.fbautoreply.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

object PreferenceManager {

    private const val PREF_NAME = "auto_reply_prefs"

    const val KEY_TRIGGER_KEYWORDS = "trigger_keywords"
    const val KEY_REPLY_MESSAGE = "reply_message"
    const val KEY_ACTION_DELAY_MS = "action_delay_ms"
    const val KEY_IS_BOT_ACTIVE = "is_bot_active"
    const val KEY_REPLIED_COUNT = "replied_count"

    const val DEFAULT_TRIGGER_KEYWORDS = "link, info, details"
    const val DEFAULT_REPLY_MESSAGE = "Thanks for your comment! Here is the information you requested. Please check the details."
    const val DEFAULT_ACTION_DELAY_MS = 1200L

    // In-memory cache to prevent duplicate replies to the same comment
    val repliedSignatures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Reactive StateFlows for real-time UI & Service sync
    private val _botActiveFlow = MutableStateFlow(false)
    val botActiveFlow: StateFlow<Boolean> = _botActiveFlow.asStateFlow()

    private val _repliedCountFlow = MutableStateFlow(0)
    val repliedCountFlow: StateFlow<Int> = _repliedCountFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        val prefs = getPrefs(context)
        val active = prefs.getBoolean(KEY_IS_BOT_ACTIVE, false)
        val count = prefs.getInt(KEY_REPLIED_COUNT, 0)
        _botActiveFlow.value = active
        _repliedCountFlow.value = count
    }

    fun getTriggerKeywords(context: Context): String {
        return getPrefs(context).getString(KEY_TRIGGER_KEYWORDS, DEFAULT_TRIGGER_KEYWORDS)
            ?: DEFAULT_TRIGGER_KEYWORDS
    }

    fun setTriggerKeywords(context: Context, keywords: String) {
        getPrefs(context).edit().putString(KEY_TRIGGER_KEYWORDS, keywords).apply()
    }

    fun getReplyMessage(context: Context): String {
        return getPrefs(context).getString(KEY_REPLY_MESSAGE, DEFAULT_REPLY_MESSAGE)
            ?: DEFAULT_REPLY_MESSAGE
    }

    fun setReplyMessage(context: Context, message: String) {
        getPrefs(context).edit().putString(KEY_REPLY_MESSAGE, message).apply()
    }

    fun getActionDelayMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_ACTION_DELAY_MS, DEFAULT_ACTION_DELAY_MS)
    }

    fun setActionDelayMs(context: Context, delayMs: Long) {
        getPrefs(context).edit().putLong(KEY_ACTION_DELAY_MS, delayMs).apply()
    }

    fun isBotActive(context: Context): Boolean {
        val active = getPrefs(context).getBoolean(KEY_IS_BOT_ACTIVE, false)
        _botActiveFlow.value = active
        return active
    }

    fun setBotActive(context: Context, active: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_BOT_ACTIVE, active).apply()
        _botActiveFlow.value = active
    }

    fun getRepliedCount(context: Context): Int {
        val count = getPrefs(context).getInt(KEY_REPLIED_COUNT, 0)
        _repliedCountFlow.value = count
        return count
    }

    fun incrementRepliedCount(context: Context): Int {
        val prefs = getPrefs(context)
        val newCount = prefs.getInt(KEY_REPLIED_COUNT, 0) + 1
        prefs.edit().putInt(KEY_REPLIED_COUNT, newCount).apply()
        _repliedCountFlow.value = newCount
        return newCount
    }

    fun resetRepliedCount(context: Context) {
        getPrefs(context).edit().putInt(KEY_REPLIED_COUNT, 0).apply()
        _repliedCountFlow.value = 0
    }

    fun isAlreadyReplied(signature: String): Boolean {
        return repliedSignatures.contains(signature)
    }

    fun markAsReplied(signature: String) {
        repliedSignatures.add(signature)
    }

    fun clearCache() {
        repliedSignatures.clear()
    }
}
