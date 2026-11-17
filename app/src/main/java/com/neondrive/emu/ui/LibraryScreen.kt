package com.neondrive.emu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.neondrive.emu.R
import com.neondrive.emu.data.Game
import com.neondrive.emu.data.GameSystem

@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpenSettings: () -> Unit, onPlay: (Game) -> Unit) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::addFolder)
    }

    Scaffold(
        containerColor = Neon.Bg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFolder.launch(null) },
                containerColor = Neon.Magenta, contentColor = Neon.Bg,
                modifier = Modifier.neonGlow(16.dp, Neon.Magenta),
                icon = { Icon(Icons.Default.CreateNewFolder, null) },
                text = { Text("إضافة مجلد", fontWeight = FontWeight.Bold) },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header(vm, onOpenSettings) }

                if (vm.recent.isNotEmpty() && vm.query.isBlank() && vm.systemFilter == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionTitle("آخر الألعاب")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(vm.recent, key = { "r" + it.uri }) { g ->
                                    GameCard(g, Modifier.width(120.dp)) { onPlay(g) }
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("كل الألعاب (${vm.games.size})") }
                }

                if (vm.games.isEmpty() && vm.scanning == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState { pickFolder.launch(null) } }
                }

                items(vm.visibleGames, key = { it.uri }) { g -> GameCard(g) { onPlay(g) } }
            }

            vm.scanning?.let { ScanBar(it, Modifier.align(Alignment.BottomCenter)) }
            vm.loadingGame?.let { LoadingOverlay(it) }
        }
    }

    vm.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { TextButton({ vm.error = null }) { Text("حسناً") } },
            text = { Text(msg) },
            containerColor = Neon.SurfaceHi,
        )
    }
}

@Composable
private fun Header(vm: LibraryViewModel, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.app_logo), null,
                Modifier.size(52.dp).neonGlow(14.dp).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NeonDrive", style = Neon.glowText)
                Text("محاكي سيكا الاحترافي", color = Neon.TextDim, fontSize = 13.sp)
                Text("تطوير: مهند سات لنك", color = Neon.Magenta, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = vm::rescanAll) { Icon(Icons.Default.Refresh, "إعادة الفحص", tint = Neon.Cyan) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Tune, "الإعدادات", tint = Neon.Magenta) }
        }

        vm.profile?.let { p ->
            NeonPanel(Modifier.fillMaxWidth(), radius = 14.dp) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, null, tint = Neon.Cyan)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("بروفايل الأداء: ${p.tier.label}", color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "${p.gpu} • ${p.cores} أنوية • %.1f GHz • %.0f GB".format(p.maxGhz, p.ramGb),
                            color = Neon.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("ابحث عن لعبة…") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Neon.Cyan) },
            singleLine = true, shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Neon.Cyan, unfocusedBorderColor = Neon.Purple.copy(alpha = 0.5f),
                focusedContainerColor = Neon.Surface, unfocusedContainerColor = Neon.Surface,
            ),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonChip("الكل", vm.systemFilter == null) { vm.systemFilter = null }
            GameSystem.entries.filter { s -> vm.games.any { it.system == s } }.forEach { s ->
                NeonChip(s.shortLabel, vm.systemFilter == s) { vm.systemFilter = s }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, color = Neon.Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun GameCard(game: Game, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        NeonPanel(Modifier.fillMaxWidth().aspectRatio(0.72f), radius = 14.dp) {
            SubcomposeAsyncImage(
                model = game.coverUrl,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { CoverFallback(game) },
                loading = { CoverFallback(game) },
            )
            Text(
                game.system.shortLabel,
                Modifier.align(Alignment.TopStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Neon.Bg.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Neon.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(game.title, color = Neon.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}

@Composable
private fun CoverFallback(game: Game) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Neon.Purple.copy(alpha = 0.55f), Neon.Bg, Neon.Cyan.copy(alpha = 0.35f)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            game.title, color = Neon.Text, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, modifier = Modifier.padding(10.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    NeonPanel(Modifier.fillMaxWidth().padding(top = 24.dp), glow = true) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.SportsEsports, null, tint = Neon.Magenta, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("المكتبة فارغة", color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "اختر المجلد اللي بيه ألعابك، والتطبيق يتعرف عليها ويجيب أغلفتها تلقائياً.\nيدعم: md, gen, bin, smd, sms, gg, sg, zip",
                color = Neon.TextDim, textAlign = TextAlign.Center, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) {
                Text("اختيار مجلد الألعاب", color = Neon.Bg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScanBar(current: String, modifier: Modifier) {
    NeonPanel(modifier.padding(16.dp).fillMaxWidth(), radius = 14.dp) {
        Column(Modifier.padding(12.dp)) {
            Text("جاري فحص الألعاب…", color = Neon.Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(current, color = Neon.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Neon.Magenta, trackColor = Neon.Surface)
        }
    }
}

@Composable
private fun LoadingOverlay(title: String) {
    Box(Modifier.fillMaxSize().background(Neon.Bg.copy(alpha = 0.85f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Neon.Cyan)
            Spacer(Modifier.height(12.dp))
            Text(title, color = Neon.Text)
        }
    }
}
