package com.offershub.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.offershub.app.R
import com.offershub.app.data.Offer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val W = 1080
private const val H = 1350

/** Shares a deal as a picture card (good for WhatsApp) with its link as text. */
suspend fun shareDealImage(context: Context, offer: Offer) {
    val product = offer.image?.let { url ->
        withTimeoutOrNull(8_000) {
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(900).build()
            (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
        }
    }
    val file = withContext(Dispatchers.Default) {
        val card = drawCard(context, offer, product)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, "deal.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val price = offer.price?.let { " · ${formatPrice(it)}" } ?: ""
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, "${offer.title}$price\n${offer.url}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_deal)))
}

private fun drawCard(context: Context, offer: Offer, product: Bitmap?): Bitmap {
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.parseColor("#FFF8F6"))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Product picture on a white panel.
    val panel = RectF(60f, 60f, W - 60f, 750f)
    paint.color = Color.WHITE
    c.drawRoundRect(panel, 48f, 48f, paint)
    if (product != null) {
        val scale = minOf((panel.width() - 80) / product.width, (panel.height() - 80) / product.height)
        val w = product.width * scale
        val h = product.height * scale
        val left = panel.centerX() - w / 2
        val top = panel.centerY() - h / 2
        c.drawBitmap(product, Rect(0, 0, product.width, product.height), RectF(left, top, left + w, top + h), paint)
    } else {
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B33B15"); textSize = 96f; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText(if (offer.store == "Other") "Deal" else offer.store, panel.centerX(), panel.centerY() + 32, text)
    }
    offer.discount?.let { pill(c, context.getString(R.string.percent_off_caps, it), 100f, 100f, Color.parseColor("#D32F2F")) }

    var y = 790f
    if (offer.store != "Other") {
        val store = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#77574C"); textSize = 40f; typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(offer.store.uppercase(), 70f, y + 40, store)
        y += 70
    }

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#231917"); textSize = 54f; typeface = Typeface.DEFAULT_BOLD
    }
    val title = StaticLayout.Builder.obtain(offer.title, 0, offer.title.length, titlePaint, W - 140)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
    c.save(); c.translate(70f, y); title.draw(c); c.restore()
    y += title.height + 30

    val pricePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B33B15"); textSize = 104f; typeface = Typeface.DEFAULT_BOLD
    }
    val priceText = offer.price?.let { formatPrice(it) } ?: context.getString(R.string.see_deal)
    c.drawText(priceText, 70f, y + 100, pricePaint)
    offer.mrp?.takeIf { offer.price != null && it > offer.price }?.let { mrp ->
        val mrpPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#85736E"); textSize = 48f; isStrikeThruText = true
        }
        c.drawText(formatPrice(mrp), 70f + pricePaint.measureText(priceText) + 30, y + 98, mrpPaint)
    }
    offer.savings?.let {
        val save = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E9D57"); textSize = 44f; typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(context.getString(R.string.you_save, formatPrice(it)), 70f, y + 165, save)
    }

    // Footer band.
    paint.color = Color.parseColor("#E8590C")
    c.drawRect(0f, H - 120f, W.toFloat(), H.toFloat(), paint)
    val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 42f; typeface = Typeface.DEFAULT_BOLD
    }
    c.drawText(context.getString(R.string.share_footer), 70f, H - 45f, footer)
    return bmp
}

private fun pill(c: Canvas, text: String, x: Float, y: Float, color: Int) {
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE; textSize = 44f; typeface = Typeface.DEFAULT_BOLD
    }
    val w = textPaint.measureText(text) + 56
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    c.drawRoundRect(RectF(x, y, x + w, y + 76), 38f, 38f, bg)
    c.drawText(text, x + 28, y + 54, textPaint)
}
