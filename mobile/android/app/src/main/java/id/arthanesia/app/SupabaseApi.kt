package id.arthanesia.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MarketData(
    val ratesPerIdr: Map<String, Double>,
    val goldIdrPerGram: Double,
    val fxUpdatedAt: String,
    val goldUpdatedAt: String
)

internal data class CloudProfile(
    val fullName: String,
    val businessName: String,
    val baseCurrency: String
)

internal data class CloudSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

internal class SecureSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val alias = "arthanesia_session_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(session: CloudSession) {
        val plain = JSONObject().apply {
            put("userId", session.userId)
            put("email", session.email)
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            put("expiresAt", session.expiresAt)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        val blob = cipher.iv + encrypted
        prefs.edit().putString("session", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun load(): CloudSession? = runCatching {
        val raw = prefs.getString("session", null) ?: return null
        val blob = Base64.decode(raw, Base64.NO_WRAP)
        require(blob.size > 12)
        val iv = blob.copyOfRange(0, 12)
        val payload = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val obj = JSONObject(String(cipher.doFinal(payload), Charsets.UTF_8))
        CloudSession(
            userId = obj.getString("userId"),
            email = obj.optString("email"),
            accessToken = obj.getString("accessToken"),
            refreshToken = obj.getString("refreshToken"),
            expiresAt = obj.optLong("expiresAt")
        )
    }.getOrElse {
        clear()
        null
    }

    fun clear() {
        prefs.edit().remove("session").apply()
    }
}

internal object SupabaseApi {
    private const val BASE_URL = "https://ixihqkifpmxruvjyfnrk.supabase.co"
    private const val PUBLIC_KEY = "sb_publishable_-kbqcW7De6AgeVLgBkTaPg_iD1EicRt"

    private data class HttpResult(val code: Int, val body: String)

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        bearer: String? = null,
        prefer: String? = null
    ): HttpResult {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 18000
            setRequestProperty("apikey", PUBLIC_KEY)
            setRequestProperty("Accept", "application/json")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            prefer?.let { setRequestProperty("Prefer", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResult(code, response)
        } finally {
            connection.disconnect()
        }
    }

    private fun sessionFrom(obj: JSONObject): CloudSession {
        val user = obj.optJSONObject("user") ?: throw IllegalStateException("Respons autentikasi tidak memuat pengguna.")
        val access = obj.optString("access_token")
        val refresh = obj.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("Email perlu diverifikasi sebelum akun dapat digunakan.")
        val expiresAt = obj.optLong("expires_at", System.currentTimeMillis() / 1000 + obj.optLong("expires_in", 3600))
        return CloudSession(user.getString("id"), user.optString("email"), access, refresh, expiresAt)
    }

    suspend fun signIn(email: String, password: String): CloudSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val result = request("POST", "/auth/v1/token?grant_type=password", body.toString())
        if (result.code !in 200..299) throw IllegalStateException(errorMessage(result.body, "Email atau password tidak benar."))
        sessionFrom(JSONObject(result.body))
    }

    suspend fun signUp(name: String, business: String, email: String, password: String): CloudSession? =
        withContext(Dispatchers.IO) {
            val metadata = JSONObject().put("full_name", name.trim()).put("business_name", business.trim())
            val body = JSONObject().put("email", email.trim()).put("password", password).put("data", metadata)
            val result = request("POST", "/auth/v1/signup", body.toString())
            if (result.code !in 200..299) throw IllegalStateException(errorMessage(result.body, "Pendaftaran gagal."))
            val response = JSONObject(result.body)
            if (response.has("access_token") && !response.isNull("access_token")) sessionFrom(response) else null
        }

    suspend fun refresh(session: CloudSession): CloudSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("refresh_token", session.refreshToken)
        val result = request("POST", "/auth/v1/token?grant_type=refresh_token", body.toString())
        if (result.code !in 200..299) throw IllegalStateException("Sesi berakhir. Silakan masuk kembali.")
        sessionFrom(JSONObject(result.body))
    }

    suspend fun fetchProfile(session: CloudSession): CloudProfile? = withContext(Dispatchers.IO) {
        val result = request(
            "GET",
            "/rest/v1/profiles?select=full_name,business_name,base_currency&id=eq.${session.userId}&limit=1",
            bearer = session.accessToken
        )
        if (result.code !in 200..299) throw IllegalStateException(errorMessage(result.body, "Profil cloud belum dapat dibaca."))
        val rows = JSONArray(result.body)
        if (rows.length() == 0) null else {
            val row = rows.getJSONObject(0)
            CloudProfile(
                fullName = row.optString("full_name"),
                businessName = row.optString("business_name"),
                baseCurrency = row.optString("base_currency", "IDR")
            )
        }
    }

    suspend fun saveProfile(session: CloudSession, name: String, business: String, currency: String) =
        withContext(Dispatchers.IO) {
            val body = JSONArray().put(
                JSONObject()
                    .put("id", session.userId)
                    .put("full_name", name)
                    .put("business_name", business)
                    .put("base_currency", currency)
            )
            val result = request(
                "POST",
                "/rest/v1/profiles?on_conflict=id",
                body.toString(),
                session.accessToken,
                "resolution=merge-duplicates,return=minimal"
            )
            if (result.code !in 200..299) throw IllegalStateException(errorMessage(result.body, "Profil cloud belum tersimpan."))
        }

    suspend fun syncTransactions(session: CloudSession, local: List<MoneyEntry>): List<MoneyEntry> =
        withContext(Dispatchers.IO) {
            if (local.isNotEmpty()) {
                val payload = JSONArray()
                local.forEach { entry ->
                    val isoDate = runCatching {
                        LocalDate.parse(entry.date, DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("id", "ID")))
                    }.getOrElse { LocalDate.now() }
                    payload.put(JSONObject()
                        .put("user_id", session.userId)
                        .put("client_ref", "${session.userId}-${entry.id}")
                        .put("type", if (entry.income) "income" else "expense")
                        .put("amount", entry.originalAmount)
                        .put("currency", entry.currency)
                        .put("amount_base", entry.amount)
                        .put("base_currency", "IDR")
                        .put("exchange_rate", entry.fxRateToIdr)
                        .put("category", entry.category)
                        .put("description", entry.title)
                        .put("occurred_at", isoDate.atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant().toString())
                        .put("source", "manual"))
                }
                val upsert = request(
                    "POST",
                    "/rest/v1/transactions?on_conflict=user_id,client_ref",
                    payload.toString(),
                    session.accessToken,
                    "resolution=merge-duplicates,return=minimal"
                )
                if (upsert.code !in 200..299) throw IllegalStateException(errorMessage(upsert.body, "Sinkronisasi transaksi gagal."))
            }

            val response = request(
                "GET",
                "/rest/v1/transactions?select=client_ref,type,amount,category,description,occurred_at&order=occurred_at.asc",
                bearer = session.accessToken
            )
            if (response.code !in 200..299) throw IllegalStateException(errorMessage(response.body, "Data cloud belum dapat dibaca."))
            val rows = JSONArray(response.body)
            val known = local.map { it.id }.toMutableSet()
            val merged = local.toMutableList()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val clientRef = row.optString("client_ref")
                val id = clientRef.removePrefix("${session.userId}-").toLongOrNull()
                    ?: (clientRef.hashCode().toLong().let { if (it < 0) -it else it } + 1_000_000_000L)
                if (id in known) continue
                val instant = runCatching { Instant.parse(row.getString("occurred_at")) }.getOrNull()
                val date = instant?.atZone(ZoneId.of("Asia/Jakarta"))?.toLocalDate()
                    ?.format(DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("id", "ID")))
                    ?: LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("id", "ID")))
                merged += MoneyEntry(
                    id = id,
                    title = row.optString("description", "Transaksi"),
                    category = row.optString("category", "Lainnya"),
                    amount = row.optDouble("amount_base", row.optDouble("amount", 0.0)).toLong(),
                    income = row.optString("type") == "income",
                    date = date,
                    currency = row.optString("currency", "IDR"),
                    originalAmount = row.optDouble("amount", 0.0),
                    fxRateToIdr = row.optDouble("exchange_rate", 1.0)
                )
                known += id
            }
            merged
        }

    suspend fun deleteTransaction(session: CloudSession, id: Long): Unit = withContext(Dispatchers.IO) {
        val result = request("DELETE", "/rest/v1/transactions?client_ref=eq.${session.userId}-$id", bearer = session.accessToken)
        if (result.code !in 200..299) throw IllegalStateException(errorMessage(result.body, "Transaksi lokal dihapus, tetapi penghapusan cloud gagal."))
    }

    suspend fun signOut(session: CloudSession): Unit = withContext(Dispatchers.IO) {
        request("POST", "/auth/v1/logout", bearer = session.accessToken)
    }

    suspend fun fetchMarketData(): MarketData = withContext(Dispatchers.IO) {
        val response = request("GET", "/functions/v1/market-data")
        if (response.code !in 200..299) throw IllegalStateException(errorMessage(response.body, "Harga pasar tidak tersedia."))
        val obj = JSONObject(response.body)
        val ratesJson = obj.getJSONObject("rates")
        val rates = mutableMapOf<String, Double>()
        ratesJson.keys().forEach { code -> rates[code] = ratesJson.optDouble(code, 0.0) }
        val gold = obj.getJSONObject("gold")
        MarketData(
            ratesPerIdr = rates,
            goldIdrPerGram = gold.optDouble("idrPerGram", 0.0),
            fxUpdatedAt = obj.optString("rateUpdatedAt"),
            goldUpdatedAt = gold.optString("updatedAt")
        )
    }

    suspend fun askAssistant(session: CloudSession, question: String, consent: Boolean): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("question", question)
            .put("consent", consent)
        val response = request(
            "POST",
            "/functions/v1/artha-assistant",
            payload.toString(),
            session.accessToken
        )
        if (response.code !in 200..299) {
            throw IllegalStateException(errorMessage(response.body, "Artha AI cloud belum tersedia."))
        }
        JSONObject(response.body).optString("answer").ifBlank { "AI belum memberikan jawaban." }
    }

    private fun errorMessage(raw: String, fallback: String): String = runCatching {
        val obj = JSONObject(raw)
        obj.optString("msg").ifBlank { obj.optString("message").ifBlank { obj.optString("error_description").ifBlank { fallback } } }
    }.getOrDefault(fallback)
}
