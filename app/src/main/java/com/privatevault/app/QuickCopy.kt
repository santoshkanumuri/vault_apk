package com.privatevault.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.Totp
import java.util.Locale
import java.util.UUID

/** One copyable field of a Quick copy item. [button] is the short label on the button and on the notification action. */
internal enum class QuickField(val button: String, val clipLabel: String, val done: String) {
    USERNAME("Username", "Username", "Username copied"),
    PASSWORD("Password", "Password", "Password copied"),
    CODE("Code", "Authenticator code", "Code copied"),
    CARD_NUMBER("Number", "Card number", "Card number copied"),
    EXPIRY("Expiry", "Expiry", "Expiry copied"),
    NAME("Name", "Name on card", "Name copied"),
    CVV("CVV", "CVV", "CVV copied"),
    ANSWER("Answer", "Answer", "Answer copied"),
}

/** A field's value. A code is made when it is copied, so a later copy from the notification is never stale. */
internal sealed interface QuickValue {
    fun resolve(): String

    class Text(private val text: String) : QuickValue {
        override fun resolve() = text
    }

    class Code(private val secret: String, private val algorithm: String, private val digits: Int, private val period: Int) : QuickValue {
        override fun resolve() = Totp.code(secret, algorithm, digits, period)
    }
}

private fun VaultEntry.codeValue() = QuickValue.Code(secondaryValue, totpAlgorithm, totpDigits, totpPeriod)

/**
 * The fields [entry] offers, in button order. Empty fields are left out. A login also offers its linked
 * authenticator code when that code is in [all]. A card number is copied as digits only, because many payment
 * fields cut a pasted number with spaces. An expiry is copied as "MM/YY" when Nuvori can read it.
 */
internal fun quickCopyValues(entry: VaultEntry, all: List<VaultEntry>): Map<QuickField, QuickValue> {
    val values = LinkedHashMap<QuickField, QuickValue>()
    fun text(field: QuickField, value: String) { if (value.isNotEmpty()) values[field] = QuickValue.Text(value) }
    when (entry.type) {
        EntryType.PASSWORD -> {
            text(QuickField.USERNAME, entry.primaryValue.takeIf { it.isNotBlank() }.orEmpty())
            text(QuickField.PASSWORD, entry.secondaryValue)
            entry.linkedAuthenticatorId.takeIf { it.isNotEmpty() }?.let { id ->
                all.firstOrNull { it.type == EntryType.AUTHENTICATOR && it.id == id && it.secondaryValue.isNotBlank() }
            }?.let { values[QuickField.CODE] = it.codeValue() }
        }
        EntryType.AUTHENTICATOR -> if (entry.secondaryValue.isNotBlank()) values[QuickField.CODE] = entry.codeValue()
        EntryType.CARD -> {
            text(QuickField.CARD_NUMBER, entry.primaryValue.filter(Char::isDigit).ifEmpty { entry.primaryValue.trim() })
            text(QuickField.EXPIRY, entry.tertiaryValue.trim().let { saved ->
                if (saved.isEmpty()) "" else com.privatevault.app.autofill.parseExpiry(saved)?.let { (month, year) ->
                    String.format(Locale.ROOT, "%02d/%02d", month, year % 100)
                } ?: saved
            })
            text(QuickField.NAME, entry.secondaryValue.trim())
            text(QuickField.CVV, entry.fourthValue.trim())
        }
        EntryType.QUESTION -> text(QuickField.ANSWER, entry.secondaryValue)
        EntryType.NOTE, EntryType.AUTOFILL -> Unit
    }
    return values
}

/**
 * The notification shows at most three actions (an Android limit). A card with four fields leaves out the name,
 * which is the easiest to type. The picker still offers every field.
 */
internal fun notificationFields(fields: Collection<QuickField>): List<QuickField> =
    (if (fields.size > 3) fields.filter { it != QuickField.NAME } else fields.toList()).take(3)

/**
 * The open Quick copy session: one item's fields, a random token and an expiry. Pure logic with an injected clock
 * for tests. One session at a time; a new one replaces the old one.
 */
internal class QuickCopyStore(private val clock: () -> Long) {
    class Session(val token: String, val title: String, val values: Map<QuickField, QuickValue>, val expiresAt: Long) {
        internal val copied = mutableSetOf<QuickField>()
    }

    var current: Session? = null
        private set

    fun start(title: String, values: Map<QuickField, QuickValue>, durationMs: Long): Session =
        Session(UUID.randomUUID().toString(), title, values, clock() + durationMs).also { current = it }

    /** The session for [token], or null when it ended, expired or belongs to an older notification. */
    fun session(token: String?): Session? {
        val session = current ?: return null
        if (clock() >= session.expiresAt) { current = null; return null }
        return session.takeIf { token != null && it.token == token }
    }

    /**
     * Records a copy. True when every field the notification offers is now copied, so the session is done.
     * A field the notification can't show (a card name, see [notificationFields]) doesn't count.
     */
    fun markCopied(session: Session, field: QuickField): Boolean {
        session.copied += field
        return session.copied.containsAll(notificationFields(session.values.keys))
    }

    fun remainingMs(session: Session): Long = (session.expiresAt - clock()).coerceAtLeast(0)

    /** Ends the session. With a [token], ends it only when it is still that session. */
    fun end(token: String? = null) {
        if (token == null || current?.token == token) current = null
    }
}

