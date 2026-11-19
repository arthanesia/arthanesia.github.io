package id.arthanesia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionIntelligenceTest {
    @Test
    fun numericVoiceSeparatesExpenseFields() {
        val result = TransactionIntelligence.fromVoice("pengeluaran 5000 beli bahan baku")

        assertFalse(result.income)
        assertEquals(5_000L, result.amount)
        assertEquals("Beli bahan baku", result.title)
        assertEquals("Bahan Baku", result.category)
    }

    @Test
    fun spokenIndonesianNumberIsParsed() {
        val result = TransactionIntelligence.fromVoice("pengeluaran lima ribu beli bahan baku")

        assertEquals(5_000L, result.amount)
        assertEquals("Beli bahan baku", result.title)
        assertEquals("Bahan Baku", result.category)
    }

    @Test
    fun numericSpeechWithThousandUnitIsScaled() {
        val result = TransactionIntelligence.fromVoice("pengeluaran 5 ribu beli bahan baku")

        assertEquals(5_000L, result.amount)
        assertEquals("Beli bahan baku", result.title)
        assertEquals("Bahan Baku", result.category)
    }

    @Test
    fun smallNumericVoiceAmountIsAccepted() {
        val result = TransactionIntelligence.fromVoice("pengeluaran 50 beli air minum")

        assertEquals(50L, result.amount)
        assertEquals("Utilitas", result.category)
    }

    @Test
    fun receiptUsesGrandTotalInsteadOfSubtotal() {
        val receipt = """
            TOKO MAKMUR
            Tepung Terigu 1 KG 15.000
            Gula Pasir 1 KG 18.000
            Subtotal Rp 33.000
            TOTAL Rp 33.000
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)

        assertFalse(result.income)
        assertEquals(33_000L, result.amount)
        assertEquals("Bahan Baku", result.category)
        assertEquals("Belanja bahan baku", result.title)
    }

    @Test
    fun serviceIncomeIsCategorizedAsServiceRevenue() {
        val result = TransactionIntelligence.fromVoice("pemasukan 250000 jasa desain")

        assertTrue(result.income)
        assertEquals(250_000L, result.amount)
        assertEquals("Pendapatan Jasa", result.category)
    }

    @Test
    fun receiptDoesNotMistakeLargestProductPriceForGrandTotal() {
        val receipt = """
            TOKO MAKMUR
            Tepung Terigu 1 KG 15.000
            Gula Pasir 1 KG 18.000
            Susu UHT 2 PCS 12.000
            """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)

        assertEquals(null, result.amount)
        assertEquals("Belanja bahan baku", result.title)
    }

    @Test
    fun receiptPrefersGrandTotalAndIgnoresSubtotalAndTax() {
        val receipt = """
            TOKO MAKMUR
            Tepung Terigu 1 KG 15.000
            Subtotal Rp 15.000
            PPN Rp 1.650
            TOTAL BAYAR Rp 16.650
            Tunai Rp 20.000
            Kembali Rp 3.350
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)

        assertEquals(16_650L, result.amount)
    }

    @Test
    fun receiptUsesFirstAmountAfterTotalInsteadOfTrailingCardNumber() {
        val receipt = """
            TOKO MAKMUR
            TOTAL BAYAR Rp 33.000 KARTU 12345678
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)

        assertEquals(33_000L, result.amount)
        assertEquals("Transaksi dari struk", result.title)
    }

    @Test
    fun receiptNormalizesOcrLetterOInsideTotal() {
        val result = TransactionIntelligence.fromReceipt("TOTAL BAYAR Rp 33.OOO")
        assertEquals(33_000L, result.amount)
    }

    @Test
    fun goldZakatRequiresNisabAndHaul() {
        val result = ZakatCalculator.evaluateGold(
            ownedGrams = 100.0,
            buybackPricePerGram = 2_300_000.0,
            haulComplete = false
        )

        assertTrue(result.meetsNisab)
        assertFalse(result.eligible)
        assertEquals(0L, result.zakatAmount)
    }

    @Test
    fun goldZakatUsesEightyFiveGramNisabAndTwoPointFivePercent() {
        val result = ZakatCalculator.evaluateGold(
            ownedGrams = 100.0,
            buybackPricePerGram = 2_300_000.0,
            haulComplete = true
        )

        assertEquals(230_000_000L, result.ownedValue)
        assertEquals(230_000_000L, result.zakatAmount * 40L)
        assertTrue(result.eligible)
        assertEquals(5_750_000L, result.zakatAmount)
    }

    @Test
    fun goldBelowNisabHasNoZakat() {
        val result = ZakatCalculator.evaluateGold(
            ownedGrams = 84.99,
            buybackPricePerGram = 2_300_000.0,
            haulComplete = true
        )

        assertFalse(result.meetsNisab)
        assertFalse(result.eligible)
        assertEquals(0L, result.zakatAmount)
    }

    @Test
    fun missingBuybackPriceDoesNotMarkGoldAsEligible() {
        val result = ZakatCalculator.evaluateGold(
            ownedGrams = 100.0,
            buybackPricePerGram = 0.0,
            haulComplete = true
        )

        assertFalse(result.meetsNisab)
        assertEquals(0L, result.nisabValue)
        assertEquals(0L, result.zakatAmount)
    }

    @Test
    fun incomeZakatUses2026BaznasThreshold() {
        val below = ZakatCalculator.evaluateIncome(91_681_727L)
        val above = ZakatCalculator.evaluateIncome(100_000_000L)

        assertFalse(below.first)
        assertTrue(above.first)
        assertEquals(2_500_000L, above.second)
    }

    @Test
    fun receiptReadsTotalOnFollowingLine() {
        val receipt = """
            TOKO MAKMUR
            Tepung Terigu 1 KG
            Rp 15.000
            Gula Pasir 1 KG
            Rp 18.000
            TOTAL BAYAR
            Rp 33.000
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)

        assertEquals(33_000L, result.amount)
        assertEquals("Belanja bahan baku", result.title)
    }

    @Test
    fun receiptReadsItemPriceOnFollowingLineWithoutMistakingItForTotal() {
        val receipt = """
            TOKO MAKMUR
            Tepung Terigu 1 KG
            Rp 15.000
            Gula Pasir 1 KG
            Rp 18.000
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)

        assertEquals(null, result.amount)
        assertEquals("Belanja bahan baku", result.title)
    }


    @Test
    fun receiptNormalizesIndonesianDecimalSuffix() {
        val result = TransactionIntelligence.fromReceipt(
            """
                TOKO MAKMUR
                TOTAL BAYAR
                Rp 33.000,00
            """.trimIndent()
        )

        assertEquals(33_000L, result.amount)
    }

    @Test
    fun receiptNormalizesMixedThousandsAndDecimalSeparators() {
        val result = TransactionIntelligence.fromReceipt(
            """
                TOKO MAKMUR
                GRAND TOTAL Rp 1.234.567,00
            """.trimIndent()
        )

        assertEquals(1_234_567L, result.amount)
    }

    @Test
    fun receiptKeepsThousandsSeparatorWhenThereAreThreeTrailingDigits() {
        val result = TransactionIntelligence.fromReceipt("TOTAL BAYAR Rp 33.000")

        assertEquals(33_000L, result.amount)
    }

    @Test
    fun receiptAcceptsTotalPaymentAndAmountPayableLabels() {
        val totalPayment = TransactionIntelligence.fromReceipt("TOTAL PAYMENT 125,000")
        val amountPayable = TransactionIntelligence.fromReceipt("AMOUNT PAYABLE 250.000")

        assertEquals(125_000L, totalPayment.amount)
        assertEquals(250_000L, amountPayable.amount)
    }

    @Test
    fun receiptIgnoresSubtotalTaxCashAndChangeLinesWhenFindingTotal() {
        val receipt = """
            TOKO MAKMUR
            Tepung Terigu 1 KG 15.000
            Subtotal Rp 15.000
            PPN Rp 1.650
            Tunai Rp 50.000
            Kembali Rp 33.350
            TOTAL BAYAR Rp 16.650
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)

        assertEquals(16_650L, result.amount)
    }

    @Test
    fun receiptUsesFirstAmountAfterTotalAndDoesNotTakeCardReference() {
        val result = TransactionIntelligence.fromReceipt("TOTAL BAYAR Rp 33.000 KARTU 12345678")

        assertEquals(33_000L, result.amount)
    }


    @Test
    fun receiptReadsIndonesianCurrencyWithTwoDecimalPlaces() {
        val result = TransactionIntelligence.fromReceipt("TOTAL BAYAR Rp 33.000,00")
        assertEquals(33_000L, result.amount)
    }

    @Test
    fun receiptReadsGrandTotalWithMultipleThousandsSeparators() {
        val result = TransactionIntelligence.fromReceipt("GRAND TOTAL Rp 1.234.567,00")
        assertEquals(1_234_567L, result.amount)
    }

    @Test
    fun receiptReadsTotalPaymentAndAmountPayableLabels() {
        val totalPayment = TransactionIntelligence.fromReceipt("TOTAL PAYMENT 125,000")
        val amountPayable = TransactionIntelligence.fromReceipt("AMOUNT PAYABLE 250.000")
        assertEquals(125_000L, totalPayment.amount)
        assertEquals(250_000L, amountPayable.amount)
    }

    @Test
    fun receiptIgnoresTaxCashAndChangeWhenFindingPaymentTotal() {
        val receipt = """
            TOKO MAKMUR
            Tepung Terigu 1 KG 15.000
            Subtotal Rp 15.000
            PPN Rp 1.650
            Tunai Rp 50.000
            Kembali Rp 33.350
            TOTAL BAYAR Rp 16.650
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)
        assertEquals(16_650L, result.amount)
    }

    @Test
    fun receiptDoesNotReadTrailingCardReferenceAsPaymentAmount() {
        val result = TransactionIntelligence.fromReceipt("TOTAL BAYAR Rp 33.000 KARTU 12345678")
        assertEquals(33_000L, result.amount)
    }

    @Test
    fun voiceAmountUsesFirstNumericValueBeforeItemQuantity() {
        val result = TransactionIntelligence.fromVoice("pengeluaran 50000 beli 2 kilogram tepung")
        assertEquals(50_000L, result.amount)
        assertEquals("Bahan Baku", result.category)
    }

    @Test
    fun receiptReadsCompactCurrencyPrefixOnFollowingLine() {
        val receipt = """
            TOKO MAKMUR
            TOTAL BAYAR
            RP33.000,00
        """.trimIndent()

        val result = TransactionIntelligence.fromReceipt(receipt)
        assertEquals(33_000L, result.amount)
    }
}
