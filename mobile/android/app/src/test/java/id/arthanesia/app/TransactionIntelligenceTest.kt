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
    }

    @Test
    fun serviceIncomeIsCategorizedAsServiceRevenue() {
        val result = TransactionIntelligence.fromVoice("pemasukan 250000 jasa desain")

        assertTrue(result.income)
        assertEquals(250_000L, result.amount)
        assertEquals("Pendapatan Jasa", result.category)
    }
}
