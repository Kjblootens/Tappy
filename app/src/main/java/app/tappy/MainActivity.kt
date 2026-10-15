package app.tappy

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

    private data class Setup(
        val nfcAvailable: Boolean = true,
        val nfcEnabled: Boolean = false,
        val accessibility: Boolean = false,
        val admin: Boolean = false,
        val owner: Boolean = false,
    )

    private var nfcAdapter: NfcAdapter? = null
    private var setup by mutableStateOf(Setup())
    private var pairing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent { TappyTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        // While Tappy is open, read tags here directly (pairing or toggling) instead of via NfcTagActivity.
        nfcAdapter?.enableReaderMode(
            this,
            ::onTag,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        pairing = false
    }

    private fun refreshSetup() {
        setup = Setup(
            nfcAvailable = nfcAdapter != null,
            nfcEnabled = nfcAdapter?.isEnabled == true,
            accessibility = BlockerService.isEnabled(this),
            admin = BlockController.isAdminActive(this),
            owner = BlockController.isDeviceOwner(this),
        )
    }

    /** Called on a binder thread by reader mode. */
    private fun onTag(tag: Tag) {
        val id = TagUtils.idOf(tag)
        if (pairing) {
            val wrote = TagUtils.writeLaunchRecord(tag, packageName)
            runOnUiThread {
                pairing = false
                if (TappyStore.setPairedTag(id)) {
                    toast(if (wrote) "Tag paired." else "Tag paired (read-only tag: it works, but Android may ask which app to open).")
                } else {
                    toast("Can't change the tag while blocking.")
                }
            }
        } else {
            val result = BlockController.onTagScanned(this, id)
            runOnUiThread { toast(BlockController.describe(result)) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun openAccessibilitySettings() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun requestAdmin() = startActivity(
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, TappyAdminReceiver.component(this))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets Tappy stop itself from being uninstalled while a block is active.",
            )
    )

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    @Composable
    private fun Screen() {
        val state by TappyStore.state.collectAsStateWithLifecycle()
        var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
        var query by remember { mutableStateOf("") }
        var confirmEmergency by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps() } }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                Modifier.fillMaxSize().systemBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { StatusCard(state) }
                item { SetupCard(state) }

                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "Apps to block (${state.blockedPackages.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (state.blocking) {
                            Text(
                                "Locked while blocking.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search apps") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }

                val list = apps
                if (list == null) {
                    item { Text("Loading apps…", Modifier.padding(16.dp)) }
                } else {
                    val shown = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                    items(shown, key = { it.packageName }) { app ->
                        val checked = app.packageName in state.blockedPackages
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.blocking) {
                                    TappyStore.setBlockedPackages(
                                        if (checked) state.blockedPackages - app.packageName
                                        else state.blockedPackages + app.packageName
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(app.icon, null, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, Modifier.weight(1f))
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !state.blocking)
                        }
                    }
                }

                if (state.blocking) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text("Lost your tag?", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Emergency unlocks left: ${state.emergencyUnlocksLeft} of ${TappyStore.EMERGENCY_UNLOCKS}. These never come back.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = { confirmEmergency = true },
                                enabled = state.emergencyUnlocksLeft > 0,
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Emergency unlock") }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        if (confirmEmergency) {
            AlertDialog(
                onDismissRequest = { confirmEmergency = false },
                title = { Text("Use an emergency unlock?") },
                text = { Text("This ends the block without your tag. You have ${state.emergencyUnlocksLeft} left, and they can't be recharged.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmEmergency = false
                        if (BlockController.emergencyRelease(this@MainActivity)) toast("Unlocked.")
                    }) { Text("Unlock") }
                },
                dismissButton = { TextButton(onClick = { confirmEmergency = false }) { Text("Cancel") } },
            )
        }

        if (pairing) {
            AlertDialog(
                onDismissRequest = { pairing = false },
                title = { Text("Pair a tag") },
                text = { Text("Hold an NFC tag against the back of your phone.") },
                confirmButton = { TextButton(onClick = { pairing = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun StatusCard(state: TappyStore.State) {
        val colors = if (state.blocking) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
        Card(Modifier.fillMaxWidth().padding(16.dp), colors = colors) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.blocking) Icon(Icons.Filled.Lock, null, Modifier.padding(end = 8.dp))
                    Text(
                        if (state.blocking) "Blocking" else "Not blocking",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val detail = when {
                    state.blocking -> "${state.blockedPackages.size} apps locked since " +
                        DateUtils.getRelativeTimeSpanString(state.blockStartedAt) +
                        ". Tap your tag to unlock."
                    state.pairedTagId == null -> "Pair a tag below to get started."
                    else -> "Tap your tag to block ${state.blockedPackages.size} apps."
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun SetupCard(state: TappyStore.State) {
        val s = setup
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))

                SetupRow(
                    ok = s.nfcEnabled,
                    title = if (s.nfcAvailable) "NFC is on" else "This phone has no NFC",
                    action = if (s.nfcAvailable && !s.nfcEnabled) "Turn on" else null,
                ) { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }

                SetupRow(
                    ok = state.pairedTagId != null,
                    title = if (state.pairedTagId != null) "Tag paired" else "No tag paired",
                    subtitle = state.pairedTagId?.let { "ID $it" },
                    action = when {
                        state.blocking -> null
                        state.pairedTagId == null -> "Pair"
                        else -> "Re-pair"
                    },
                ) { pairing = true }

                SetupRow(
                    ok = s.accessibility,
                    title = "App blocker service",
                    subtitle = if (s.accessibility) null else
                        "Turn on Tappy under Accessibility. If it's greyed out: App info → ⋮ → Allow restricted settings.",
                    action = if (s.accessibility) null else "Open",
                ) { openAccessibilitySettings() }

                SetupRow(
                    ok = s.admin,
                    title = "Uninstall protection",
                    subtitle = when {
                        s.owner -> "Device owner: uninstall is hard-blocked and apps are suspended during a block."
                        s.admin -> "Tappy can't be uninstalled while blocking."
                        else -> "Required to stop Tappy being uninstalled during a block."
                    },
                    action = when {
                        !s.admin -> "Enable"
                        state.blocking -> null
                        s.owner -> "Remove owner"
                        else -> "Disable"
                    },
                ) {
                    when {
                        !s.admin -> requestAdmin()
                        s.owner -> BlockController.clearDeviceOwner(this@MainActivity)
                        else -> BlockController.deactivateAdmin(this@MainActivity)
                    }
                    refreshSetup()
                }
            }
        }
    }

    @Composable
    private fun SetupRow(
        ok: Boolean,
        title: String,
        subtitle: String? = null,
        action: String? = null,
        onAction: () -> Unit = {},
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
