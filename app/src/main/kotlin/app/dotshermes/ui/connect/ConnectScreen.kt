package app.dotshermes.ui.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import androidx.compose.ui.res.stringResource
import app.dotshermes.R

/** Écran d'appairage avec le gateway d'Hermes (plugin « dots »). */
@Composable
fun ConnectScreen(
    connection: ConnectionState,
    appearance: DotAppearance,
    initialUrl: String,
    onConnect: (url: String, token: String) -> Unit,
) {
    val palette = LocalDotPalette.current
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    val connecting = connection is ConnectionState.Connecting

    val mood = when (connection) {
        is ConnectionState.Connecting -> DotMood.Thinking
        is ConnectionState.Failed -> DotMood.Sleeping
        is ConnectionState.Connected -> DotMood.Happy
        ConnectionState.Disconnected -> DotMood.Idle
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Surface)
            .drawBehind {
                // Halo pastel derrière le personnage, comme les visuels Dots.
                drawRect(
                    Brush.radialGradient(
                        listOf(palette.light, Ink.Surface.copy(alpha = 0f)),
                        center = Offset(size.width / 2f, size.height * 0.24f),
                        radius = size.width * 0.85f,
                    ),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            ClayDot(palette, Modifier.size(220.dp), mood = mood, accessory = appearance.accessory, species = appearance.species)
            Text("Hermes", style = MaterialTheme.typography.displaySmall, color = Ink.Primary)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.connect_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Secondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))

            GroupedCard(color = Ink.Grouped) {
                FieldRow(stringResource(R.string.connect_gateway), url, { url = it }, enabled = !connecting, keyboardType = KeyboardType.Uri, placeholder = stringResource(R.string.connect_gateway_hint))
                Hairline()
                FieldRow(stringResource(R.string.connect_token), token, { token = it }, enabled = !connecting, secret = true, placeholder = stringResource(R.string.connect_token_hint))
            }
            Text(
                stringResource(R.string.connect_token_help),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Secondary,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp),
            )
            Spacer(Modifier.height(28.dp))

            Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = connection,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "connect-status",
                ) { state ->
                    when (state) {
                        is ConnectionState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = palette.base, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(state.step, style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary)
                        }
                        is ConnectionState.Failed -> Text(state.reason, style = MaterialTheme.typography.bodyMedium, color = Ink.Danger, textAlign = TextAlign.Center)
                        else -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = stringResource(if (connecting) R.string.connect_connecting else R.string.connect_button),
                onClick = { onConnect(url, token) },
                modifier = Modifier.fillMaxWidth(),
                container = palette.base,
                content = palette.onBase,
                enabled = !connecting && url.isNotBlank() && token.isNotBlank(),
                height = 54.dp,
            )
            Text(
                stringResource(R.string.connect_demo),
                style = MaterialTheme.typography.labelLarge,
                color = palette.deep,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !connecting) { onConnect(GatewayConfig.DEMO_URL, "") }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ink.Tertiary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Secondary),
                cursorBrush = SolidColor(palette.base),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
