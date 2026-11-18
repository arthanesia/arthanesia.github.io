package id.arthanesia.app

import java.util.Locale

/** Structured suggestions produced from voice input and receipt OCR. */
internal data class ParsedTransaction(
    val income: Boolean,
    val amount: Long?,
    val title: String,
    val category: String
)

/**
 * Small, deterministic on-device parser. It never saves a transaction by itself;
 * the user reviews the suggested fields in the transaction form before saving.
 */
internal object TransactionIntelligence {
    private val locale = Locale("id", "ID")

    private val receiptTotalLabels = Regex(
        "(?i)(grand\\s*total|total(?:\\s*(?:bayar|belanja|tagihan))?|jumlah(?:\\s*bayar)?|amount\\s*due|net\\s*payable|tagihan)"
    )
    private val numericAmount = Regex("(?i)(?:rp\\.?\\s*)?([0-9][0-9.,]*)")
    private val ignoredReceiptLine = Regex(
        "(?i)(subtotal|sub\\s*total|diskon|discount|ppn|pajak|tax|kembali|change|tunai|cash|debit|kredit|cashier|kasir|invoice|struk|receipt|nomor|no\\.?)"
    )
    private val numberWords = setOf(
        "nol", "kosong", "se", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan",
        "sepuluh", "sebelas", "belas", "puluh", "ratus", "seratus", "seribu", "ribu", "sejuta", "juta", "miliar", "milyar"
    )

    fun fromVoice(spoken: String): ParsedTransaction {
        val raw = spoken.trim()
        val normalized = raw.lowercase(locale)
        val income = when {
            listOf("pengeluaran", "keluar", "beli", "membeli", "pembelian", "bayar", "membayar", "belanja", "biaya").any { normalized.contains(it) } -> false
            listOf("pemasukan", "pendapatan", "penjualan", "menerima", "terima", "masuk").any { normalized.contains(it) } -> true
            else -> false
        }
        val amount = parseAmount(raw)
        val title = cleanVoiceTitle(raw).ifBlank {
            if (income) "Pemasukan usaha" else "Pengeluaran usaha"
        }
        return ParsedTransaction(income, amount, title, categoryFor(normalized, income))
    }

    fun fromReceipt(ocrText: String): ParsedTransaction {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val amount = parseReceiptAmount(lines)
        val detailLines = lines.filter { line ->
            line.count { it.isLetter() } >= 3 &&
                !receiptTotalLabels.containsMatchIn(line) &&
                !ignoredReceiptLine.containsMatchIn(line)
        }
        val fullText = lines.joinToString(" ")
        val categorizedLine = detailLines.firstOrNull { categoryFor(it, false) != "Lainnya" }
        val itemLine = categorizedLine ?: detailLines.drop(1).firstOrNull() ?: detailLines.firstOrNull()
        val title = itemLine?.let(::cleanReceiptLine).orEmpty().ifBlank { "Pembelian dari struk" }
        return ParsedTransaction(
            income = false,
            amount = amount,
            title = title.take(120),
            category = categoryFor(fullText, false)
        )
    }

