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
        assertTrue(result.title.contains("Tepung Terigu"))
        assertTrue(result.title.contains("Gula Pasir"))
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
        assertTrue(result.title.contains("Tepung Terigu"))
        assertTrue(result.title.contains("Gula Pasir"))
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

        assertEquals(195_500_000L, result.ownedValue)
        assertEquals(195_500_000L, result.zakatAmount * 40L)
        assertTrue(result.eligible)
        assertEquals(4_887_500L, result.zakatAmount)
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
    
}
