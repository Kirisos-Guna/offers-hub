package com.offershub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.offershub.app.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.offershub.app.data.Offer
import com.offershub.app.ui.theme.DealRed
import com.offershub.app.ui.theme.LiveGreen
import com.offershub.app.ui.theme.storeColor

@Composable
fun OfferCard(
    offer: Offer,
    saved: Boolean,
    onToggleSave: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        // Deals the channel marked as over stay visible but step back.
        modifier = modifier.alpha(if (offer.expired) 0.55f else 1f),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(6.dp)
                    .clip(MaterialTheme.shapes.medium)
            ) {
                OfferImage(offer, Modifier.fillMaxSize())
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    when {
                        offer.expired -> Pill(stringResource(R.string.expired_badge), Color(0xFF5F5F5F), Color.White)
                        offer.discount != null -> DiscountBadge(offer.discount)
                        else -> Spacer(Modifier)
                    }
                    if (offer.isNew()) Pill(stringResource(R.string.new_badge), LiveGreen, Color.White)
                }
                if (offer.image != null && offer.store != "Other") {
                    StoreBadge(offer.store, Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
                SaveButton(saved, onToggleSave, Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
                Text(
                    offer.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PriceRow(offer, Modifier.padding(top = 6.dp))
                Text(
                    listOfNotNull(offer.displayTag, timeAgo(offer.postedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** ♥ toggle on a white circle, readable on any product photo. */
@Composable
fun SaveButton(saved: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.9f)),
    ) {
        Icon(
            if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = stringResource(if (saved) R.string.cd_unsave else R.string.cd_save),
            tint = if (saved) DealRed else Color(0xFF53433F),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Small card for the "Hot right now" row. */
@Composable
fun HotCard(offer: Offer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier.width(136.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp).clip(MaterialTheme.shapes.small)) {
                OfferImage(offer, Modifier.fillMaxSize())
                offer.discount?.let { DiscountBadge(it, Modifier.padding(6.dp)) }
            }
            Text(
                offer.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                offer.price?.let { formatPrice(it) } ?: stringResource(R.string.see_deal),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/** Product photos are on white, so they sit on a white panel in both themes. */
@Composable
fun OfferImage(offer: Offer, modifier: Modifier = Modifier) {
    if (offer.image == null) {
        StorePlaceholder(offer.store, modifier)
    } else {
        SubcomposeAsyncImage(
            model = offer.image,
            contentDescription = offer.title,
            contentScale = ContentScale.Fit,
            modifier = modifier.background(Color.White),
            loading = { Box(Modifier.fillMaxSize().background(Color.White)) },
            error = { StorePlaceholder(offer.store, Modifier.fillMaxSize()) },
        )
    }
}

@Composable
private fun StorePlaceholder(store: String, modifier: Modifier) {
    val color = storeColor(store)
    Box(
        modifier.background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (store == "Other") "Deal" else store,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = content,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun DiscountBadge(discount: Int, modifier: Modifier = Modifier) =
    Pill(stringResource(R.string.percent_off_caps, discount), DealRed, Color.White, modifier)

@Composable
fun StoreBadge(store: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.92f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(storeColor(store)))
        Text(
            store,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF231917),
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/** Price and crossed-out MRP; the MRP moves to its own line when both don't fit (large fonts). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceRow(offer: Offer, modifier: Modifier = Modifier, large: Boolean = false) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        if (offer.price != null) {
            Text(
                formatPrice(offer.price),
                maxLines = 1,
                softWrap = false,
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                stringResource(R.string.see_deal),
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (offer.mrp != null && offer.mrp != offer.price) {
            Text(
                formatPrice(offer.mrp),
                maxLines = 1,
                softWrap = false,
                style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(bottom = if (large) 4.dp else 2.dp),
            )
        }
    }
}
