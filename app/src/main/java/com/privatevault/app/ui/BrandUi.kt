package com.privatevault.app

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
internal fun NuvoriLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.nuvori_logo_transparent), contentDescription = stringResource(R.string.nuvori_logo), modifier = modifier)
}
