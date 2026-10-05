package com.privatevault.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Shared icon set. The Windows app uses `lucide-react`; these are the same Lucide (ISC license) glyphs,
 * vendored from lucide 0.544.0 with their exact SVG path data. Circles, rects and lines from the SVGs
 * are converted to equivalent path commands. Every glyph is drawn on a 24x24 viewport with a 2px stroke
 * and round caps and joins, so `Icon(tint = …)` colors them like any Material icon.
 *
 * Use these for the shared concepts (navigation, item types, actions, status) instead of Material icons.
 */
internal object NuvoriIcons {
    // Navigation and item types
    val Home by lazy { lucide("house") {
        path("M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8")
        path("M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    } }
    val Password by lazy { lucide("key-round") {
        path("M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z")
        circle(16.5f, 7.5f, .5f, filled = true)
    } }
    val Card by lazy { lucide("credit-card") {
        rect(2f, 5f, 20f, 14f, 2f)
        line(2f, 10f, 22f, 10f)
    } }
    val Code by lazy { lucide("clock-3") {
        path("M12 6v6h4")
        circle(12f, 12f, 10f)
    } }
    val Note by lazy { lucide("file-text") {
        path("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z")
        path("M14 2v4a2 2 0 0 0 2 2h4")
        path("M10 9H8")
        path("M16 13H8")
        path("M16 17H8")
    } }
    val Question by lazy { lucide("circle-help") {
        circle(12f, 12f, 10f)
        path("M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3")
        path("M12 17h.01")
    } }
    val Autofill by lazy { lucide("user-round") {
        circle(12f, 8f, 5f)
        path("M20 21a8 8 0 0 0-16 0")
    } }
    val More by lazy { lucide("ellipsis") {
        circle(12f, 12f, 1f)
        circle(19f, 12f, 1f)
        circle(5f, 12f, 1f)
    } }
    val Devices by lazy { lucide("monitor-smartphone") {
        path("M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8")
        path("M10 19v-3.96 3.15")
        path("M7 19h5")
        rect(16f, 12f, 6f, 10f, 2f)
    } }
    val Sync by lazy { lucide("refresh-cw") {
        path("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8")
        path("M21 3v5h-5")
        path("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16")
        path("M8 16H3v5")
    } }
    val Settings by lazy { lucide("settings") {
        path("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915")
        circle(12f, 12f, 3f)
    } }
    val Search by lazy { lucide("search") {
        path("m21 21-4.34-4.34")
        circle(11f, 11f, 8f)
    } }
    val Lock by lazy { lucide("lock-keyhole") {
        circle(12f, 16f, 1f)
        rect(3f, 10f, 18f, 12f, 2f)
        path("M7 10V7a5 5 0 0 1 10 0v3")
    } }
    val Activity by lazy { lucide("activity") {
        path("M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2")
    } }
    val Passkey by lazy { lucide("fingerprint") {
        path("M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4")
        path("M14 13.12c0 2.38 0 6.38-1 8.88")
        path("M17.29 21.02c.12-.6.43-2.3.5-3.02")
        path("M2 12a10 10 0 0 1 18-6")
        path("M2 16h.01")
        path("M21.8 16c.2-2 .131-5.354 0-6")
        path("M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2")
        path("M8.65 22c.21-.66.45-1.32.57-2")
        path("M9 6.8a6 6 0 0 1 9 5.2v2")
    } }
    private const val STAR = "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"
    val Star by lazy { lucide("star") { path(STAR) } }
    /** The same star, filled (Lucide's `fill="currentColor"` usage for an active favorite). */
    val StarFilled by lazy { lucide("star-filled") { path(STAR, filled = true) } }
    val Copy by lazy { lucide("copy") {
        rect(8f, 8f, 14f, 14f, 2f)
        path("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    } }
    val Add by lazy { lucide("plus") {
        path("M5 12h14")
        path("M12 5v14")
    } }
    val Back by lazy { lucide("arrow-left") {
        path("m12 19-7-7 7-7")
        path("M19 12H5")
    } }
    val Sun by lazy { lucide("sun") {
        circle(12f, 12f, 4f)
        path("M12 2v2"); path("M12 20v2")
        path("m4.93 4.93 1.41 1.41"); path("m17.66 17.66 1.41 1.41")
        path("M2 12h2"); path("M20 12h2")
        path("m6.34 17.66-1.41 1.41"); path("m19.07 4.93-1.41 1.41")
    } }
    val Moon by lazy { lucide("moon") {
        path("M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401")
    } }
    val Monitor by lazy { lucide("monitor") {
        rect(2f, 3f, 20f, 14f, 2f)
        line(8f, 21f, 16f, 21f)
        line(12f, 17f, 12f, 21f)
    } }

    // Status
    val Success by lazy { lucide("circle-check") {
        circle(12f, 12f, 10f)
        path("m9 12 2 2 4-4")
    } }
    val Info by lazy { lucide("info") {
        circle(12f, 12f, 10f)
        path("M12 16v-4")
        path("M12 8h.01")
    } }
    val Progress by lazy { lucide("loader-circle") { path("M21 12a9 9 0 1 1-6.219-8.56") } }
    val Warning by lazy { lucide("triangle-alert") {
        path("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3")
        path("M12 9v4")
        path("M12 17h.01")
    } }
    val Error by lazy { lucide("circle-x") {
        circle(12f, 12f, 10f)
        path("m15 9-6 6")
        path("m9 9 6 6")
    } }

    // Supporting glyphs used next to the shared ones, so a row never mixes two icon styles.
    val Folder by lazy { lucide("folder") {
        path("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    } }
    val ChevronRight by lazy { lucide("chevron-right") { path("m9 18 6-6-6-6") } }
    val ChevronDown by lazy { lucide("chevron-down") { path("m6 9 6 6 6-6") } }
    val ChevronUp by lazy { lucide("chevron-up") { path("m18 15-6-6-6 6") } }
    val Close by lazy { lucide("x") {
        path("M18 6 6 18")
        path("m6 6 12 12")
    } }
    val Check by lazy { lucide("check") { path("M20 6 9 17l-5-5") } }
    val Eye by lazy { lucide("eye") {
        path("M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0")
        circle(12f, 12f, 3f)
    } }
    val EyeOff by lazy { lucide("eye-off") {
        path("M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49")
        path("M14.084 14.158a3 3 0 0 1-4.242-4.242")
        path("M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143")
        path("m2 2 20 20")
    } }
    val Delete by lazy { lucide("trash-2") {
        path("M10 11v6"); path("M14 11v6")
        path("M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6")
        path("M3 6h18")
        path("M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    } }
    val Edit by lazy { lucide("pencil") {
        path("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z")
        path("m15 5 4 4")
    } }
    val Scan by lazy { lucide("scan-line") {
        path("M3 7V5a2 2 0 0 1 2-2h2"); path("M17 3h2a2 2 0 0 1 2 2v2")
        path("M21 17v2a2 2 0 0 1-2 2h-2"); path("M7 21H5a2 2 0 0 1-2-2v-2")
        path("M7 12h10")
    } }
    val Import by lazy { lucide("import") {
        path("M12 3v12")
        path("m8 11 4 4 4-4")
        path("M8 5H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-4")
    } }
    val Shield by lazy { lucide("shield-check") {
        path("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
        path("m9 12 2 2 4-4")
    } }
    val Watch by lazy { lucide("watch") {
        path("M12 10v2.2l1.6 1")
        path("m16.13 7.66-.81-4.05a2 2 0 0 0-2-1.61h-2.68a2 2 0 0 0-2 1.61l-.78 4.05")
        path("m7.88 16.36.8 4a2 2 0 0 0 2 1.61h2.72a2 2 0 0 0 2-1.61l.81-4.05")
        circle(12f, 12f, 6f)
    } }
    val Camera by lazy { lucide("camera") {
        path("M13.997 4a2 2 0 0 1 1.76 1.05l.486.9A2 2 0 0 0 18.003 7H20a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1.997a2 2 0 0 0 1.759-1.048l.489-.904A2 2 0 0 1 10.004 4z")
        circle(12f, 13f, 3f)
    } }
    val AddPhoto by lazy { lucide("image-plus") {
        path("M16 5h6"); path("M19 2v6")
        path("M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5")
        path("m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
        circle(9f, 9f, 2f)
    } }
    val Duplicate by lazy { lucide("copy-plus") {
        line(15f, 12f, 15f, 18f)
        line(12f, 15f, 18f, 15f)
        rect(8f, 8f, 14f, 14f, 2f)
        path("M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    } }
    val Nfc by lazy { lucide("nfc") {
        path("M6 8.32a7.43 7.43 0 0 1 0 7.36")
        path("M9.46 6.21a11.76 11.76 0 0 1 0 11.58")
        path("M12.91 4.1a15.91 15.91 0 0 1 .01 15.8")
        path("M16.37 2a20.16 20.16 0 0 1 0 20")
    } }
    val Phone by lazy { lucide("smartphone") {
        rect(5f, 2f, 14f, 20f, 2f)
        path("M12 18h.01")
    } }
    val Palette by lazy { lucide("palette") {
        path("M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z")
        circle(13.5f, 6.5f, .5f, filled = true)
        circle(17.5f, 10.5f, .5f, filled = true)
        circle(6.5f, 12.5f, .5f, filled = true)
        circle(8.5f, 7.5f, .5f, filled = true)
    } }
    val Upload by lazy { lucide("upload") {
        path("M12 3v12")
        path("m17 8-5-5-5 5")
        path("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4")
    } }
    val Download by lazy { lucide("download") {
        path("M12 15V3")
        path("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4")
        path("m7 10 5 5 5-5")
    } }
    val History by lazy { lucide("history") {
        path("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8")
        path("M3 3v5h5")
        path("M12 7v5l4 2")
    } }
}

/** Collects one Lucide glyph's elements as path strings. Primitive shapes become exact path equivalents. */
internal class LucideBuilder {
    internal val parts = mutableListOf<Pair<String, Boolean>>()

    fun path(d: String, filled: Boolean = false) { parts += d to filled }

    fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false) {
        path("M${n(cx - r)} ${n(cy)}a${n(r)} ${n(r)} 0 1 0 ${n(2 * r)} 0a${n(r)} ${n(r)} 0 1 0 ${n(-2 * r)} 0z", filled)
    }

    fun rect(x: Float, y: Float, width: Float, height: Float, rx: Float = 0f) {
        if (rx <= 0f) { path("M${n(x)} ${n(y)}h${n(width)}v${n(height)}h${n(-width)}z"); return }
        path("M${n(x + rx)} ${n(y)}h${n(width - 2 * rx)}a${n(rx)} ${n(rx)} 0 0 1 ${n(rx)} ${n(rx)}" +
            "v${n(height - 2 * rx)}a${n(rx)} ${n(rx)} 0 0 1 ${n(-rx)} ${n(rx)}" +
            "h${n(-(width - 2 * rx))}a${n(rx)} ${n(rx)} 0 0 1 ${n(-rx)} ${n(-rx)}" +
            "v${n(-(height - 2 * rx))}a${n(rx)} ${n(rx)} 0 0 1 ${n(rx)} ${n(-rx)}z")
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) { path("M${n(x1)} ${n(y1)}L${n(x2)} ${n(y2)}") }

    private fun n(value: Float): String = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}

internal const val LUCIDE_STROKE_WIDTH = 2f

internal fun lucide(name: String, build: LucideBuilder.() -> Unit): ImageVector {
    val parts = LucideBuilder().apply(build).parts
    val builder = ImageVector.Builder(name = "Lucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f)
    // The color is replaced by the Icon tint; black keeps it visible in previews.
    val ink = SolidColor(Color.Black)
    parts.forEach { (d, filled) ->
        builder.addPath(
            pathData = addPathNodes(d),
            fill = if (filled) ink else null,
            stroke = ink,
            strokeLineWidth = LUCIDE_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
