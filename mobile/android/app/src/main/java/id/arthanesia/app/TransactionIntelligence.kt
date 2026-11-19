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
 * Deterministic parser for offline voice and receipt suggestions.
 * OCR totals are only accepted when a clear total label is present: product prices,
 * barcodes and payment references are never silently treated as the transaction total.
 * The user always reviews the result before saving.
 */
internal object TransactionIntelligence {
    private val locale = Locale("id", "ID")

    private val receiptTotalLabels = Regex(
        "(?i)(grand\\\\s*total|total\\\\s*(?:bayar|pembayaran|belanja|transaksi|akhir|tagihan|payment|due|payable)|jumlah\\\\s*(?:bayar|dibayar|pembayaran|tagihan)|amount\\\\s*(?:due|payable)|net\\\\s*payable|\\\\btagihan\\\\b|\\\\btotal\\\\b)"
    )
    private val subtotalLabels = Regex("(?i)(sub\\s*total|subtotal|total\\s*sebelum)")
    private val numericAmount = Regex("(?i)(?:rp\\.?\\s*|idr\\s*)?([0-9][0-9.,Oo]*)")
    private val ignoredReceiptLine = Regex(
        "(?i)(subtotal|sub\\s*total|diskon|discount|ppn|pajak|tax|kembali|change|tunai|cash|debit|kredit|cashier|kasir|invoice|struk|receipt|nomor|no\\.?|telp|telepon|alamat|www\\.|http)"
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
        val lines = ocrText.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }
        val amount = parseReceiptAmount(lines)
        val productLines = lines.mapIndexedNotNull { index, line ->
            if (ignoredReceiptLine.containsMatchIn(line) ||
                receiptTotalLabels.containsMatchIn(line) ||
                line.count { it.isLetter() } < 3
            ) return@mapIndexedNotNull null
            val amountOnThisLine = numericAmount.containsMatchIn(line)
            val amountOnNextLine = lines.getOrNull(index + 1)?.let(::isAmountOnlyLine) == true
            if (amountOnThisLine || amountOnNextLine) line else null
        }
        val categorySource = productLines.ifEmpty {
            lines.filterNot { ignoredReceiptLine.containsMatchIn(it) || receiptTotalLabels.containsMatchIn(it) }
        }.joinToString(" ")
        val category = categoryFor(categorySource, false)
        val title = when (category) {
            "Bahan Baku" -> "Belanja bahan baku"
            "Transportasi" -> "Biaya transportasi"
            "Utilitas" -> "Biaya utilitas"
            "Gaji" -> "Biaya gaji"
            "Marketing" -> "Biaya pemasaran"
            "Investasi" -> "Pembelian aset"
            "Perlengkapan" -> "Pembelian perlengkapan"
            "Operasional" -> "Biaya operasional"
            else -> "Transaksi dari struk"
        }
        return ParsedTransaction(income = false, amount = amount, title = title, category = category)
    }

    fun parseAmount(text: String): Long? {
        val scaled = Regex("""(?i)([0-9][0-9.,]*)\s*(rb|ribu|jt|juta|miliar|milyar)\b""")
            .findAll(text)
            .lastOrNull()
        if (scaled != null) {
            val base = digitsToLong(scaled.groupValues[1]) ?: 0L
            val multiplier = when (scaled.groupValues[2].lowercase(locale)) {
                "rb", "ribu" -> 1_000L
                "jt", "juta" -> 1_000_000L
                else -> 1_000_000_000L
            }
            return runCatching { Math.multiplyExact(base, multiplier) }.getOrNull()
        }
        val lines = text.lines().filter { it.isNotBlank() }
        val labeled = lines.filter { receiptTotalLabels.containsMatchIn(it) && !subtotalLabels.containsMatchIn(it) }
        for (line in labeled.asReversed()) {
            val value = numericAmount.findAll(line).lastOrNull()?.groupValues?.getOrNull(1)?.let(::digitsToLong)
            if (value != null && value > 0) return value
        }
        val direct = numericAmount.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.let(::digitsToLong) }
            .firstOrNull { it > 0 }
        return direct ?: parseIndonesianNumberWords(text)
    }

    private fun isAmountOnlyLine(raw: String): Boolean {
        val normalized = raw
            .replace(Regex("(?i)^\\s*(?:rp\\.?\\s*|idr\\s*)"), "")
            .replace('O', '0')
            .replace('o', '0')
            .trim()
        return normalized.matches(Regex("[0-9][0-9.,\\s]*")) &&
            normalized.count { it.isDigit() } >= 3
    }

    private fun parseReceiptNumeric(raw: String): Long? {
        val normalized = raw.replace('O', '0').replace('o', '0')
        val token = numericAmount.find(normalized)?.groupValues?.getOrNull(1) ?: return null
        val dot = token.lastIndexOf('.')
        val comma = token.lastIndexOf(',')
        val lastSeparator = maxOf(dot, comma)
        val digits = when {
            dot >= 0 && comma >= 0 -> {
                val decimals = token.length - lastSeparator - 1
                if (decimals in 1..2) token.substring(0, lastSeparator).filter(Char::isDigit)
                else token.filter(Char::isDigit)
            }
            lastSeparator >= 0 -> {
                val decimals = token.length - lastSeparator - 1
                when {
                    decimals == 3 -> token.filter(Char::isDigit)
                    decimals in 1..2 -> token.substring(0, lastSeparator).filter(Char::isDigit)
                    else -> token.filter(Char::isDigit)
                }
            }
            else -> token.filter(Char::isDigit)
        }
        return digits.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun isAmountOnlyLine(raw: String): Boolean {
        val normalized = raw
            .replace(Regex("(?i)\\b(?:rp\\.?|idr)\\b"), " ")
            .replace('O', '0')
            .replace('o', '0')
            .trim()
        return normalized.matches(Regex("[0-9][0-9.,\\s]*")) &&
            normalized.count { it.isDigit() } >= 3
    }

    private fun parseReceiptAmount(lines: List<String>): Long? {
        val priorityLabel = Regex(
            "(?i)(grand\\s*total|total\\s*(?:pembayaran|bayar|belanja|transaksi|akhir|tagihan|payment|due|payable)|jumlah\\s*(?:dibayar|bayar|pembayaran|tagihan)|amount\\s*(?:due|payable)|net\\s*payable)"
        )
        val anyTotalLabel = Regex("(?i)\\btotal\\b|\\btagihan\\b")
        var bestPriority = 0
        var bestIndex = -1
        var bestAmount: Long? = null

        lines.forEachIndexed { index, line ->
            if (subtotalLabels.containsMatchIn(line) || ignoredReceiptLine.containsMatchIn(line)) {
                return@forEachIndexed
            }
            val strongLabel = priorityLabel.find(line)
            val label = strongLabel ?: anyTotalLabel.find(line)
            val priority = when {
                strongLabel != null -> 3
                label != null -> 2
                else -> 0
            }
            if (priority == 0 || label == null) return@forEachIndexed

            val amountTail = line.substring(label.range.last + 1)
            val sameLineAmount = parseReceiptNumeric(amountTail)
            val nextLine = lines.getOrNull(index + 1)
            val nextLineAmount = if (nextLine != null && isAmountOnlyLine(nextLine)) {
                parseReceiptNumeric(nextLine)
            } else null
            val value = sameLineAmount ?: nextLineAmount
            if (value != null &&
                (priority > bestPriority || (priority == bestPriority && index > bestIndex))
            ) {
                bestPriority = priority
                bestIndex = index
                bestAmount = value
            }
        }
        return bestAmount
    }

    private fun digitsToLong(raw: String): Long? {
        val normalized = raw.replace('O', '0').replace('o', '0').trim()
        val lastDot = normalized.lastIndexOf('.')
        val lastComma = normalized.lastIndexOf(',')
        val lastSeparator = maxOf(lastDot, lastComma)
        val separatorCount = normalized.count { it == '.' || it == ',' }
        val trailingDigits = if (lastSeparator >= 0) {
            normalized.substring(lastSeparator + 1).count { it.isDigit() }
        } else 0

        // Rupiah is stored as an integer. Drop a decimal suffix, but preserve
        // Indonesian thousands separators (33.000) and mixed formats (33.000,00).
        val hasDecimalSuffix = lastSeparator >= 0 && trailingDigits in 1..2 &&
            (separatorCount == 1 || separatorCount > 1)
        val integerPart = if (hasDecimalSuffix) normalized.substring(0, lastSeparator) else normalized
        val digits = integerPart.filter { it.isDigit() }
        return digits.toLongOrNull()
    }

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
        var value = raw.replace(Regex("(?i)\\b(?:rp|idr)\\b"), " ")
        value = value.replace(Regex("(?i)\\b[0-9][0-9.,]*\\b"), " ")
        value = value.replace(Regex("(?i)\\b(kg|gr|gram|g|ml|liter|ltr|pcs|pc|pack|dus|btl|botol|qty)\\b"), " ")
        value = value.replace(Regex("[|:]+"), " ")
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
            listOf("bahan baku", "tepung", "terigu", "gula", "beras", "kopi", "susu", "telur", "daging", "sayur", "buah", "bumbu", "kemasan", "plastik", "biji kopi", "roti", "minyak goreng").any { value.contains(it) } -> "Bahan Baku"
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
