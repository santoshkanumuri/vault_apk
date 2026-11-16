package com.privatevault.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

@Composable
internal fun NetworkLogo(network: String, modifier: Modifier = Modifier) {
    val resource = when (network.lowercase().replace(" ", "").replace("-", "")) {
        "visa" -> R.drawable.network_visa
        "mastercard" -> R.drawable.network_mastercard
        "discover" -> if (LocalContentColor.current.luminance() > .5f) R.drawable.network_discover_light else R.drawable.network_discover
        "americanexpress", "amex" -> R.drawable.network_amex
        "dinersclub", "diners" -> R.drawable.network_diners
        "jcb" -> R.drawable.network_jcb
        "unionpay", "chinaunionpay" -> R.drawable.network_unionpay
        "rupay" -> if (LocalContentColor.current.luminance() > .5f) R.drawable.network_rupay_light else R.drawable.network_rupay
        else -> null
    }
    val logoModifier = modifier.width(64.dp).height(32.dp)
    if (resource != null) {
        // The Visa wordmark follows card ink for contrast on every card color. The Amex blue box
        // keeps its own blue and white, which read on every card color.
        val tint = if (resource == R.drawable.network_visa) ColorFilter.tint(LocalContentColor.current) else null
        Image(painterResource(resource), network, logoModifier,
            // The square Amex box sits at the right edge, where the wide marks end.
            alignment = if (resource == R.drawable.network_amex) Alignment.CenterEnd else Alignment.Center,
            contentScale = ContentScale.Fit, colorFilter = tint)
    } else if (network.isNotBlank()) {
        Text(network, logoModifier, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
