package dev.pencilstub.mauvelauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.pencilstub.mauvelauncher.generated.resources.Res
import dev.pencilstub.mauvelauncher.generated.resources.chevron_down
import dev.pencilstub.mauvelauncher.generated.resources.login
import dev.pencilstub.mauvelauncher.generated.resources.nunito
import dev.pencilstub.mauvelauncher.generated.resources.plus
import dev.pencilstub.mauvelauncher.generated.resources.settings
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource

val TopBarHeight = 40.dp

private val BackgroundColor = Color(0xFF000000)
private val TopBarColor = Color(0xFF111111)
private val DividerColor = Color(0xFF2A2A2A)
private val TabTrackColor = Color(0xFF1F1F1F)
private val SelectedTabColor = Color(0xFF3A3A3A)
private val SelectedTabTextColor = Color(0xFFFFFFFF)
private val TabTextColor = Color(0xFFA3A3A3)
private val AccentColor = Color(0xFF46217D)
private val SignInTextColor = Color(0xFFFFFFFF)
private val SettingsIconHoverColor = Color(0xFFFFFFFF)
private val DialogTextColor = Color(0xFFD4D4D4)

private enum class Tab { Home, Library, Discover }

sealed interface AccountStatus {
    data object SignedOut : AccountStatus
    data object SigningIn : AccountStatus
    data class SignedIn(val playerName: String, val face: ImageBitmap? = null) : AccountStatus
}

@Composable
@Preview
fun App(
    topBarModifier: Modifier = Modifier,
    topBarStartInset: Dp = 0.dp,
    accountStatus: AccountStatus = AccountStatus.SignedOut,
    onSignInClick: () -> Unit = {},
    signInError: String? = null,
    onDismissSignInError: () -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(Tab.Home) }

    Column(Modifier.fillMaxSize().background(BackgroundColor)) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).background(TopBarColor).then(topBarModifier)
                .padding(start = topBarStartInset + 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
            Spacer(Modifier.weight(1f))
            SettingsButton(onClick = {})
            AccountButton(accountStatus, onClick = onSignInClick)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
    }

    if (signInError != null) {
        SignInErrorDialog(signInError, onDismiss = onDismissSignInError)
    }
}

@Composable
private fun TopBarTab(label: String, selected: Boolean, onClick: () -> Unit) {
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
            fontFamily = nunito(FontWeight.SemiBold),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Icon(
        painterResource(Res.drawable.settings),
        contentDescription = "Settings",
        modifier = Modifier.size(20.dp)
            .hoverable(interactionSource)
            .clickable(interactionSource, indication = null, role = Role.Button, onClick = onClick),
        tint = if (hovered) SettingsIconHoverColor else TabTextColor,
    )
}

@Composable
private fun AccountButton(status: AccountStatus, onClick: () -> Unit) {
    val signedIn = status is AccountStatus.SignedIn

    var menuOpen by remember(signedIn) { mutableStateOf(false) }

    Box(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(if (signedIn) TabTrackColor else AccentColor)
                .clickable(role = if (signedIn) Role.DropdownList else Role.Button) {
                    if (signedIn) menuOpen = !menuOpen else onClick()
                }
                .padding(start = if (signedIn) 4.dp else 14.dp, end = if (status == AccountStatus.SigningIn) 14.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status is AccountStatus.SignedIn) {
                // inset as much from the side as from the top and bottom, corners following the button's
                PlayerFace(status.face, size = 20.dp, cornerRadius = 4.dp)
                Spacer(Modifier.width(2.dp))
            }
            Text(
                when (status) {
                    AccountStatus.SignedOut -> "Sign in"
                    AccountStatus.SigningIn -> "Signing in…"
                    is AccountStatus.SignedIn -> status.playerName
                },
                color = SignInTextColor,
                fontSize = 15.sp,
                fontFamily = nunito(FontWeight.SemiBold),
                fontWeight = FontWeight.SemiBold,
            )
            when (status) {
                AccountStatus.SignedOut ->
                    Icon(painterResource(Res.drawable.login), contentDescription = null, Modifier.size(18.dp), tint = SignInTextColor)
                is AccountStatus.SignedIn ->
                    Icon(
                        painterResource(Res.drawable.chevron_down),
                        contentDescription = null,
                        Modifier.size(16.dp).rotate(if (menuOpen) 180f else 0f),
                        tint = TabTextColor,
                    )
                AccountStatus.SigningIn -> {}
            }
        }

        if (status is AccountStatus.SignedIn && menuOpen) {
            AccountMenu(
                playerName = status.playerName,
                face = status.face,
                onDismiss = { menuOpen = false },
                onAddAccount = {
                    menuOpen = false
                    onClick()
                },
            )
        }
    }
}

// opens below the account button, right edges lined up
private class BelowAnchorEnd(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0),
        y = anchorBounds.bottom + gap,
    )
}

@Composable
private fun AccountMenu(playerName: String, face: ImageBitmap?, onDismiss: () -> Unit, onAddAccount: () -> Unit) {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val positionProvider = remember(gap) { BelowAnchorEnd(gap) }
    Popup(positionProvider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier.width(IntrinsicSize.Max)
                .widthIn(min = 200.dp)
                .clip(shape)
                .background(TopBarColor)
                .border(1.dp, DividerColor, shape)
                .padding(4.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerFace(face, size = 32.dp, cornerRadius = 6.dp)
                Text(
                    playerName,
                    color = SignInTextColor,
                    fontSize = 15.sp,
                    fontFamily = nunito(FontWeight.SemiBold),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(DividerColor))
            AccountMenuItem("Add account", Res.drawable.plus, onClick = onAddAccount)
        }
    }
}

@Composable
private fun PlayerFace(face: ImageBitmap?, size: Dp, cornerRadius: Dp) {
    val modifier = Modifier.size(size).clip(RoundedCornerShape(cornerRadius))
    if (face == null) {
        Box(modifier.background(SelectedTabColor))
    } else {
        Image(face, contentDescription = null, modifier, filterQuality = FilterQuality.None)
    }
}

@Composable
private fun AccountMenuItem(label: String, icon: DrawableResource, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) TabTrackColor else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(16.dp), tint = if (hovered) SignInTextColor else TabTextColor)
        Text(
            label,
            color = if (hovered) SignInTextColor else DialogTextColor,
            fontSize = 14.sp,
            fontFamily = nunito(FontWeight.Medium),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SignInErrorDialog(message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.widthIn(max = 400.dp)
                .clip(shape)
                .background(TopBarColor)
                .border(1.dp, DividerColor, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Couldn't sign in",
                color = SelectedTabTextColor,
                fontSize = 17.sp,
                fontFamily = nunito(FontWeight.Bold),
                fontWeight = FontWeight.Bold,
            )
            Text(message, color = DialogTextColor, fontSize = 14.sp, fontFamily = nunito(FontWeight.Normal), lineHeight = 20.sp)
            Box(
                Modifier.align(Alignment.End)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentColor)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
                Text("OK", color = SignInTextColor, fontSize = 15.sp, fontFamily = nunito(FontWeight.SemiBold), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun nunito(weight: FontWeight): FontFamily {
    val font = Font(Res.font.nunito, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    return remember(font) { FontFamily(font) }
}