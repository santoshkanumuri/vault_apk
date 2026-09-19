package com.privatevault.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val isAmex = resource == R.drawable.network_amex
    // Fit drew the old 74 x 34 dp box at about 56 dp wide; 66 dp adds roughly 5 dp on each side.
    val logoModifier = modifier.width(if (isAmex) 66.dp else 64.dp).height(if (isAmex) 34.dp else 32.dp)
    if (resource != null) {
        // Single-color wordmarks follow card ink for contrast on every card color.
        val tint = if (resource == R.drawable.network_visa || resource == R.drawable.network_amex) ColorFilter.tint(LocalContentColor.current) else null
        Image(painterResource(resource), network, logoModifier,
            contentScale = if (isAmex) ContentScale.FillBounds else ContentScale.Fit, colorFilter = tint)
    } else if (network.isNotBlank()) {
        Text(network, logoModifier, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
