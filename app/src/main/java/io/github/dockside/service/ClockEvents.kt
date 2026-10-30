package io.github.dockside.service

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ClockKind { ALARM, TIMER }

/** What a button on the clock app's notification does, guessed from its label. */
enum class ClockRole { DISMISS, SNOOZE, ADD, OTHER }

data class ClockAction(val title: String, val role: ClockRole, val intent: PendingIntent?)

/**
 * An alarm or timer that is ringing right now. Dockside can't silence another app's alarm by
 * itself, so its buttons press the clock app's own notification buttons.
 */
data class RingingClock(
    val key: String,
    val packageName: String,
    val kind: ClockKind,
    val label: String,
    val actions: List<ClockAction>,
    /** The clock app's own alarm screen, the way out when no dismiss button is recognised. */
    val openClock: PendingIntent? = null,
    val since: Long = System.currentTimeMillis(),
    val demo: Boolean = false,
) {
    fun action(role: ClockRole) = actions.firstOrNull { it.role == role }
}

object ClockEvents {
    private val _ringing = MutableStateFlow<RingingClock?>(null)
    val ringing: StateFlow<RingingClock?> = _ringing.asStateFlow()

    /** Shows the alarm screen with nothing behind it, from the settings (to try the look). */
    fun demo(kind: ClockKind = ClockKind.ALARM) {
        _ringing.value = RingingClock("demo", "", kind, "", emptyList(), demo = true)
    }

    /**
     * Presses a clock-app button and hides the alarm screen. Without a button (the demo, or a
     * clock app whose buttons weren't recognised) a real alarm opens the clock app's own alarm
     * screen instead, so it can always be silenced.
     */
    fun press(action: ClockAction?) {
        val r = _ringing.value
        if (action == null) {
            if (r != null && !r.demo) runCatching { r.openClock?.send() }
            _ringing.value = null
            return
        }
        runCatching { action.intent?.send() }
        if (action.role == ClockRole.DISMISS || action.role == ClockRole.SNOOZE) _ringing.value = null
    }

    fun dismissScreen() {
        _ringing.value = null
    }

    private val TIME_ONLY = Regex("""^\d{1,2}[:.]\d{2}(\s?[aApP]\.?[mM]\.?|\s?(ДП|ПП|дп|пп))?$""")

    private fun has(text: String, vararg words: String) = words.any { it in text }

    private fun role(title: String): ClockRole {
        val t = title.lowercase()
        return when {
            has(t, "snooze", "отлож", "повтор", "дремать") -> ClockRole.SNOOZE
            has(t, "dismiss", "stop", "turn off", "cancel", "delete", "отклон", "выключ", "стоп", "останов", "закрыть", "отмен", "удал") -> ClockRole.DISMISS
            t.startsWith("+") || has(t, "add", "добав", "мин") -> ClockRole.ADD
            else -> ClockRole.OTHER
        }
    }

    private fun actionsOf(n: Notification) = n.actions.orEmpty().map {
        val title = it.title?.toString().orEmpty()
        ClockAction(title, role(title), it.actionIntent)
    }

    /** Called for every posted notification; returns true when it was a clock event. */
    internal fun onPosted(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val words = (title + " " + text + " " + ((if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId else null) ?: "")).lowercase()
        val timerWords = has(words, "timer", "таймер")

        // Ringing: an alarm-category notification that wants to take over the screen.
        val ringingNow = n.category == Notification.CATEGORY_ALARM && n.fullScreenIntent != null
        // Snoozed or stopped elsewhere (the clock's own screen, the shade), the clock app often
        // updates its notification ("Snoozed until 7:10") instead of removing it.
        if (!ringingNow && _ringing.value?.key == sbn.key) {
            _ringing.value = null
            return true
        }
        if (ringingNow) {
            _ringing.value = RingingClock(
                key = sbn.key, packageName = sbn.packageName,
                kind = if (timerWords) ClockKind.TIMER else ClockKind.ALARM,
                // the alarm's own label, when the user gave it one
                // (not when it is just the time, which the screen already shows big)
                label = if (timerWords) "" else text.takeIf { label ->
                    label.isNotBlank() && !has(label.lowercase(), "alarm", "будильник") && !label.trim().matches(TIME_ONLY)
                }.orEmpty(),
                actions = actionsOf(n),
                openClock = n.fullScreenIntent ?: n.contentIntent,
            )
            return true
        }
        return false
    }

    internal fun onRemoved(sbn: StatusBarNotification) {
        if (_ringing.value?.key == sbn.key) _ringing.value = null
    }
}
