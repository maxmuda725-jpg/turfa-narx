package uz.turfa.narx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Polka rasmi (bir rasmda ko'p tovar): rasmdagi buyumlarni alohida qirqib beradi.
 * Avval MediaPipe ObjectDetector (efficientdet_lite0, telefonning o'zida, bepul) buyumlarni topadi;
 * kam topsa — rasm bir-birini qoplaydigan katakchalarga bo'linadi. Har bir qirqim keyin Visual bilan qidiriladi.
 */
object Shelf {
    private const val MODEL = "detector.tflite"
    @Volatile private var det: ObjectDetector? = null

    private fun detector(ctx: Context): ObjectDetector? = det ?: try {
        ObjectDetector.createFromOptions(ctx.applicationContext,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setMaxResults(15).setScoreThreshold(0.15f).build()).also { det = it }
    } catch (_: Throwable) { null }

    /** -> buyumlar qirqimlari (chapdan o'ngga, tepadan pastga tartibda). */
    fun crops(ctx: Context, bmp: Bitmap): List<Bitmap> {
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val boxes = ArrayList<RectF>()
        try {
            detector(ctx)?.detect(BitmapImageBuilder(bmp).build())?.detections()?.forEach { d ->
                val b = d.boundingBox()
                val area = b.width() * b.height() / (w * h)
                if (area in 0.012f..0.75f) boxes += b
            }
        } catch (_: Throwable) {}
        // bir-birining ustidagi qutilarni birlashtirmaslik uchun: juda o'xshashlarini tashlash
        val uniq = ArrayList<RectF>()
        for (b in boxes.sortedByDescending { it.width() * it.height() }) if (uniq.none { iou(it, b) > 0.6f }) uniq += b
        if (uniq.size < 2) {
            // Detektor tanimadi — 2 qator x 3 ustun katakchalar (har biri qo'shnisini biroz qoplaydi)
            uniq.clear()
            val rows = if (h > w * 1.2f) 3 else 2
            val cols = if (h > w * 1.2f) 2 else 3
            val cw = w / cols; val ch = h / rows
            for (r in 0 until rows) for (c in 0 until cols)
                uniq += RectF(c * cw - cw * 0.12f, r * ch - ch * 0.12f, (c + 1) * cw + cw * 0.12f, (r + 1) * ch + ch * 0.12f)
        }
        return uniq.sortedWith(compareBy({ ((it.centerY() / h) * 3).toInt() }, { it.centerX() })).map { b ->
            // qirqimni biroz kengaytirib (atrofi ham ko'rinsin), rasm chegarasida kesish
            val px = b.width() * 0.08f; val py = b.height() * 0.08f
            val l = (b.left - px).coerceIn(0f, w - 2); val t = (b.top - py).coerceIn(0f, h - 2)
            val r = (b.right + px).coerceIn(l + 2, w); val bt = (b.bottom + py).coerceIn(t + 2, h)
            Bitmap.createBitmap(bmp, l.toInt(), t.toInt(), (r - l).toInt().coerceAtLeast(1), (bt - t).toInt().coerceAtLeast(1))
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val i = RectF(); if (!i.setIntersect(a, b)) return 0f
        val ia = i.width() * i.height()
        return ia / (a.width() * a.height() + b.width() * b.height() - ia)
    }
}
