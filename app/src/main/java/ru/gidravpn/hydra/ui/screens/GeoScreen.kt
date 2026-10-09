package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.geo.CustomGeoSource
import ru.gidravpn.hydra.data.geo.GeoKind
import ru.gidravpn.hydra.data.geo.GeoSources
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*
import java.text.DateFormat
import java.util.Date

/** «Geo-базы» (0.7.13): источники, расписание, статус скачанных баз, откат, свои списки. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeoScreen(vm: MainViewModel) {
    val s by vm.geoSettings.collectAsState()
    val ui by vm.geoUi.collectAsState()
    LaunchedEffect(Unit) { vm.refreshGeoEntries() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.geo_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Text(stringResource(R.string.geo_intro), color = TextMuted, fontSize = 12.sp)

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.geo_auto), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.geo_auto_sub, s.intervalHours), color = TextMuted, fontSize = 11.sp)
                }
                Switch(checked = s.autoUpdate, onCheckedChange = { v -> vm.setGeoSettings { it.copy(autoUpdate = v) } })
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 12, 24, 72, 168).forEach { h ->
                    GeoChip(stringResource(R.string.geo_hours, h), s.intervalHours == h) { vm.setGeoSettings { it.copy(intervalHours = h) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            GeoChip(if (ui.busy) stringResource(R.string.geo_updating) else stringResource(R.string.geo_update_now), false) { vm.updateGeoNow() }
            ui.message?.takeIf { it.isNotEmpty() }?.let { Text(it, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.geo_sources), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.geo_source_ip), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GeoSources.ALL.forEach { src -> GeoChip(src.title, s.ipSource == src.id) { vm.setGeoSettings { it.copy(ipSource = src.id) } } }
            }
            Text(stringResource(R.string.geo_source_site), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GeoSources.ALL.forEach { src -> GeoChip(src.title, s.siteSource == src.id) { vm.setGeoSettings { it.copy(siteSource = src.id) } } }
            }
            Text(stringResource(R.string.geo_sources_note), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }

        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.geo_extra), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.geo_extra_sub), color = TextMuted, fontSize = 11.sp)
            var name by remember { mutableStateOf("") }
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.geo_name_hint), fontSize = 11.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GeoChip(stringResource(R.string.geo_add_ip), false) {
                    val n = name.trim().lowercase(); if (n.isNotEmpty()) { vm.setGeoSettings { it.copy(extraIp = (it.extraIp + n).distinct()) }; name = "" }
                }
                GeoChip(stringResource(R.string.geo_add_site), false) {
                    val n = name.trim().lowercase(); if (n.isNotEmpty()) { vm.setGeoSettings { it.copy(extraSite = (it.extraSite + n).distinct()) }; name = "" }
                }
            }
            (s.extraIp.map { GeoKind.IP to it } + s.extraSite.map { GeoKind.SITE to it }).forEach { (k, n) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${k.dir}: $n", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickableNoRipple {
                        vm.setGeoSettings { if (k == GeoKind.IP) it.copy(extraIp = it.extraIp - n) else it.copy(extraSite = it.extraSite - n) }
                    }.padding(8.dp))
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.geo_custom), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.geo_custom_sub), color = TextMuted, fontSize = 11.sp)
            var kind by remember { mutableStateOf(GeoKind.IP) }
            var type by remember { mutableStateOf(CustomGeoSource.TYPE_LIST) }
            var cname by remember { mutableStateOf("") }
            var url by remember { mutableStateOf("") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                GeoChip("geoip", kind == GeoKind.IP) { kind = GeoKind.IP }
                GeoChip("geosite", kind == GeoKind.SITE) { kind = GeoKind.SITE }
                listOf(CustomGeoSource.TYPE_LIST, CustomGeoSource.TYPE_SRS, CustomGeoSource.TYPE_DAT).forEach { t -> GeoChip(".$t", type == t) { type = t } }
            }
            OutlinedTextField(value = cname, onValueChange = { cname = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.geo_name_hint), fontSize = 11.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
            OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("URL", fontSize = 11.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
            GeoChip(stringResource(R.string.geo_custom_add), false) {
                val n = cname.trim().lowercase(); val u = url.trim()
                if (n.isNotEmpty() && u.startsWith("https://")) {
                    vm.setGeoSettings { it.copy(custom = it.custom.filterNot { c -> c.kind == kind && c.name == n } + CustomGeoSource(kind, n, u, type)) }
                    cname = ""; url = ""
                }
            }
            s.custom.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.kind.dir}/${c.name} ← ${c.url}", color = TextPrimary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickableNoRipple { vm.setGeoSettings { it.copy(custom = it.custom - c) } }.padding(8.dp))
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.geo_downloaded), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (ui.entries.isEmpty()) Text(stringResource(R.string.geo_none), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
            ui.entries.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.kind.dir}/${e.name}", color = TextPrimary, fontSize = 13.sp)
                        Text("${e.source} · ${e.size / 1024} КБ · ${df.format(Date(e.updatedAt))} · ${e.sha256.take(8)}", color = TextMuted, fontSize = 10.sp)
                    }
                    if (e.hasPrev) Text("↩", color = AccentCyan, fontSize = 16.sp, modifier = Modifier.clickableNoRipple { vm.rollbackGeo(e.kind, e.name) }.padding(8.dp))
                    Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickableNoRipple { vm.removeGeo(e.kind, e.name) }.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun GeoChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentCyan.copy(alpha = 0.15f) else CardBg)
            .border(1.dp, if (active) AccentCyan else Border, RoundedCornerShape(10.dp))
            .clickableNoRipple(onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (active) AccentCyan else TextSecondary, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
