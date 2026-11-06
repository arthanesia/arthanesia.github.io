package id.arthanesia.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.PageRange
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.Locale

internal object BookkeepingReport {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f
    private const val ROW_HEIGHT = 23f

    fun createPdf(entries: List<MoneyEntry>, business: String, owner: String): ByteArray {
        val ordered = entries.sortedByDescending { it.id }
        val chunks = if (ordered.isEmpty()) listOf(emptyList()) else ordered.chunked(20)
        val income = ordered.filter { it.income }.sumOf { it.amount }
        val expense = ordered.filterNot { it.income }.sumOf { it.amount }
        val document = PdfDocument()
        val navy = Color.rgb(34, 36, 69)
        val purple = Color.rgb(83, 77, 215)
        val grey = Color.rgb(111, 118, 137)
        val pale = Color.rgb(246, 247, 252)
        val green = Color.rgb(23, 137, 103)
        val red = Color.rgb(194, 76, 93)

        chunks.forEachIndexed { pageIndex, rows ->
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
            )
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val titlePaint = paint(22f, navy, true)
            canvas.drawText("ARTHANESIA", MARGIN, 58f, paint(11f, purple, true))
            canvas.drawText("Laporan Pembukuan", MARGIN, 91f, titlePaint)
            canvas.drawText(business.take(48), MARGIN, 116f, paint(11f, navy, true))
            canvas.drawText("Disusun untuk $owner", MARGIN, 135f, paint(9f, grey, false))
            val right = "Dicetak: " + java.time.LocalDate.now().toString()
            canvas.drawText(right, PAGE_WIDTH - MARGIN, 58f, paint(9f, grey, false, Paint.Align.RIGHT))
            canvas.drawText("Periode: seluruh transaksi tersimpan", PAGE_WIDTH - MARGIN, 76f, paint(9f, grey, false, Paint.Align.RIGHT))
            canvas.drawLine(MARGIN, 151f, PAGE_WIDTH - MARGIN, 151f, paint(1f, Color.rgb(226, 229, 238), false))

            var tableY = 180f
            if (pageIndex == 0) {
                val cardTop = 169f
                val cardBottom = 245f
                val cardGap = 10f
                val cardWidth = (PAGE_WIDTH - 2 * MARGIN - 2 * cardGap) / 3f
                val cards = listOf(
                    Triple("PEMASUKAN", income, green),
                    Triple("PENGELUARAN", expense, red),
                    Triple("SALDO BERSIH", income - expense, navy)
                )
                cards.forEachIndexed { index, item ->
                    val left = MARGIN + index * (cardWidth + cardGap)
                    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pale }
                    canvas.drawRoundRect(left, cardTop, left + cardWidth, cardBottom, 9f, 9f, box)
                    canvas.drawText(item.first, left + 12f, cardTop + 23f, paint(8f, grey, true))
                    canvas.drawText(currency(item.second), left + 12f, cardTop + 51f, paint(12f, item.third, true))
                }
                canvas.drawText("RINCIAN TRANSAKSI", MARGIN, 278f, paint(10f, navy, true))
                tableY = 298f
            } else {
                canvas.drawText("RINCIAN TRANSAKSI · LANJUTAN", MARGIN, 180f, paint(10f, navy, true))
                tableY = 199f
            }

            val tableRight = PAGE_WIDTH - MARGIN
            canvas.drawRect(MARGIN, tableY - 17f, tableRight, tableY + 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy })
            canvas.drawText("TANGGAL", MARGIN + 8f, tableY, paint(7f, Color.WHITE, true))
            canvas.drawText("KETERANGAN", 122f, tableY, paint(7f, Color.WHITE, true))
            canvas.drawText("KATEGORI", 312f, tableY, paint(7f, Color.WHITE, true))
            canvas.drawText("ARUS", 405f, tableY, paint(7f, Color.WHITE, true))
            canvas.drawText("NOMINAL", tableRight - 8f, tableY, paint(7f, Color.WHITE, true, Paint.Align.RIGHT))

            rows.forEachIndexed { rowIndex, entry ->
                val top = tableY + 12f + rowIndex * ROW_HEIGHT
                val baseline = top + 15f
                if (rowIndex % 2 == 1) {
                    canvas.drawRect(MARGIN, top, tableRight, top + ROW_HEIGHT, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pale })
                }
                canvas.drawText(entry.date.take(13), MARGIN + 8f, baseline, paint(7.2f, grey, false))
                canvas.drawText(entry.title.take(29), 122f, baseline, paint(7.5f, navy, true))
                canvas.drawText(entry.category.take(15), 312f, baseline, paint(7.2f, grey, false))
                val direction = if (entry.income) "Masuk" else "Keluar"
                val directionColor = if (entry.income) green else red
                canvas.drawText(direction, 405f, baseline, paint(7.2f, directionColor, true))
                val amount = (if (entry.income) "+" else "−") + currency(entry.amount)
                canvas.drawText(amount, tableRight - 8f, baseline, paint(7.5f, directionColor, true, Paint.Align.RIGHT))
                canvas.drawLine(MARGIN, top + ROW_HEIGHT, tableRight, top + ROW_HEIGHT, paint(.5f, Color.rgb(233, 235, 241), false))
            }

            val footerY = 793f
            canvas.drawLine(MARGIN, footerY - 17f, tableRight, footerY - 17f, paint(.6f, Color.rgb(226, 229, 238), false))
            canvas.drawText("Arthanesia · Dokumen pembukuan internal", MARGIN, footerY, paint(8f, grey, false))
            canvas.drawText("Halaman ${pageIndex + 1} dari ${chunks.size}", tableRight, footerY, paint(8f, grey, false, Paint.Align.RIGHT))
            document.finishPage(page)
        }

        val output = ByteArrayOutputStream()
        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }

    fun createCsv(entries: List<MoneyEntry>): ByteArray {
        fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        val lines = buildList {
            add(listOf("Tanggal", "Deskripsi", "Kategori", "Jenis", "Mata Uang", "Nominal").joinToString(";") { cell(it) })
            entries.sortedByDescending { it.id }.forEach {
                add(listOf(it.date, it.title, it.category, if (it.income) "Pemasukan" else "Pengeluaran", "IDR", it.amount.toString()).joinToString(";") { value -> cell(value) })
            }
        }
        return ("\uFEFF" + lines.joinToString("\r\n")).toByteArray(Charsets.UTF_8)
    }

    fun print(context: Context, entries: List<MoneyEntry>, business: String, owner: String) {
        val bytes = createPdf(entries, business, owner)
        val pageCount = if (entries.isEmpty()) 1 else (entries.size + 19) / 20
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("Arthanesia-Pembukuan.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal,
                callback: WriteResultCallback
            ) {
                if (cancellationSignal.isCanceled) {
                    callback.onWriteCancelled()
                    return
                }
                try {
                    FileOutputStream(destination.fileDescriptor).use { it.write(bytes) }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (error: Exception) {
                    callback.onWriteFailed(error.message ?: "Gagal membuat dokumen PDF")
                } finally {
                    runCatching { destination.close() }
                }
            }
        }
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("arthanesia", "Arthanesia", 300, 300))
            .setMinMargins(PrintAttributes.Margins(24, 24, 24, 24))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        manager.print("Arthanesia - Pembukuan", adapter, attributes)
    }

    private fun currency(amount: Long): String =
        NumberFormat.getCurrencyInstance(Locale("id", "ID")).format(amount).replace(",00", "")

    private fun paint(size: Float, color: Int, bold: Boolean, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = align
        }
}
