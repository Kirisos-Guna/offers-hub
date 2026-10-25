package com.offershub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
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
import com.offershub.app.ui.theme.storeColor

@Composable
fun OfferCard(offer: Offer, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            OfferImage(offer, imageUrl, Modifier.fillMaxSize())
            offer.discount?.let {
                DiscountBadge(it, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            // The placeholder already shows the store name; "Other" says nothing worth a badge.
            if (imageUrl != null && offer.store != "Other") {
                StoreBadge(offer.store, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                offer.title,
                style = MaterialTheme.typography.bodyMedium,
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

@Composable
fun OfferImage(offer: Offer, imageUrl: String?, modifier: Modifier = Modifier) {
    if (imageUrl == null) {
        StorePlaceholder(offer.store, modifier)
    } else {
        SubcomposeAsyncImage(
            model = imageUrl,
            contentDescription = offer.title,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            error = { StorePlaceholder(offer.store, Modifier.fillMaxSize()) },
        )
    }
}

@Composable
private fun StorePlaceholder(store: String, modifier: Modifier) {
    val color = storeColor(store)
    Box(
        modifier.background(Brush.linearGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (store == "Other") "Deal" else store,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
fun DiscountBadge(discount: Int, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            "$discount% OFF",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun StoreBadge(store: String, modifier: Modifier = Modifier) {
    Text(
        store,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(storeColor(store))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun PriceRow(offer: Offer, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        if (offer.price != null) {
            Text(
                formatPrice(offer.price),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "See deal",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (offer.mrp != null && offer.mrp != offer.price) {
            Text(
                formatPrice(offer.mrp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}
