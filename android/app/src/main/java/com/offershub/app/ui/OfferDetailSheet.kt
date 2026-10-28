package com.offershub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offershub.app.data.Offer
import com.offershub.app.ui.theme.LiveGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailSheet(offer: Offer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(MaterialTheme.shapes.large)
            ) {
                OfferImage(offer, Modifier.fillMaxWidth().height(260.dp))
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    offer.discount?.let { DiscountBadge(it) }
                    if (offer.isNew()) Pill("NEW", LiveGreen, Color.White)
                }
            }

            Row(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (offer.store != "Other") StoreBadge(offer.store)
                offer.displayTag?.let {
                    Pill(it.uppercase(), MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(
                offer.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 10.dp),
            )
            PriceRow(offer, Modifier.padding(top = 8.dp), large = true)
            offer.savings?.let {
                Text(
                    "You save ${formatPrice(it)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = LiveGreen,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "${offer.source} · ${timeAgo(offer.postedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(18.dp))
            offer.links.forEachIndexed { index, link ->
                val label = when {
                    link.label.isNotBlank() -> "Buy · ${link.label}"
                    offer.store != "Other" -> "Buy on ${offer.store}"
                    else -> "Open deal"
                }
                val onClick = { openLink(context, link.url) }
                if (index == 0) {
                    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp).padding(vertical = 2.dp)) {
                        Icon(Icons.Filled.ShoppingCart, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(label)
                    }
                }
            }
            FilledTonalButton(
                onClick = { shareOffer(context, offer) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Share deal")
            }

            Text(
                "Original post",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        offer.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}
