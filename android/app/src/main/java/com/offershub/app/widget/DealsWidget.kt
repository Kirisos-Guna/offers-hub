package com.offershub.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.offershub.app.DealNotifier
import com.offershub.app.MainActivity
import com.offershub.app.R
import com.offershub.app.WidgetData
import com.offershub.app.data.Offer
import com.offershub.app.ui.formatPrice

/** Home-screen widget: the latest deals; tapping one opens it in the app. */
class DealsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deals = WidgetData.load(context)
        provideContent { GlanceTheme { Content(context, deals) } }
    }

    @Composable
    private fun Content(context: Context, deals: List<Offer>) {
        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp).padding(12.dp)
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.app_name),
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.primary),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            if (deals.isEmpty()) {
                Text(
                    context.getString(R.string.widget_empty),
                    style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.clickable(openApp),
                )
            } else {
                LazyColumn {
                    items(deals, itemId = { it.id.hashCode().toLong() }) { deal -> DealRow(context, deal) }
                }
            }
        }
    }

    @Composable
    private fun DealRow(context: Context, deal: Offer) {
        val open = actionStartActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(DealNotifier.EXTRA_OFFER_ID, deal.id)
                .setAction("open_deal_${deal.id}") // a distinct intent per row
        )
        Column(
            GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(open)
        ) {
            Text(
                deal.title,
                maxLines = 1,
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
            )
            val details = listOfNotNull(
                deal.price?.let { formatPrice(it) },
                deal.discount?.let { context.getString(R.string.percent_off, it) },
                deal.store.takeIf { it != "Other" },
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    details,
                    maxLines = 1,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
                )
            }
        }
    }
}

class DealsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DealsWidget()
}
