package com.sarmidev.imuflux.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ─────────────────────────────────────────────────────────────────────────────
// Session setup card — shown on the main screen, above the record button.
// Lets the user declare which forklift and warehouse the session belongs to
// (required for a CSV to be meaningful downstream).
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SessionSetupCard(
    forkliftModel: String,
    warehouse: String,
    isRecording: Boolean,
    c: ImuFluxColors,
    onEditForklift: () -> Unit,
    onEditWarehouse: () -> Unit,
) {
    val isReady     = forkliftModel.isNotBlank() && warehouse.isNotBlank()
    val accentColor = when {
        isRecording -> c.accentGreen
        isReady     -> c.accentCyan
        else        -> c.accentAmber
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(14.dp)),
    ) {
        Row {
            Box(
                Modifier
                    .width(3.dp)
                    .height(92.dp)
                    .background(accentColor),
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "CONTEXTO DE SESIÓN",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        color = c.textSecondary,
                    )
                    if (isRecording) {
                        Text(
                            text = "· bloqueado",
                            fontSize = 8.sp,
                            letterSpacing = 1.sp,
                            color = c.textDim,
                        )
                    } else if (!isReady) {
                        Text(
                            text = "· requerido",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = c.accentAmber,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                SetupFieldRow(
                    label = "TORO",
                    value = forkliftModel,
                    placeholder = "SELECCIONAR",
                    enabled = !isRecording,
                    c = c,
                    onClick = onEditForklift,
                )
                Spacer(Modifier.height(6.dp))
                SetupFieldRow(
                    label = "ALMACÉN",
                    value = warehouse,
                    placeholder = "SELECCIONAR",
                    enabled = !isRecording,
                    c = c,
                    onClick = onEditWarehouse,
                )
            }
        }
    }
}

@Composable
private fun SetupFieldRow(
    label: String,
    value: String,
    placeholder: String,
    enabled: Boolean,
    c: ImuFluxColors,
    onClick: () -> Unit,
) {
    val hasValue   = value.isNotBlank()
    val valueColor = when {
        !enabled  -> c.textSecondary
        hasValue  -> c.textPrimary
        else      -> c.accentAmber
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.bgDeep)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp,
            color = c.textSecondary,
            modifier = Modifier.width(66.dp),
        )
        Text(
            text = if (hasValue) value else placeholder,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (hasValue) 0.5.sp else 1.5.sp,
            color = valueColor,
            fontFamily = if (hasValue) FontFamily.Default else FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (enabled) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "✎",
                fontSize = 13.sp,
                color = c.textSecondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup field dialog — modal to edit forklift or warehouse, with recent chips
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SetupFieldDialog(
    title: String,
    label: String,
    currentValue: String,
    recents: List<String>,
    hint: String,
    c: ImuFluxColors,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.bgSurface)
                .border(1.dp, c.bgCardBorder, RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(3.dp, 14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.accentCyan),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color = c.textPrimary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = c.textSecondary,
                    modifier = Modifier.padding(start = 13.dp),
                )
                Spacer(Modifier.height(18.dp))

                // Label
                Text(
                    text = label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                // TextField styled in the instrument panel aesthetic
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = c.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    cursorBrush = SolidColor(c.accentCyan),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.bgDeep)
                        .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (text.isEmpty()) {
                                Text(
                                    text = "Escribe aquí…",
                                    fontSize = 15.sp,
                                    color = c.textDim,
                                )
                            }
                            inner()
                        }
                    },
                )

                if (recents.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "USADOS RECIENTEMENTE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        modifier = Modifier.heightIn(min = 34.dp),
                    ) {
                        items(recents) { item ->
                            RecentChip(
                                label = item,
                                selected = item.equals(text.trim(), ignoreCase = true),
                                c = c,
                                onClick = { text = item },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogActionButton(
                        label = "Cancelar",
                        filled = false,
                        c = c,
                        onClick = onDismiss,
                    )
                    Spacer(Modifier.width(10.dp))
                    DialogActionButton(
                        label = "Guardar",
                        filled = true,
                        enabled = text.trim().isNotEmpty(),
                        c = c,
                        onClick = { onConfirm(text.trim()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentChip(
    label: String,
    selected: Boolean,
    c: ImuFluxColors,
    onClick: () -> Unit,
) {
    val bg     = if (selected) c.accentCyan.copy(alpha = 0.14f) else c.bgCard
    val border = if (selected) c.accentCyan.copy(alpha = 0.55f) else c.bgCardBorder
    val text   = if (selected) c.accentCyan else c.textSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog action buttons (used by SetupFieldDialog, onboarding, permission)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DialogActionButton(
    label: String,
    filled: Boolean,
    c: ImuFluxColors,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> if (filled) c.accentCyan.copy(alpha = 0.25f) else Color.Transparent
        filled   -> c.accentCyan
        else     -> Color.Transparent
    }
    val border = when {
        !enabled -> c.bgCardBorder
        filled   -> c.accentCyan
        else     -> c.bgCardBorder
    }
    val textColor = when {
        !enabled -> if (filled) Color.White.copy(alpha = 0.5f) else c.textDim
        filled   -> Color.White
        else     -> c.textSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            color = textColor,
        )
    }
}
