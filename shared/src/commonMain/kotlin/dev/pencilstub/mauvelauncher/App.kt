package dev.pencilstub.mauvelauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mauvelauncherkmptest.shared.generated.resources.Res
import mauvelauncherkmptest.shared.generated.resources.nunito
import org.jetbrains.compose.resources.Font

val TopBarHeight = 40.dp

private val BackgroundColor = Color(0xFF000000)
private val TopBarColor = Color(0xFF111111)
private val DividerColor = Color(0xFF2A2A2A)
private val TabTrackColor = Color(0xFF1F1F1F)
private val SelectedTabColor = Color(0xFF3A3A3A)
private val SelectedTabTextColor = Color(0xFFFFFFFF)
private val TabTextColor = Color(0xFFA3A3A3)

private enum class Tab { Home, Library, Discover }

@Composable
@Preview
fun App(topBarModifier: Modifier = Modifier, topBarStartInset: Dp = 0.dp) {
    var selectedTab by remember { mutableStateOf(Tab.Home) }

    Column(Modifier.fillMaxSize().background(BackgroundColor)) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).background(TopBarColor).then(topBarModifier)
                .padding(start = topBarStartInset + 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(
                Modifier.fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TabTrackColor)
                    .selectableGroup(),
            ) {
                for (tab in Tab.entries) {
                    TopBarTab(tab.name, selected = tab == selectedTab, onClick = { selectedTab = tab })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
    }
}

@Composable
private fun TopBarTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val weight = FontWeight.SemiBold
    val font = Font(Res.font.nunito, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    Box(
        Modifier.fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SelectedTabColor else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) SelectedTabTextColor else TabTextColor,
            fontSize = 15.sp,
            fontFamily = remember(font) { FontFamily(font) },
            fontWeight = weight,
        )
    }
}