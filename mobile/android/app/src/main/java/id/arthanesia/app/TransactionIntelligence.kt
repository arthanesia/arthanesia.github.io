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
        "(?i)(grand\\s*total|\\btotal(?:\\s*(?:bayar|belanja|tagihan))?\\b|\\bjumlah(?:\\s*bayar)?\\b|\\bamount\\s*due\\b|\\bnet\\s*payable\\b|\\btagihan\\b)"
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
        var group = 0L
        var pending = 0L
        var foundNumber = false
        for (token in tokens) {
            when (token) {
                "nol", "kosong" -> { foundNumber = true }
                "se", "satu" -> { pending += 1; foundNumber = true }
                "dua" -> { pending += 2; foundNumber = true }
                "tiga" -> { pending += 3; foundNumber = true }
                "empat" -> { pending += 4; foundNumber = true }
                "lima" -> { pending += 5; foundNumber = true }
                "enam" -> { pending += 6; foundNumber = true }
                "tujuh" -> { pending += 7; foundNumber = true }
                "delapan" -> { pending += 8; foundNumber = true }
                "sembilan" -> { pending += 9; foundNumber = true }
                "sepuluh" -> { pending += 10; foundNumber = true }
                "sebelas" -> { pending += 11; foundNumber = true }
                "belas" -> { group += (if (pending == 0L) 1L else pending) + 10L; pending = 0; foundNumber = true }
                "puluh" -> { group += (if (pending == 0L) 1L else pending) * 10L; pending = 0; foundNumber = true }
                "ratus", "seratus" -> { group += (if (pending == 0L) 1L else pending) * 100L; pending = 0; foundNumber = true }
                "seribu", "ribu" -> {
                    total += (group + pending).coerceAtLeast(1L) * 1_000L
                    group = 0; pending = 0; foundNumber = true
                }
                "sejuta", "juta" -> {
                    total += (group + pending).coerceAtLeast(1L) * 1_000_000L
                    group = 0; pending = 0; foundNumber = true
                }
                "miliar", "milyar" -> {
                    total += (group + pending).coerceAtLeast(1L) * 1_000_000_000L
                    group = 0; pending = 0; foundNumber = true
                }
            }
        }
        return if (foundNumber) (total + group + pending).takeIf { it > 0 } else null
    }
}
