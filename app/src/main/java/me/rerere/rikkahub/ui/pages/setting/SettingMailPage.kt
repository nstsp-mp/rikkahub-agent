package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import android.text.format.DateUtils
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Mail01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.mail.MailAuthManager
import me.rerere.rikkahub.data.mail.MailAuthStatus
import me.rerere.rikkahub.data.mail.MailTracker
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
fun SettingMailPage() {
    val auth = koinInject<MailAuthManager>()
    val accounts by auth.accounts.collectAsStateWithLifecycle()
    val status by auth.status.collectAsStateWithLifecycle()
    val tracker = koinInject<MailTracker>()
    val trackerStatus by tracker.status.collectAsStateWithLifecycle()
    val awaiting by tracker.awaitingFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_mail)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.setting_mail_page_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(accounts, key = { it.id }) { account ->
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    ListItem(
                        leadingContent = { Icon(HugeIcons.Mail01, contentDescription = null) },
                        headlineContent = { Text(account.email) },
                        supportingContent = {
                            if (account.displayName.isNotBlank()) Text(account.displayName)
                        },
                        trailingContent = {
                            TextButton(onClick = { auth.signOut(account.id) }) {
                                Text(stringResource(R.string.setting_mail_page_sign_out))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                Button(
                    onClick = { auth.startLogin() },
                    enabled = status != MailAuthStatus.Waiting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (status == MailAuthStatus.Waiting) R.string.setting_mail_page_waiting
                            else R.string.setting_mail_page_sign_in
                        )
                    )
                }
            }
            (status as? MailAuthStatus.Error)?.let { error ->
                item {
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (accounts.isNotEmpty()) {
                item {
                    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.setting_mail_page_track_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_mail_page_track_desc)) },
                            trailingContent = {
                                Switch(checked = trackerStatus.enabled, onCheckedChange = tracker::setEnabled)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (trackerStatus.enabled) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        if (trackerStatus.lastRunAt == 0L) stringResource(R.string.setting_mail_page_never_checked)
                                        else stringResource(
                                            R.string.setting_mail_page_last_checked,
                                            DateUtils.getRelativeTimeSpanString(trackerStatus.lastRunAt).toString(),
                                            trackerStatus.lastSummary,
                                        )
                                    )
                                },
                                supportingContent = trackerStatus.lastError?.let { error ->
                                    { Text(error, color = MaterialTheme.colorScheme.error) }
                                },
                                trailingContent = {
                                    TextButton(onClick = tracker::checkNow, enabled = !trackerStatus.running) {
                                        Text(stringResource(R.string.setting_mail_page_check_now))
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
                if (awaiting.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.setting_mail_page_awaiting, awaiting.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(awaiting, key = { "tracked-" + it.messageId }) { item ->
                        val overdue = item.dueAt <= System.currentTimeMillis()
                        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                            ListItem(
                                headlineContent = { Text(item.subject.ifBlank { "(no subject)" }) },
                                supportingContent = {
                                    Text(
                                        "${item.fromName.ifBlank { item.fromAddress }} · " + stringResource(
                                            if (overdue) R.string.setting_mail_page_overdue else R.string.setting_mail_page_due,
                                            DateUtils.getRelativeTimeSpanString(item.dueAt).toString(),
                                        ),
                                        color = if (overdue) MaterialTheme.colorScheme.error else Color.Unspecified,
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = { scope.launch { tracker.markHandled(item.messageId) } }) {
                                        Text(stringResource(R.string.setting_mail_page_mark_handled))
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.setting_mail_page_tools_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
