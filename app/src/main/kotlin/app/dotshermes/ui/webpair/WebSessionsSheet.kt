package app.dotshermes.ui.webpair

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotshermes.R
import app.dotshermes.data.AgentSummary
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.webpair.WebSession
import app.dotshermes.ui.activity.relative
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.displayName
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/**
 * Les navigateurs appairés, agent par agent (trame `web_sessions`) : chacun a son jeton à lui,
 * révocable sans toucher au téléphone ni aux autres navigateurs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSessionsSheet(viewModel: WebPairViewModel, onConnectBrowser: () -> Unit, onDismiss: () -> Unit) {
    val agents by viewModel.agentList.collectAsStateWithLifecycle()
    var revoking by remember { mutableStateOf<Pair<AgentSummary, WebSession>?>(null) }
    // À l'ouverture, et quand un agent se (re)connecte : la liste vient du gateway, pas d'un cache.
    val connectedIds = agents.filter { it.connection is ConnectionState.Connected }.map { it.id }
    LaunchedEffect(connectedIds) { viewModel.refreshSessions() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.web_sessions_title),
                style = MaterialTheme.typography.titleLarge, color = Ink.Primary,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                stringResource(R.string.web_sessions_hint),
                style = MaterialTheme.typography.bodySmall, color = Ink.Secondary,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp),
            )
            agents.forEach { agent ->
                AgentSessions(agent, viewModel) { session -> revoking = agent to session }
            }
            Spacer(Modifier.size(16.dp))
            GroupedCard {
                Text(
                    stringResource(R.string.web_connect_row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalDotPalette.current.deep,
                    modifier = Modifier.fillMaxWidth().clickableRow(onConnectBrowser),
                )
            }
        }
    }

    revoking?.let { (agent, session) ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.web_revoke_title)) },
            text = { Text(stringResource(R.string.web_revoke_text, session.label.ifEmpty { session.origin }, agent.appearance.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    viewModel.revoke(agent.id, session.id)
                }) { Text(stringResource(R.string.web_revoke), color = Ink.Danger) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.action_cancel), color = Ink.Primary) } },
            containerColor = Ink.Raised,
        )
    }
}

@Composable
private fun AgentSessions(agent: AgentSummary, viewModel: WebPairViewModel, onRevoke: (WebSession) -> Unit) {
    val sessions by remember(agent.id) { viewModel.sessions(agent.id) }.collectAsStateWithLifecycle()
    val look = agent.appearance
    val connected = agent.connection is ConnectionState.Connected

    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ClayDot(look.palette, Modifier.size(28.dp), accessory = look.accessory, species = look.species, showShadow = false)
        Spacer(Modifier.width(8.dp))
        Text(look.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    GroupedCard {
        val list = sessions
        when {
            !connected -> Placeholder(stringResource(R.string.web_sessions_offline))
            list == null -> Placeholder(stringResource(R.string.web_sessions_loading))
            list.isEmpty() -> Placeholder(stringResource(R.string.web_sessions_empty))
            else -> list.forEachIndexed { index, session ->
                if (index > 0) Hairline()
                SessionRow(session) { onRevoke(session) }
            }
        }
    }
}

@Composable
private fun SessionRow(session: WebSession, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(session.label.ifEmpty { session.origin }, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val seen = session.lastSeen ?: session.createdAt
            Text(
                listOfNotNull(session.origin.substringAfter("://"), seen?.let { stringResource(R.string.web_session_seen, relative(it)) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRevoke) { Text(stringResource(R.string.web_revoke), color = Ink.Danger) }
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp))
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick)).padding(horizontal = 16.dp, vertical = 13.dp)
