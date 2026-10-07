package com.sekhar.helium.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sekhar.helium.core.ui.theme.HeliumColors
import com.sekhar.helium.core.ui.theme.HeliumTextStyles

/** Primary action. Blossom fill, charcoal text — one per screen. */
@Composable
fun HeliumPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = HeliumColors.Blossom,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = HeliumColors.SurfaceHighest,
            disabledContentColor = HeliumColors.OnSurfaceFaint,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Lower-emphasis action. */
@Composable
fun HeliumSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HeliumColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HeliumColors.OnBackground),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Standard surface container. */
@Composable
fun HeliumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Deliberately uses Modifier.clickable rather than the clickable Card
    // overload so the component stays on stable Material 3 APIs.
    val colors = CardDefaults.cardColors(containerColor = HeliumColors.Surface)
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = shape,
        colors = colors,
    ) {
        content()
    }
}

/** Rounded status pill, used for analysis stages and AI state. */
@Composable
fun HeliumChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = HeliumColors.Blossom,
    background: Color = HeliumColors.SurfaceElevated,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                text = text,
                style = HeliumTextStyles.SectionLabel,
                color = HeliumColors.OnBackground,
            )
        }
    }
}

/** Empty-state block with an optional action. */
@Composable
fun HeliumEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            HeliumColors.Blossom.copy(alpha = 0.32f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = HeliumColors.OnBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = HeliumColors.OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** Section heading with a small uppercase label. */
@Composable
fun HeliumSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(vertical = 4.dp),
        style = HeliumTextStyles.SectionLabel,
        color = HeliumColors.OnSurfaceFaint,
    )
}

/** Thin progress bar in blossom, used for indexing and export progress. */
@Composable
fun HeliumProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(HeliumColors.SurfaceHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(HeliumColors.Blossom),
        )
    }
}

/** Consistent horizontal page padding. */
val HeliumPagePadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
