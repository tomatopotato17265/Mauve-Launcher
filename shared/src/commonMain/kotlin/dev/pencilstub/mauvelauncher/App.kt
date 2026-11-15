package dev.pencilstub.mauvelauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

val TopBarHeight = 40.dp

private val BackgroundColor = Color(0xFF000000)
private val TopBarColor = Color(0xFF111111)
private val DividerColor = Color(0xFF2A2A2A)

@Composable
@Preview
fun App(topBarModifier: Modifier = Modifier) {
    Column(Modifier.fillMaxSize().background(BackgroundColor)) {
        Box(Modifier.fillMaxWidth().height(TopBarHeight).background(TopBarColor).then(topBarModifier))
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
    }
}