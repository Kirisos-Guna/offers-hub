package com.offershub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun OfferCard(offer: Offer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier,
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
                    offer.discount?.let { DiscountBadge(it) } ?: Spacer(Modifier)
                    if (offer.isNew()) Pill("NEW", LiveGreen, Color.White)
                }
                if (offer.image != null && offer.store != "Other") {
                    StoreBadge(offer.store, Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
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
    Pill("$discount% OFF", DealRed, Color.White, modifier)

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

@Composable
fun PriceRow(offer: Offer, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        if (offer.price != null) {
            Text(
                formatPrice(offer.price),
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "See deal",
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (offer.mrp != null && offer.mrp != offer.price) {
            Text(
                formatPrice(offer.mrp),
                style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(bottom = if (large) 4.dp else 2.dp),
            )
        }
    }
}
