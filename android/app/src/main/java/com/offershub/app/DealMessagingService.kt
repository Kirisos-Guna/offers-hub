package com.offershub.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.offershub.app.data.AppSettings
import kotlinx.coroutines.runBlocking

/** Receives each new deal the moment the listener sees it (topic "deals"). */
class DealMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val id = data["id"] ?: return
        val url = data["url"] ?: return
        val discount = data["discount"]?.toIntOrNull()
        val title = data["title"].orEmpty()
        val text = data["text"].orEmpty()
        WidgetData.addLatest(this, data)
        // On screen, the live feed already shows it; otherwise respect the user's filters.
        if (AppVisibility.foreground || !AppSettings.get(this).wantsNotification(discount, title, text)) return

        val alert = DealAlert(
            id = id,
            title = title.ifEmpty { getString(R.string.notif_new_deal) },
            store = data["store"].orEmpty(),
            price = data["price"]?.toDoubleOrNull(),
            mrp = data["mrp"]?.toDoubleOrNull(),
            discount = discount,
            image = data["image"]?.takeIf { it.isNotBlank() },
            url = url,
        )
        // onMessageReceived runs on a background thread and may block briefly.
        runBlocking { DealNotifier.show(this@DealMessagingService, alert) }
    }

    override fun onNewToken(token: String) {
        // Delivery is by topic, so there is no token to register anywhere.
    }
}
