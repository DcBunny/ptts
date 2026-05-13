package com.example.ptts.features.jump_session.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ptts.R
import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.jump_session.presentation.formatDuration
import com.example.ptts.ui.theme.BrandOrange
import com.example.ptts.ui.theme.CardSurface
import com.example.ptts.ui.theme.CreamSurface
import com.example.ptts.ui.theme.PttsTheme
import com.example.ptts.ui.theme.TextMuted
import com.example.ptts.ui.theme.TextPrimary
import com.example.ptts.ui.theme.TextSecondary
import com.example.ptts.ui.theme.TonalButtonSurface
import com.example.ptts.ui.theme.WarmBackground
import com.example.ptts.ui.theme.WarmBackgroundDeep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpSessionScreen(
    onOpenParentCamera: (durationSeconds: Int) -> Unit,
    bestRecord: Int,
    modifier: Modifier = Modifier,
) {
    var durationSeconds by remember {
        mutableIntStateOf(JumpSessionDefaults.InitialDurationSeconds)
    }
    val decreaseDescription = stringResource(R.string.duration_decrease)
    val increaseDescription = stringResource(R.string.duration_increase)
    val durationDescription = stringResource(R.string.duration_display)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = WarmBackground,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WarmBackground,
                    scrolledContainerColor = WarmBackground,
                ),
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(WarmBackground, WarmBackgroundDeep),
                    ),
                )
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BestRecordCard(bestRecord = bestRecord)
            SectionCard {
                Column {
                    Text(
                        text = stringResource(R.string.duration),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DurationButton(
                            icon = Icons.Rounded.Remove,
                            contentDescription = decreaseDescription,
                            enabled = durationSeconds > JumpSessionDefaults.MinDurationSeconds,
                            onClick = {
                                durationSeconds = maxOf(
                                    JumpSessionDefaults.MinDurationSeconds,
                                    durationSeconds - JumpSessionDefaults.DurationStepSeconds,
                                )
                            },
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = formatDuration(durationSeconds),
                                modifier = Modifier.semantics {
                                    contentDescription = durationDescription
                                },
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.parent_photo_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                textAlign = TextAlign.Center,
                            )
                        }
                        DurationButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = increaseDescription,
                            enabled = true,
                            onClick = {
                                durationSeconds += JumpSessionDefaults.DurationStepSeconds
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(28.dp))
                    TextButton(
                        onClick = {
                            onOpenParentCamera(durationSeconds)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = BrandOrange,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.parent_photo),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BestRecordCard(bestRecord: Int) {
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.best_record),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = bestRecord.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (bestRecord == 0) {
                        stringResource(R.string.best_record_empty)
                    } else {
                        stringResource(R.string.best_record_subtitle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
            Spacer(modifier = Modifier.size(16.dp))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color(0xFFFFF2CF), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.WorkspacePremium,
                    contentDescription = null,
                    tint = Color(0xFFE2A11B),
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x14000000),
                spotColor = Color(0x14000000),
            ),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(28.dp),
    ) {
        Box(modifier = Modifier.padding(22.dp)) {
            content()
        }
    }
}

@Composable
private fun DurationButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .semantics {
                this.contentDescription = contentDescription
            },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = TonalButtonSurface,
            disabledContainerColor = TonalButtonSurface,
            contentColor = Color(0xFF6D665E),
            disabledContentColor = Color(0x666D665E),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JumpSessionScreenPreview() {
    PttsTheme {
        JumpSessionScreen(
            onOpenParentCamera = { _ -> },
            bestRecord = 42,
        )
    }
}