    fun parseAmount(text: String): Long? {
        val lines = text.lines().filter { it.isNotBlank() }
        val labeled = lines.filter { receiptTotalLabels.containsMatchIn(it) }
        for (line in labeled.asReversed()) {
            val value = numericAmount.findAll(line).lastOrNull()?.groupValues?.getOrNull(1)?.let(::digitsToLong)
            if (value != null && value > 0) return value
        }
        val direct = numericAmount.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.let(::digitsToLong) }
            .filter { it > 0 }.lastOrNull()
        return direct ?: parseIndonesianNumberWords(text)
    }

    private fun parseReceiptAmount(lines: List<String>): Long? {
        for (line in lines.filter { receiptTotalLabels.containsMatchIn(it) }.asReversed()) {
            val value = numericAmount.findAll(line).lastOrNull()?.groupValues?.getOrNull(1)?.let(::digitsToLong)
            if (value != null && value > 0) return value
        }
        val candidates = lines.filter { !ignoredReceiptLine.containsMatchIn(it) }
            .flatMap { line -> numericAmount.findAll(line).mapNotNull { it.groupValues.getOrNull(1)?.let(::digitsToLong) }.toList() }
            .filter { it > 0 }
        return candidates.maxOrNull()
    }

    private fun digitsToLong(raw: String): Long? = raw.filter { it.isDigit() }.toLongOrNull()

    private fun cleanVoiceTitle(raw: String): String {
        var value = raw.lowercase(locale)
        value = value.replace(Regex("(?i)\\b(?:rp|rupiah|idr)\\b"), " ")
        value = value.replace(Regex("\\b[0-9][0-9.,]*\\b"), " ")
        value = value.replace(Regex("(?i)\\b(?:${numberWords.joinToString("|")})\\b"), " ")
        value = value.replace(
            Regex("(?i)\\b(pengeluaran|pemasukan|catat|transaksi|sebesar|senilai|sejumlah|nominal|uang|rupiah)\\b"),
            " "
        )
        value = value.replace(Regex("\\s+"), " ").trim(' ', '.', ',', '-', ':')
        return value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    private fun cleanReceiptLine(raw: String): String {
        var value = raw.replace(numericAmount, " ")
        value = value.replace(Regex("\\s+"), " ").trim(' ', '.', ',', '-', ':', '|')
        return value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    private fun categoryFor(text: String, income: Boolean): String {
        val value = text.lowercase(locale)
        if (income) {
            return if (listOf("jasa", "servis", "service", "konsultasi", "desain").any { value.contains(it) }) {
                "Pendapatan Jasa"
            } else "Penjualan"
        }
        return when {
            listOf("bahan baku", "tepung", "gula", "beras", "kopi", "susu", "telur", "daging", "sayur", "buah", "bumbu", "kemasan", "plastik", "biji kopi", "roti").any { value.contains(it) } -> "Bahan Baku"
            listOf("bensin", "solar", "bbm", "transport", "parkir", "tol", "ojek", "grab", "gojek", "kurir", "ongkir").any { value.contains(it) } -> "Transportasi"
            listOf("listrik", "air", "internet", "wifi", "telepon", "pulsa", "gas", "pln").any { value.contains(it) } -> "Utilitas"
            listOf("gaji", "upah", "honor", "lembur", "pegawai", "karyawan").any { value.contains(it) } -> "Gaji"
            listOf("iklan", "ads", "promosi", "marketing", "endorse", "brosur", "spanduk").any { value.contains(it) } -> "Marketing"
            listOf("mesin", "laptop", "komputer", "peralatan besar", "investasi", "aset", "renovasi").any { value.contains(it) } -> "Investasi"
            listOf("alat tulis", "atk", "perlengkapan", "sabun", "tisu", "kertas", "printer", "alat kebersihan").any { value.contains(it) } -> "Perlengkapan"
            listOf("sewa", "operasional", "administrasi", "maintenance", "perawatan", "kebersihan").any { value.contains(it) } -> "Operasional"
            else -> "Lainnya"
        }
    }

    private fun parseIndonesianNumberWords(text: String): Long? {
        val tokens = Regex("[a-zA-Z]+")
            .findAll(text.lowercase(locale))
            .map { it.value }
            .filter { it in numberWords }
            .toList()
        if (tokens.isEmpty()) return null
        var total = 0L
        var current = 0L
        var foundNumber = false
        for (token in tokens) {
            when (token) {
                "nol", "kosong" -> { current += 0; foundNumber = true }
                "se", "satu" -> { current += 1; foundNumber = true }
                "dua" -> { current += 2; foundNumber = true }
                "tiga" -> { current += 3; foundNumber = true }
                "empat" -> { current += 4; foundNumber = true }
                "lima" -> { current += 5; foundNumber = true }
                "enam" -> { current += 6; foundNumber = true }
                "tujuh" -> { current += 7; foundNumber = true }
                "delapan" -> { current += 8; foundNumber = true }
                "sembilan" -> { current += 9; foundNumber = true }
                "sepuluh" -> { current += 10; foundNumber = true }
                "sebelas" -> { current += 11; foundNumber = true }
                "belas" -> { current = (if (current == 0L) 1L else current) + 10L; foundNumber = true }
                "puluh" -> { current = (if (current == 0L) 1L else current) * 10L; foundNumber = true }
                "ratus", "seratus" -> { current = (if (current == 0L) 1L else current) * 100L; foundNumber = true }
                "seribu", "ribu" -> { total += (if (current == 0L) 1L else current) * 1_000L; current = 0; foundNumber = true }
                "sejuta", "juta" -> { total += (if (current == 0L) 1L else current) * 1_000_000L; current = 0; foundNumber = true }
                "miliar", "milyar" -> { total += (if (current == 0L) 1L else current) * 1_000_000_000L; current = 0; foundNumber = true }
            }
        }
        return if (foundNumber) (total + current).takeIf { it > 0 } else null
    }
}
