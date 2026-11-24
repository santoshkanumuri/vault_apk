package com.privatevault.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * A local check for a rooted phone or an attached debugger (security review L-12). It only warns:
 * root can hide itself, so a clean result proves nothing, and Nuvori must keep working on phones
 * people rooted on purpose. A server-backed check such as Play Integrity needs a backend Nuvori
 * does not have.
 */
internal object DeviceIntegrity {
    /** What was seen. Each is a reason to warn; none blocks anything. */
    data class Signals(
        val debuggerAttached: Boolean,
        val debuggableRelease: Boolean,
        val suBinary: Boolean,
        val testKeys: Boolean,
    )

    enum class Concern { DEBUGGER, ROOT }

    /** The concerns behind [signals], most serious first. Empty when nothing was seen. */
    fun assess(signals: Signals): List<Concern> = buildList {
        if (signals.debuggerAttached || signals.debuggableRelease) add(Concern.DEBUGGER)
        if (signals.suBinary || signals.testKeys) add(Concern.ROOT)
    }

    /** One sentence for the UI, or null when nothing was seen. */
    fun warning(concerns: List<Concern>): String? = when {
        Concern.DEBUGGER in concerns && Concern.ROOT in concerns ->
            "This phone looks rooted and a debugger can reach Nuvori. Other apps could read the vault while it is open. Pair devices only from a phone you trust."
        Concern.DEBUGGER in concerns ->
            "A debugger can reach Nuvori on this phone. It could read the vault while it is open. Pair devices only from a phone you trust."
        Concern.ROOT in concerns ->
            "This phone looks rooted. Apps with root access could read the vault while it is open. Pair devices only from a phone you trust."
        else -> null
    }

    /** Folders where su usually lives on rooted phones. */
    internal val SU_FOLDERS = listOf("/system/bin", "/system/xbin", "/sbin", "/system/sbin", "/vendor/bin",
        "/su/bin", "/data/local/bin", "/data/local/xbin", "/system/bin/failsafe")

    fun signals(context: Context, exists: (String) -> Boolean = { File(it).exists() }): Signals = Signals(
        debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger(),
        // A debug build (package suffix .debug) is debuggable on purpose; a release build never should be.
        debuggableRelease = !context.packageName.endsWith(".debug") && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        suBinary = SU_FOLDERS.any { exists("$it/su") },
        testKeys = Build.TAGS?.contains("test-keys") == true,
    )

    fun check(context: Context): List<Concern> = runCatching { assess(signals(context)) }.getOrDefault(emptyList())
}
