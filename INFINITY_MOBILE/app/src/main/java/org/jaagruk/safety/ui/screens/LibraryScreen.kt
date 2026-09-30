package org.jaagruk.safety.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jaagruk.safety.data.library.EntryType
import org.jaagruk.safety.data.library.LibraryEntry
import org.jaagruk.safety.ui.components.GradientBackground
import org.jaagruk.safety.ui.theme.*
import org.jaagruk.safety.viewmodel.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LibraryScreen(
    isDarkTheme   : Boolean,
    bottomPadding : Dp,
    onOpenEntry   : (Long) -> Unit,
    vm            : LibraryViewModel = viewModel()
) {
    val entries      by vm.entries.collectAsState()
    val selectedType by vm.selectedType.collectAsState()
    val searchQuery  by vm.searchQuery.collectAsState()
    val dark = isDarkTheme

    GradientBackground(darkTheme = dark, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            Spacer(Modifier.height(24.dp))

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Knowledge Vault",
                        style = MaterialTheme.typography.headlineLarge,
                        color = if (dark) TextPrimary else TextPrimaryLight,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Your saved AI-generated content",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dark) TextSecondary else TextSecondaryLight
                    )
                }
                AnimatedVisibility(visible = entries.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Blue50)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "${entries.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Blue500,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Search bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (dark) DarkSurface else LightSurface)
                    .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Search, null,
                    tint = if (dark) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.size(16.dp)
                )
                BasicTextField(
                    value         = searchQuery,
                    onValueChange = { vm.setSearch(it) },
                    modifier      = Modifier.weight(1f),
                    textStyle     = MaterialTheme.typography.bodyMedium.copy(
                        color = if (dark) TextPrimary else TextPrimaryLight
                    ),
                    cursorBrush   = SolidColor(Blue500),
                    singleLine    = true,
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search saved content…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (dark) TextSecondary else TextSecondaryLight
                            )
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Default.Close, "Clear",
                        tint = if (dark) TextSecondary else TextSecondaryLight,
                        modifier = Modifier.size(16.dp).clickable { vm.setSearch("") }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Filter chips ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                VaultChip("All", selectedType == null, Blue500, dark) { vm.setFilter(null) }
                EntryType.entries.forEach { type ->
                    VaultChip(
                        label    = type.label,
                        selected = selectedType == type,
                        color    = typeColor(type),
                        dark     = dark,
                        onClick  = { vm.setFilter(if (selectedType == type) null else type) }
                    )
                }
            }

            // ── Stat pills ────────────────────────────────────────────────────
            if (selectedType == null && searchQuery.isBlank() && entries.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    EntryType.entries.forEach { type ->
                        val c = entries.count { it.type == type }
                        if (c > 0) VaultStatPill(type.label, c, typeColor(type), dark)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Content ───────────────────────────────────────────────────────
            if (entries.isEmpty()) {
                VaultEmptyState(dark, searchQuery.isNotBlank())
            } else {
                LazyColumn(
                    contentPadding      = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        VaultEntryCard(
                            entry    = entry,
                            dark     = dark,
                            onClick  = { onOpenEntry(entry.id) },
                            onDelete = { vm.delete(entry) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

// ── Filter chip ───────────────────────────────────────────────────────────────

@Composable
private fun VaultChip(label: String, selected: Boolean, color: Color, dark: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) color.copy(0.10f)
                else if (dark) DarkSurface else LightSurface
            )
            .border(
                1.dp,
                if (selected) color.copy(0.30f) else if (dark) DarkBorder else LightBorder,
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style      = MaterialTheme.typography.labelMedium,
            color      = if (selected) color else if (dark) TextSecondary else TextSecondaryLight,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ── Stat pill ─────────────────────────────────────────────────────────────────

@Composable
private fun VaultStatPill(label: String, count: Int, color: Color, dark: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (dark) DarkSurface else LightSurface)
            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(5.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (dark) TextSecondary else TextSecondaryLight)
        Text("$count", style = MaterialTheme.typography.labelSmall,
            color = color, fontWeight = FontWeight.Bold)
    }
}

// ── Entry card ────────────────────────────────────────────────────────────────

@Composable
private fun VaultEntryCard(
    entry: LibraryEntry, dark: Boolean, onClick: () -> Unit, onDelete: () -> Unit
) {
    val color = typeColor(entry.type)
    val fmt   = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) DarkSurface else LightSurface)
            .border(1.dp, if (dark) DarkBorder else LightBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        // Top accent strip
        Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(color.copy(0.35f)))

        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(color.copy(0.09f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(typeIcon(entry.type), null, tint = color, modifier = Modifier.size(17.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = if (dark) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    entry.content.take(100).replace("\n", " "),
                    style      = MaterialTheme.typography.bodySmall,
                    color      = if (dark) TextSecondary else TextSecondaryLight,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(color.copy(0.09f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            entry.type.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        fmt.format(Date(entry.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dark) TextDisabled else TextTertiary
                    )
                }
            }

            if (!confirmDelete) {
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline, "Delete",
                        tint = if (dark) TextDisabled else TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = { confirmDelete = false }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, "Cancel",
                            tint = if (dark) TextSecondary else TextSecondaryLight,
                            modifier = Modifier.size(14.dp))
                    }
                    IconButton(
                        onClick  = { onDelete(); confirmDelete = false },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.Delete, "Confirm",
                            tint = ErrorRed, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun VaultEmptyState(dark: Boolean, isSearching: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Blue50, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isSearching) Icons.Default.SearchOff else Icons.Default.AutoStories,
                null, tint = Blue500, modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (isSearching) "No results found" else "Vault is empty",
            style      = MaterialTheme.typography.titleMedium,
            color      = if (dark) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isSearching) "Try different keywords"
            else "Generate a summary, OCR scan, or quiz —\nit will be saved here automatically.",
            style       = MaterialTheme.typography.bodyMedium,
            color       = if (dark) TextSecondary else TextSecondaryLight,
            textAlign   = TextAlign.Center,
            lineHeight  = 22.sp
        )
    }
}

// ── Type helpers (public — used by ChatScreen save action) ────────────────────

fun typeColor(type: EntryType): Color = when (type) {
    EntryType.PDF_SUMMARY -> Moss500
    EntryType.OCR         -> Blue500
    EntryType.SCREENSHOT  -> Indigo500
    EntryType.QUIZ        -> Moss500
    EntryType.NOTE        -> Amber400
}

fun typeIcon(type: EntryType): ImageVector = when (type) {
    EntryType.PDF_SUMMARY -> Icons.Default.PictureAsPdf
    EntryType.OCR         -> Icons.Default.DocumentScanner
    EntryType.SCREENSHOT  -> Icons.Default.ScreenSearchDesktop
    EntryType.QUIZ        -> Icons.Default.Quiz
    EntryType.NOTE        -> Icons.Default.EditNote
}
