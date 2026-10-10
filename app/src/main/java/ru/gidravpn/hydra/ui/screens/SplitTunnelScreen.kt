package ru.gidravpn.hydra.ui.screens

import androidx.compose.ui.res.stringResource
import ru.gidravpn.hydra.R

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/** Установленное приложение для списка split tunneling. */
data class AppEntry(val packageName: String, val label: String, val isSystem: Boolean)

private val ipCidrRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?$")

internal fun normalizeNetRuleValue(type: NetRuleType, raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when (type) {
        NetRuleType.IP_CIDR -> trimmed.takeIf { ipCidrRegex.matches(it) }
        else -> trimmed.removePrefix("https://").removePrefix("http://").substringBefore("/")
    }
}

@Composable
internal fun NetRuleRow(rule: NetworkRule, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(stringResource(rule.type.labelRes), color = TextMuted, fontSize = 10.sp)
            Text(rule.value, color = TextPrimary, fontSize = 13.sp)
        }
        Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickableNoRipple(onDelete).padding(8.dp))
    }
}

@Composable
private fun OutlinedActionButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(CardBg)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) { Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ModeChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentCyan.copy(alpha = 0.15f) else CardBg)
            .border(1.dp, if (active) AccentCyan else Border, RoundedCornerShape(10.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (active) AccentCyan else TextSecondary,
            fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun AppRow(app: AppEntry, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) AccentCyan.copy(alpha = 0.08f) else CardBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName + if (app.isSystem) " • " + stringResource(R.string.split_system_tag) else "",
                color = TextMuted, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(
            checked = checked, onCheckedChange = { onClick() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = AccentCyan, checkmarkColor = Bg)
        )
    }
}

/** Запускабельные приложения (launcher intent) + флаг системных. */
internal fun installedApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(
                    packageName = pkg,
                    label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                    isSystem = runCatching {
                        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    }.getOrDefault(false),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