/** Sensitive clip, cleared after 30 s unless something newer was copied in the meantime. */
internal fun copySensitive(context: Context, label: String, value: String) {
    val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    val token = UUID.randomUUID().toString()
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
        putString("vault_clip_token", token)
    }
    clipboard.setPrimaryClip(clip)
    // Android may deny background clipboard access. Never erase a newer clipboard item.
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            if (clipboard.primaryClipDescription?.extras?.getString("vault_clip_token") == token) clipboard.clearPrimaryClip()
        }
    }, 30_000)
}

/**
 * After a copy in the Quick copy picker, the other fields of the same item stay in a notification for
 * [DURATION_MS], so a payment or sign-in page needs no second unlock. Values live in process memory only; the
 * notification carries a random token and a field name, never a value. The notification is hidden on the lock
 * screen, its actions need an unlocked device (Android 12+), and it never goes to a watch. The session ends after
 * [DURATION_MS], after every field in it is copied, when the screen turns off, when the notification is swiped
 * away, or on a manual lock.
 */
internal object QuickCopySession {
    const val DURATION_MS = 120_000L
    private const val CHANNEL = "quick_copy"
    private const val NOTIFICATION_ID = 7301
    internal const val ACTION_COPY = "com.privatevault.app.quickcopy.COPY"
    internal const val ACTION_END = "com.privatevault.app.quickcopy.END"
    internal const val EXTRA_TOKEN = "quick_copy_token"
    internal const val EXTRA_FIELD = "quick_copy_field"

    private val store = QuickCopyStore { SystemClock.elapsedRealtime() }
    private val handler = Handler(Looper.getMainLooper())
    private var screenOff: BroadcastReceiver? = null

    /** Whether Nuvori may post the notification: app notifications on, permission granted, channel not blocked. */
    fun canNotify(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Starts a session for an item with more than one field. Returns true when the notification shows. */
    @MainThread
    fun start(context: Context, title: String, values: Map<QuickField, QuickValue>, copied: QuickField): Boolean {
        val app = context.applicationContext
        if (values.size < 2 || !canNotify(app)) return false
        end(app)
        val session = store.start(title, values, DURATION_MS)
        store.markCopied(session, copied)
        if (!post(app, session, copied)) { end(app); return false }
        handler.postDelayed({ end(app, session.token) }, DURATION_MS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = end(app)
        }
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenOff = receiver
        return true
    }

    @androidx.annotation.VisibleForTesting
    internal fun currentTokenForTest(): String? = store.current?.token

    /** A notification action: copies one field of the current session. */
    @MainThread
    fun copy(context: Context, token: String?, field: QuickField?) {
        val app = context.applicationContext
        val session = store.session(token)
        if (session == null) {
            // Expired, ended, or a notification left over after the process restarted: remove it.
            // A token from an older notification never ends the current session.
            if (store.current == null) end(app)
            Toast.makeText(app, "Quick copy expired. Open Quick copy again.", Toast.LENGTH_SHORT).show()
            return
        }
        val value = field?.let { session.values[it] }?.let { runCatching { it.resolve() }.getOrNull() }
        if (field == null || value == null) {
            Toast.makeText(app, "Could not copy this field. Open Quick copy again.", Toast.LENGTH_SHORT).show()
            return
        }
        copySensitive(app, field.clipLabel, value)
        if (store.markCopied(session, field)) {
            // Every field is copied: nothing is left to offer, so the notification goes away now.
            end(app, session.token)
            Toast.makeText(app, "${field.done}. All fields copied.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(app, field.done, Toast.LENGTH_SHORT).show()
        post(app, session, field)
    }

    /** Ends the session and removes the notification. With a [token], only when it is still that session. */
    @MainThread
    fun end(context: Context, token: String? = null) {
        if (token != null && store.current?.token != token) return
        store.end()
        handler.removeCallbacksAndMessages(null)
        screenOff?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        screenOff = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(app: Context, session: QuickCopyStore.Session, copied: QuickField): Boolean {
        val remaining = store.remainingMs(session)
        if (remaining <= 0) return false
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val notifications = app.getSystemService(NotificationManager::class.java)
        // Default importance, but no sound or vibration: a low-importance notification lands in Android's collapsed
        // "Silent" group, where the copy buttons need an extra tap. Default never pops up over the page (only high does).
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Quick copy", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Copy the other fields of the item you picked in Quick copy, for 2 minutes."
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        fun broadcast(requestCode: Int, intent: Intent) = PendingIntent.getBroadcast(app, requestCode,
            intent.setClass(app, QuickCopyReceiver::class.java).putExtra(EXTRA_TOKEN, session.token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(app, 0, Intent(app, VaultCodesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_vault_codes)
            .setContentTitle(session.title.ifBlank { "Quick copy" })
            .setContentText("${copied.done}. Tap a field to copy it.")
            .setSubText("Quick copy")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis() + remaining)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(remaining)
            .setContentIntent(open)
            .setDeleteIntent(broadcast(99, Intent(ACTION_END)))
        notificationFields(session.values.keys).forEach { field ->
            builder.addAction(NotificationCompat.Action.Builder(0, field.button,
                broadcast(field.ordinal, Intent(ACTION_COPY).putExtra(EXTRA_FIELD, field.name)))
                .setAuthenticationRequired(true)
                .build())
        }
        return runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build()); true }.getOrDefault(false)
    }
}

/** Receives Quick copy notification actions. Not exported: only Nuvori's own PendingIntents reach it. */
class QuickCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(QuickCopySession.EXTRA_TOKEN)
        when (intent.action) {
            QuickCopySession.ACTION_COPY -> QuickCopySession.copy(context, token,
                intent.getStringExtra(QuickCopySession.EXTRA_FIELD)?.let { name -> QuickField.entries.firstOrNull { it.name == name } })
            QuickCopySession.ACTION_END -> QuickCopySession.end(context, token)
        }
    }
}
