package com.offershub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.offershub.app.R
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OfferDetailSheet(offer: Offer, saved: Boolean, onToggleSave: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharingImage by remember { mutableStateOf(false) }
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
                    if (offer.expired) Pill(stringResource(R.string.expired_badge), Color(0xFF5F5F5F), Color.White)
                    else offer.discount?.let { DiscountBadge(it) }
                    if (offer.isNew()) Pill(stringResource(R.string.new_badge), LiveGreen, Color.White)
                }
                SaveButton(saved, onToggleSave, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }

            if (offer.expired) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.deal_ended),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            FlowRow(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (offer.store != "Other") StoreBadge(offer.store)
                if (offer.category != "Other") {
                    Pill("${categoryEmoji(offer.category)} ${categoryName(offer.category)}",
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                }
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
                    stringResource(R.string.you_save, formatPrice(it)),
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
                    link.label.isNotBlank() -> stringResource(R.string.buy_label, link.label)
                    offer.store != "Other" -> stringResource(R.string.buy_on, offer.store)
                    else -> stringResource(R.string.open_deal)
                }
                val onClick = { openLink(context, link.url) }
                if (index == 0) {
                    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp)) {
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
            // One above the other: side by side they cut off at large font sizes.
            FilledTonalButton(
                onClick = {
                    sharingImage = true
                    scope.launch {
                        try {
                            shareDealImage(context, offer)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            sharingImage = false
                        }
                    }
                },
                enabled = !sharingImage,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.share_image))
            }
            OutlinedButton(onClick = { shareOffer(context, offer) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.share_deal))
            }

            Text(
                stringResource(R.string.original_post),
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
