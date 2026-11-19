package id.arthanesia.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.min

private val Brand = Color(0xFF0070BA)
private val BrandDark = Color(0xFF003087)
private val Green = Color(0xFF008A68)
private val Red = Color(0xFFD9485F)
private val Gold = Color(0xFFC58B34)
private fun rupiah(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale("id", "ID")).format(value).replace(",00", "")

private fun marketTimestamp(value: String): String? = runCatching {
    Instant.parse(value).atZone(ZoneId.of("Asia/Jakarta"))
        .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale("id", "ID"))) + " WIB"
}.getOrNull()

internal data class MoneyEntry(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val category: String,
    val amount: Long,
    val income: Boolean,
    val date: String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("id", "ID"))),
    val currency: String = "IDR",
    val originalAmount: Double = amount.toDouble(),
    val fxRateToIdr: Double = 1.0
)
internal data class SavingGoal(val name: String, val current: Long, val target: Long, val due: String)
private enum class Page(val label: String) {
    HOME("Beranda"), TRANSACTIONS("Transaksi"), ANALYTICS("Analitik"),
    GOALS("Target"), ZAKAT("Zakat"), TAX("Pajak"), ASSISTANT("Artha AI"), PROFILE("Profil")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(245, 248, 251)
        window.navigationBarColor = android.graphics.Color.rgb(245, 248, 251)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { ArthanesiaApp() }
    }
}

@Composable
private fun ArthanesiaApp() {
    val context = LocalContext.current
    val appDatabase = remember { ArthanesiaDatabase(context) }
    val sessionStore = remember { SecureSessionStore(context) }
    var cloudSession by remember { mutableStateOf(sessionStore.load()) }
    val loggedIn = cloudSession != null
    val coroutineScope = rememberCoroutineScope()
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf("") }
    var syncStatus by remember { mutableStateOf("Data tersimpan di perangkat") }
    var fxMarketData by remember { mutableStateOf<MarketData?>(null) }
    var dark by remember { mutableStateOf(appDatabase.setting("dark") == "true") }
    var page by remember { mutableStateOf(Page.HOME) }
    var owner by remember { mutableStateOf(appDatabase.setting("owner")) }
    var business by remember { mutableStateOf(appDatabase.setting("business")) }
    var currency by remember { mutableStateOf(appDatabase.setting("currency", "IDR")) }
    var entries by remember { mutableStateOf(cloudSession?.let { appDatabase.transactions(it.userId) } ?: emptyList()) }
    var goals by remember { mutableStateOf(cloudSession?.let { appDatabase.goals(it.userId) } ?: emptyList()) }
    var showTransaction by remember { mutableStateOf(false) }
    var receiptDraft by remember { mutableStateOf(false) }
    var showEntryChooser by remember { mutableStateOf(false) }
    var initialIncome by remember { mutableStateOf(true) }
    var showGoal by remember { mutableStateOf(false) }
    var voiceDraft by remember { mutableStateOf("") }
    var parsedAmount by remember { mutableStateOf("") }
    var suggestedCategory by remember { mutableStateOf("Penjualan") }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCsv by remember { mutableStateOf<ByteArray?>(null) }
    val pdfSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val payload = pendingPdf
        pendingPdf = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload) }
                    ?: error("Tidak dapat membuka lokasi penyimpanan")
            }.onSuccess {
                Toast.makeText(context, "PDF pembukuan berhasil disimpan.", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "PDF gagal disimpan.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val csvSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val payload = pendingCsv
        pendingCsv = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload) }
                    ?: error("Tidak dapat membuka lokasi penyimpanan")
            }.onSuccess {
                Toast.makeText(context, "CSV pembukuan berhasil disimpan.", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "CSV gagal disimpan.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val incomeTotal = entries.filter { it.income }.sumOf { it.amount }
    val expenseTotal = entries.filter { !it.income }.sumOf { it.amount }
    val balance = incomeTotal - expenseTotal
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF63B8EE), secondary = Color(0xFF62D2B0),
        background = Color(0xFF0C1928), surface = Color(0xFF14263A),
        onBackground = Color(0xFFF2F7FC), onSurface = Color(0xFFF2F7FC),
        surfaceVariant = Color(0xFF20364D), onSurfaceVariant = Color(0xFFB8C8D8)
    ) else lightColorScheme(
        primary = Brand, secondary = Green, background = Color(0xFFF5F8FB),
        surface = Color.White, onBackground = Color(0xFF102A43),
        onSurface = Color(0xFF102A43), surfaceVariant = Color(0xFFEAF3FA),
        onSurfaceVariant = if (dark) Color(0xFFB7C9D9) else Color(0xFF60758B)
    )

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val parsed = TransactionIntelligence.fromVoice(spoken)
            receiptDraft = false
            initialIncome = parsed.income
            voiceDraft = parsed.title
            parsedAmount = parsed.amount?.toString() ?: ""
            suggestedCategory = parsed.category
            showTransaction = true
            if (parsed.amount == null) {
                Toast.makeText(context, "Jenis dan kategori dikenali. Periksa lalu isi nominalnya.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val receiptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching { InputImage.fromFilePath(context, uri) }
                .onSuccess { image ->
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                        .addOnSuccessListener { result ->
                            val parsed = TransactionIntelligence.fromReceipt(result.text)
                            receiptDraft = true
                            voiceDraft = parsed.title
                            parsedAmount = parsed.amount?.toString() ?: ""
                            initialIncome = false
                            suggestedCategory = parsed.category
                            showTransaction = true
                            val feedback = if (parsed.amount == null) {
                                "Struk terbaca. Nominal perlu diperiksa dan diisi manual."
                            } else {
                                "Struk terbaca · ${parsed.category} · ${rupiah(parsed.amount)}. Periksa sebelum simpan."
                            }
                            Toast.makeText(context, feedback, Toast.LENGTH_LONG).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Struk tidak dapat dibaca. Coba foto yang lebih jelas.", Toast.LENGTH_LONG).show()
                        }
                }
                .onFailure {
                    Toast.makeText(context, "Foto tidak dapat dibuka.", Toast.LENGTH_LONG).show()
                }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Ucapkan transaksi, misalnya pemasukan lima ratus ribu rupiah")
            }
            runCatching { speechLauncher.launch(intent) }
        }
    }

    MaterialTheme(colorScheme = colors) {
        SideEffect {
            val window = (context as? ComponentActivity)?.window
            if (window != null) {
                val barColor = if (dark) android.graphics.Color.rgb(12, 25, 40) else android.graphics.Color.rgb(245, 248, 251)
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (dark) 0 else
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!loggedIn) {
                LoginScreen(
                    initialOwner = owner,
                    initialBusiness = business,
                    busy = authBusy,
                    error = authError,
                    onSignIn = { email, password ->
                        coroutineScope.launch {
                            authBusy = true
                            authError = ""
                            try {
                                val session = SupabaseApi.signIn(email, password)
                                appDatabase.bindUnclaimedDataToFirstAccount(session.userId)
                                val remoteProfile = SupabaseApi.fetchProfile(session)
                                cloudSession = session
                                sessionStore.save(session)
                                owner = remoteProfile?.fullName?.ifBlank { appDatabase.setting("owner", email.substringBefore("@")) }
                                    ?: appDatabase.setting("owner", email.substringBefore("@"))
                                business = remoteProfile?.businessName?.ifBlank { appDatabase.setting("business", "Bisnis Saya") }
                                    ?: appDatabase.setting("business", "Bisnis Saya")
                                currency = remoteProfile?.baseCurrency?.takeIf { it.isNotBlank() } ?: appDatabase.setting("currency", "IDR")
                                appDatabase.saveWorkspace(owner, business)
                                appDatabase.saveSetting("currency", currency)
                                entries = appDatabase.transactions(session.userId)
                                goals = appDatabase.goals(session.userId)
                                syncStatus = "Akun terhubung · menyinkronkan data"
                            } catch (e: Exception) {
                                authError = e.message ?: "Login gagal. Periksa koneksi dan akun."
                            } finally {
                                authBusy = false
                            }
                        }
                    },
                    onSignUp = { name, biz, email, password ->
                        coroutineScope.launch {
                            authBusy = true
                            authError = ""
                            try {
                                val session = SupabaseApi.signUp(name, biz, email, password)
                                if (session == null) {
                                    authError = "Akun dibuat. Buka email untuk verifikasi, lalu masuk. Bila email tidak datang, gunakan Kirim ulang verifikasi."
                                } else {
                                    appDatabase.bindUnclaimedDataToFirstAccount(session.userId)
                                    owner = name.trim()
                                    business = biz.trim()
                                    appDatabase.saveWorkspace(owner, business)
                                    entries = appDatabase.transactions(session.userId)
                                    goals = appDatabase.goals(session.userId)
                                    cloudSession = session
                                    sessionStore.save(session)
                                    syncStatus = "Akun terhubung · menyinkronkan data"
                                }
                            } catch (e: Exception) {
                                authError = e.message ?: "Pendaftaran gagal."
                            } finally {
                                authBusy = false
                            }
                        }
                    },
                    dark = dark,
                    onRecoverPassword = { email ->
                        coroutineScope.launch {
                            authBusy = true
                            authError = ""
                            try {
                                SupabaseApi.recoverPassword(email)
                                authError = "Jika email tersebut terdaftar, tautan pemulihan password sudah dikirim. Periksa kotak masuk dan spam."
                            } catch (e: Exception) {
                                authError = e.message ?: "Reset password gagal."
                            } finally {
                                authBusy = false
                            }
                        }
                    },
                    onResendConfirmation = { email ->
                        coroutineScope.launch {
                            authBusy = true
                            authError = ""
                            try {
                                SupabaseApi.resendSignupConfirmation(email)
                                authError = "Jika akun memerlukan verifikasi, email verifikasi sudah dikirim ulang."
                            } catch (e: Exception) {
                                authError = e.message ?: "Email verifikasi belum dapat dikirim ulang."
                            } finally {
                                authBusy = false
                            }
                        }
                    }
                )
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { AppTopBar(page, business, {
                        dark = !dark
                        appDatabase.saveSetting("dark", dark.toString())
                    }, dark) },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                            listOf(Page.HOME, Page.TRANSACTIONS, Page.ANALYTICS, Page.GOALS, Page.PROFILE).forEach { item ->
                                NavigationBarItem(
                                    selected = page == item, onClick = { page = item },
                                    icon = {
                                        Icon(when (item) {
                                            Page.HOME -> Icons.Default.Home
                                            Page.TRANSACTIONS -> Icons.Default.ReceiptLong
                                            Page.ANALYTICS -> Icons.Default.Insights
                                            Page.GOALS -> Icons.Default.Flag
                                            else -> Icons.Default.Person
                                        }, contentDescription = item.label)
                                    },
                                    label = { Text(item.label, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (page) {
                            Page.HOME -> HomeScreen(owner, business, balance, incomeTotal, expenseTotal, entries,
                                syncStatus = syncStatus,
                                onAdd = { initialIncome = it; showEntryChooser = true; voiceDraft = ""; parsedAmount = "" },
                                onVoice = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                                onNavigate = { page = it })
                            Page.TRANSACTIONS -> TransactionsScreen(
                                entries = entries,
                                onAdd = { initialIncome = true; showEntryChooser = true; voiceDraft = ""; parsedAmount = "" },
                                onDelete = { id ->
                                    val activeUser = cloudSession?.userId
                                    appDatabase.deleteTransaction(id, activeUser)
                                    entries = appDatabase.transactions(activeUser)
                                    cloudSession?.let { active ->
                                        coroutineScope.launch {
                                            runCatching { SupabaseApi.deleteTransaction(active, id) }
                                                .onFailure { syncStatus = "Terhapus lokal; penghapusan cloud tertunda" }
                                        }
                                    }
                                },
                                onPrint = {
                                    runCatching { BookkeepingReport.print(context, entries, business, owner) }
                                        .onFailure { Toast.makeText(context, "Tidak dapat membuka fitur cetak.", Toast.LENGTH_LONG).show() }
                                },
                                onSavePdf = {
                                    pendingPdf = BookkeepingReport.createPdf(entries, business, owner)
                                    pdfSaveLauncher.launch("Arthanesia-Pembukuan.pdf")
                                },
                                onSaveCsv = {
                                    pendingCsv = BookkeepingReport.createCsv(entries)
                                    csvSaveLauncher.launch("Arthanesia-Transaksi.csv")
                                }
                            )
                            Page.ANALYTICS -> AnalyticsScreen(entries, incomeTotal, expenseTotal)
                            Page.GOALS -> GoalsScreen(goals, onAdd = { showGoal = true })
                            Page.ZAKAT -> ZakatScreen(entries, fxMarketData)
                            Page.TAX -> TaxScreen(entries)
                            Page.ASSISTANT -> AssistantScreen(balance, incomeTotal, expenseTotal, entries, business, cloudSession)
                            Page.PROFILE -> ProfileScreen(owner, business, currency, dark, entries, goals,
                                onCurrency = { currency = it; appDatabase.saveSetting("currency", it) },
                                onTheme = { dark = !dark; appDatabase.saveSetting("dark", dark.toString()) },
                                onLogout = {
                                 cloudSession?.let { active -> coroutineScope.launch { runCatching { SupabaseApi.signOut(active) } } }
                                 sessionStore.clear()
                                 cloudSession = null
                                 entries = emptyList()
                                 goals = emptyList()
                                 page = Page.HOME
                             })
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(page) {
        if (page == Page.HOME || page == Page.TRANSACTIONS || page == Page.ZAKAT) {
            runCatching { SupabaseApi.fetchMarketData() }.onSuccess { fxMarketData = it }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            runCatching { SupabaseApi.fetchMarketData() }.onSuccess { fxMarketData = it }
        }
    }

    LaunchedEffect(cloudSession?.userId, entries, owner, business, currency) {
        val current = cloudSession ?: return@LaunchedEffect
        try {
            val active = if (current.expiresAt <= System.currentTimeMillis() / 1000 + 90) {
                SupabaseApi.refresh(current).also {
                    sessionStore.save(it)
                    cloudSession = it
                }
            } else current
            SupabaseApi.saveProfile(active, owner, business, currency)
            val merged = SupabaseApi.syncTransactions(active, entries)
            merged.forEach { appDatabase.saveTransaction(it, active.userId) }
            entries = appDatabase.transactions(active.userId)
            syncStatus = "Tersinkron ke cloud · ${entries.size} transaksi"
        } catch (e: Exception) {
            syncStatus = "Offline · data tetap tersimpan di perangkat"
        }
    }

    if (showEntryChooser) {
        EntryMethodDialog(
            onDismiss = { showEntryChooser = false },
            onManual = { showEntryChooser = false; receiptDraft = false; voiceDraft = ""; parsedAmount = ""; suggestedCategory = if (initialIncome) "Penjualan" else "Lainnya"; showTransaction = true },
            onReceipt = { showEntryChooser = false; receiptLauncher.launch("image/*") },
            onVoice = { showEntryChooser = false; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        )
    }
    if (showTransaction) {
        TransactionDialog(initialIncome, voiceDraft, parsedAmount, suggestedCategory, currency, fxMarketData, receiptDraft,
            onDismiss = { showTransaction = false; receiptDraft = false; voiceDraft = ""; parsedAmount = "" }) {
            val ownerId = cloudSession?.userId ?: return@TransactionDialog
            appDatabase.saveTransaction(it, ownerId)
            entries = appDatabase.transactions(ownerId)
            showTransaction = false
            receiptDraft = false
            voiceDraft = ""
            parsedAmount = ""
        }
    }
    if (showGoal) GoalDialog(onDismiss = { showGoal = false }) {
        val ownerId = cloudSession?.userId ?: return@GoalDialog
        appDatabase.saveGoal(it, ownerId)
        goals = appDatabase.goals(ownerId)
        showGoal = false
    }
}

@Composable
private fun LoginScreen(
    initialOwner: String,
    initialBusiness: String,
    busy: Boolean,
    error: String,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String, String) -> Unit,
    dark: Boolean,
    onRecoverPassword: (String) -> Unit,
    onResendConfirmation: (String) -> Unit
) {
    var mode by remember { mutableStateOf("signin") }
    var name by remember { mutableStateOf(initialOwner) }
    var business by remember { mutableStateOf(initialBusiness) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val pageBackground = if (dark) listOf(Color(0xFF081522), Color(0xFF10243A)) else listOf(Color(0xFFEAF4FB), Color(0xFFFAFCFE))
    val cardBackground = if (dark) Color(0xFF14263A) else Color.White
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(pageBackground))
    ) {
        val compact = maxWidth < 600.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 22.dp else 72.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            WalletLogo(if (compact) 72.dp else 88.dp)
            Spacer(Modifier.height(12.dp))
            Text("Arthanesia", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("Keuangan usaha, lebih terarah", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("KELOLA  ·  RENCANAKAN  ·  TUMBUH", fontSize = 9.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Card(
                Modifier.fillMaxWidth().widthIn(max = 440.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                elevation = CardDefaults.cardElevation(if (dark) 0.dp else 6.dp)
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text(if (mode == "signin") "Masuk ke akun" else "Buat akun Arthanesia", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("Gunakan akun pribadi untuk menyimpan data keuangan dan menyinkronkannya antarperangkat.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (mode == "signup") {
                        OutlinedTextField(name, { name = it }, label = { Text("Nama pemilik") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(business, { business = it }, label = { Text("Nama usaha") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (mode == "signup") "Password (min. 8 karakter)" else "Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Sembunyikan password" else "Tampilkan password"
                                )
                            }
                        }
                    )
                    if (error.isNotBlank()) Text(error, color = Red, fontSize = 10.sp)
                    Button(
                        onClick = {
                            if (mode == "signin") onSignIn(email, password)
                            else onSignUp(name, business, email, password)
                        },
                        enabled = !busy && email.trim().contains("@") &&
                            (if (mode == "signin") AuthFlowRules.canSignIn(password) else AuthFlowRules.canSignUp(password)) &&
                            (mode == "signin" || (name.isNotBlank() && business.isNotBlank())),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text(if (mode == "signin") "Masuk" else "Daftar akun")
                    }
                    if (mode == "signin") {
                        TextButton(
                            onClick = { onRecoverPassword(email) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && email.trim().contains("@")
                        ) {
                            Text("Lupa password? Kirim tautan pemulihan")
                        }
                        if (error.contains("verifikasi", ignoreCase = true) || error.contains("confirm", ignoreCase = true)) {
                            TextButton(
                                onClick = { onResendConfirmation(email) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && email.trim().contains("@")
                            ) { Text("Kirim ulang email verifikasi") }
                        }
                    }
                    TextButton(
                        onClick = { mode = if (mode == "signin") "signup" else "signin"; passwordVisible = false },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) {
                        Text(if (mode == "signin") "Belum punya akun? Daftar" else "Sudah punya akun? Masuk")
                    }
                    Text(
                        "Password selalu disembunyikan saat diketik. Data cloud tidak dihapus saat aplikasi diperbarui; gunakan akun lama atau pemulihan password.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletLogo(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFF0070BA), Color(0xFF003087))), RoundedCornerShape(size * .27f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.AccountBalanceWallet, "Logo Arthanesia", tint = Color.White, modifier = Modifier.size(size * .47f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(page: Page, business: String, onTheme: () -> Unit, dark: Boolean) {
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(page.label, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(business, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = {
            IconButton(onClick = onTheme) {
                Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Ganti tema", tint = Brand)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        windowInsets = WindowInsets.statusBars
    )
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp)
            .padding(top = 17.dp, bottom = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeScreen(owner: String, business: String, balance: Long, income: Long, expense: Long,
    entries: List<MoneyEntry>, syncStatus: String, onAdd: (Boolean) -> Unit, onVoice: () -> Unit, onNavigate: (Page) -> Unit) {
    ScreenColumn {
        Column {
            Text("BUSINESS OVERVIEW", fontSize = 9.sp, color = Brand, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
            Text("Halo, $owner 👋", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("Kelola arus kas $business dengan lebih terarah.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudDone, null, tint = if (syncStatus.startsWith("Tersinkron")) Green else Gold, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(syncStatus, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF0070BA), Color(0xFF003087))),
                RoundedCornerShape(23.dp)
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(21.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SALDO BISNIS ANDA", color = Color.White.copy(.78f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.size(36.dp).background(Color.White.copy(.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(rupiah(balance), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).background(Color.White.copy(.12f), RoundedCornerShape(13.dp)).padding(11.dp)) {
                        Text("PEMASUKAN", color = Color(0xFFC8F4E5), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        Text(rupiah(income), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.weight(1f).background(Color.White.copy(.12f), RoundedCornerShape(13.dp)).padding(11.dp)) {
                        Text("PENGELUARAN", color = Color(0xFFFFD9DF), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        Text(rupiah(expense), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction("Catat", Icons.Default.Add, Brand, Modifier.weight(1f)) { onAdd(true) }
            QuickAction("VoiceCash", Icons.Default.Mic, Color(0xFF008A68), Modifier.weight(1f), onVoice)
            QuickAction("Analitik", Icons.Default.Insights, Green, Modifier.weight(1f)) { onNavigate(Page.ANALYTICS) }
            QuickAction("Tanya AI", Icons.Default.AutoAwesome, Gold, Modifier.weight(1f)) { onNavigate(Page.ASSISTANT) }
        }
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                val score = (50 + (if (entries.size >= 5) 15 else entries.size * 3) + (if (balance >= 0 && entries.isNotEmpty()) 20 else 0) + (if (income > 0) 15 else 0)).coerceIn(0, 100)
                SectionTitle("ArthaScore", "Indikator sederhana dari aktivitas pembukuan")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$score", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(if (score >= 80) "Aktivitas sehat" else if (score >= 60) "Terus berkembang" else "Mulai bangun kebiasaan", color = if (score >= 80) Green else MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Skor ini indikator penggunaan, bukan penilaian kredit.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SectionTitle("Akses cepat", "Fitur pengelolaan Arthanesia")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile("Transaksi", Icons.Default.ReceiptLong, Modifier.weight(1f)) { onNavigate(Page.TRANSACTIONS) }
            QuickTile("Target", Icons.Default.Flag, Modifier.weight(1f)) { onNavigate(Page.GOALS) }
            QuickTile("Zakat", Icons.Default.AccountBalance, Modifier.weight(1f)) { onNavigate(Page.ZAKAT) }
            QuickTile("Pajak", Icons.Default.Receipt, Modifier.weight(1f)) { onNavigate(Page.TAX) }
        }
        SectionTitle("Smart Insight", "Ringkasan berbasis transaksi lokal")
        InsightCard(if (income >= expense) "Arus kas positif" else "Perlu evaluasi pengeluaran",
            if (income >= expense) "Pemasukan saat ini lebih tinggi daripada pengeluaran. Pertimbangkan alokasi surplus untuk dana cadangan."
            else "Pengeluaran melampaui pemasukan. Tinjau biaya operasional dan tunda belanja yang belum mendesak.", Icons.Default.Lightbulb)
        SectionTitle("Transaksi terbaru", trailing = { TextButton(onClick = { onNavigate(Page.TRANSACTIONS) }) { Text("Lihat semua") } })
        entries.take(4).forEach { TransactionRow(it) }
    }
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 13.dp, horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(36.dp).background(tint.copy(alpha = .11f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
@Composable
private fun QuickTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Brand); Spacer(Modifier.height(5.dp)); Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
@Composable
private fun InsightCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).background(Color(0xFFFFF5E6), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Gold, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(body, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun TransactionRow(entry: MoneyEntry, onDelete: (() -> Unit)? = null) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background((if (entry.income) Green else Red).copy(.11f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(if (entry.income) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, tint = if (entry.income) Green else Red)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entry.category} · ${entry.date}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (entry.income) "+" else "−") +
                        if (entry.currency == "IDR") rupiah(entry.amount)
                        else "${entry.currency} ${entry.originalAmount.toLong()} · ≈ ${rupiah(entry.amount)}",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (entry.income) Green else Red,
                    maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                if (onDelete != null) TextButton(onClick = onDelete, contentPadding = PaddingValues(0.dp)) { Text("Hapus", fontSize = 9.sp, color = Red) }
            }
        }
    }
}

@Composable
private fun TransactionsScreen(
    entries: List<MoneyEntry>,
    onAdd: () -> Unit,
    onDelete: (Long) -> Unit,
    onPrint: () -> Unit,
    onSavePdf: () -> Unit,
    onSaveCsv: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Semua") }
    val filtered = entries.filter {
        (query.isBlank() || it.title.contains(query, true) || it.category.contains(query, true)) &&
            (filter == "Semua" || (filter == "Pemasukan" && it.income) || (filter == "Pengeluaran" && !it.income))
    }
    ScreenColumn {
        SectionTitle("Buku transaksi", "Semua arus kas bisnis", trailing = {
            Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)); Text("Catat", fontSize = 11.sp) }
        })
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Cari transaksi...") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Semua", "Pemasukan", "Pengeluaran").forEach { FilterChip(selected = filter == it, onClick = { filter = it }, label = { Text(it, fontSize = 10.sp) }) }
        }
        val visibleIncome = filtered.filter { it.income }.sumOf { it.amount }
        val visibleExpense = filtered.filterNot { it.income }.sumOf { it.amount }
        Card(shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${filtered.size} transaksi · sesuai filter", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Pemasukan", color = Green, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        Text(rupiah(visibleIncome), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Pengeluaran", color = Red, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        Text(rupiah(visibleExpense), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Saldo bersih", color = Brand, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        Text(rupiah(visibleIncome - visibleExpense), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onPrint, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp)) {
                Icon(Icons.Default.Print, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Cetak", fontSize = 9.sp)
            }
            OutlinedButton(onClick = onSavePdf, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp)) {
                Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("PDF", fontSize = 9.sp)
            }
            OutlinedButton(onClick = onSaveCsv, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp)) {
                Icon(Icons.Default.TableView, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("CSV", fontSize = 9.sp)
            }
        }
        filtered.forEach { TransactionRow(it) }
        if (filtered.isEmpty()) Text("Belum ada transaksi pada filter ini.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Laporan PDF memuat seluruh transaksi tersimpan. CSV dapat dibuka di Excel atau Google Sheets.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnalyticsScreen(entries: List<MoneyEntry>, income: Long, expense: Long) {
    val margin = if (income > 0) ((income - expense).toDouble() / income * 100).toInt() else 0
    ScreenColumn {
        SectionTitle("Growth Monitor", "Analisis kesehatan finansial usaha")
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricCard("Pemasukan", rupiah(income), Icons.Default.TrendingUp, Green, Modifier.weight(1f))
            MetricCard("Pengeluaran", rupiah(expense), Icons.Default.TrendingDown, Red, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricCard("Saldo bersih", rupiah(income - expense), Icons.Default.AccountBalanceWallet, Brand, Modifier.weight(1f))
            MetricCard("Profit margin", "$margin%", Icons.Default.Percent, Gold, Modifier.weight(1f))
        }
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Distribusi arus kas", "Pemasukan dan pengeluaran")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(13.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Box(Modifier.weight(income.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(Green, CircleShape))
                    Box(Modifier.weight(expense.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(Red, CircleShape))
                }
                Spacer(Modifier.height(12.dp)); LegendDot("Pemasukan", Green, rupiah(income)); LegendDot("Pengeluaran", Red, rupiah(expense))
            }
        }
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Cashflow candle", "Perubahan saldo kas harian · 7 hari terakhir")
                CashflowCandleChart(entries)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot("Saldo naik", Green, "")
                    LegendDot("Saldo turun", Red, "")
                }
            }
        }
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Ringkasan kategori", "Total pengeluaran per kategori")
                val grouped = entries.filter { !it.income }.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.amount } }
                val max = (grouped.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
                grouped.forEach { (name, amount) ->
                    Column(Modifier.padding(top = 13.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, fontSize = 11.sp); Text(rupiah(amount), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        LinearProgressIndicator(progress = { (amount.toFloat() / max).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp), color = Brand, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
        InsightCard("Catatan analitik", "Grafik historis harian dan bulanan akan disempurnakan setelah penyimpanan data dan filter periode diimplementasikan.", Icons.Default.Info)
    }
}
@Composable
private fun CashflowCandleChart(entries: List<MoneyEntry>) {
    val today = java.time.LocalDate.now()
    val start = today.minusDays(6)
    val format = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("id", "ID"))
    fun dateOf(entry: MoneyEntry) = runCatching { java.time.LocalDate.parse(entry.date, format) }.getOrNull()
    val baseline = entries.filter { (dateOf(it) ?: today).isBefore(start) }
        .sumOf { if (it.income) it.amount else -it.amount }
    val days = (0..6).map { start.plusDays(it.toLong()) }
    var running = baseline
    val candles = days.map { day ->
        val open = running
        var high = open
        var low = open
        entries.filter { dateOf(it) == day }.sortedBy { it.id }.forEach { entry ->
            running += if (entry.income) entry.amount else -entry.amount
            high = maxOf(high, running)
            low = minOf(low, running)
        }
        Triple(open, high to low, running)
    }
    val minValue = minOf(0L, candles.minOfOrNull { it.second.second } ?: 0L)
    val maxValue = maxOf(1L, candles.maxOfOrNull { it.second.first } ?: 1L)
    val chartGridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(155.dp)) {
        val top = 8f
        val bottom = size.height - 8f
        val plotHeight = (bottom - top).coerceAtLeast(1f)
        val span = (maxValue - minValue).toDouble().coerceAtLeast(1.0)
        fun y(value: Long): Float = bottom - (((value - minValue).toDouble() / span) * plotHeight).toFloat()
        val column = size.width / 7f
        val candleWidth = (column * .38f).coerceAtLeast(5f)
        if (minValue <= 0 && maxValue >= 0) {
            drawLine(chartGridColor, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1f)
        }
        candles.forEachIndexed { index, candle ->
            val x = column * (index + .5f)
            val open = candle.first
            val high = candle.second.first
            val low = candle.second.second
            val close = candle.third
            val tint = if (close >= open) Green else Red
            drawLine(tint, Offset(x, y(high)), Offset(x, y(low)), strokeWidth = 2f)
            val rectTop = minOf(y(open), y(close))
            val rectBottom = maxOf(y(open), y(close))
            val height = (rectBottom - rectTop).coerceAtLeast(3f)
            drawRect(
                color = tint,
                topLeft = Offset(x - candleWidth / 2f, rectTop),
                size = Size(candleWidth, height)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { index, day ->
            Text(if (index == 6) "Hari ini" else day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GoalsScreen(goals: List<SavingGoal>, onAdd: () -> Unit) {
    ScreenColumn {
        SectionTitle("Target usaha", "Rencanakan tujuan dan dana pertumbuhan", trailing = {
            Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)); Text("Target", fontSize = 11.sp) }
        })
        goals.forEach { goal ->
            val pct = if (goal.target > 0) goal.current.toFloat() / goal.target else 0f
            Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, null, tint = Brand); Spacer(Modifier.width(9.dp))
                        Text(goal.name, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${(pct * 100).toInt()}%", color = Brand, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(9.dp))
                    LinearProgressIndicator(progress = { pct.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Brand, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Terkumpul ${rupiah(goal.current)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Target ${rupiah(goal.target)}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Target waktu: ${goal.due}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal data class GoldZakatResult(
    val ownedGrams: Double,
    val buybackPricePerGram: Double,
    val nisabGrams: Double,
    val ownedValue: Long,
    val nisabValue: Long,
    val meetsNisab: Boolean,
    val haulComplete: Boolean,
    val eligible: Boolean,
    val zakatAmount: Long
)

internal object ZakatCalculator {
    fun evaluateGold(
        ownedGrams: Double,
        buybackPricePerGram: Double,
        haulComplete: Boolean,
        nisabGrams: Double = 85.0,
        rate: Double = 0.025
    ): GoldZakatResult {
        val grams = ownedGrams.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val price = buybackPricePerGram.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val safeNisab = nisabGrams.takeIf { it.isFinite() && it > 0.0 } ?: 85.0
        val safeRate = rate.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.025
        val ownedValue = (grams * price).takeIf { it.isFinite() }?.toLong() ?: 0L
        val nisabValue = (safeNisab * price).takeIf { it.isFinite() }?.toLong() ?: 0L
        val meetsNisab = grams >= safeNisab && price > 0
        val eligible = meetsNisab && haulComplete
        val zakat = if (eligible) (ownedValue * safeRate).toLong() else 0L
        return GoldZakatResult(
            ownedGrams = grams,
            buybackPricePerGram = price,
            nisabGrams = safeNisab,
            ownedValue = ownedValue,
            nisabValue = nisabValue,
            meetsNisab = meetsNisab,
            haulComplete = haulComplete,
            eligible = eligible,
            zakatAmount = zakat
        )
    }

    fun evaluateIncome(
        annualIncome: Long,
        annualNisab: Long = 91_681_728L,
        rate: Double = 0.025
    ): Pair<Boolean, Long> {
        val income = annualIncome.coerceAtLeast(0L)
        val eligible = income >= annualNisab && annualNisab > 0
        return eligible to if (eligible) (income * rate.coerceIn(0.0, 1.0)).toLong() else 0L
    }
}

@Composable
private fun ZakatScreen(entries: List<MoneyEntry>, market: MarketData?) {
    var mode by remember { mutableStateOf("emas") }
    val buybackPerGram = market?.antamBuybackIdrPerGram?.takeIf { it > 0 } ?: 0.0
    val retailBasePerGram = market?.antamRetailIdrPerGram?.takeIf { it > 0 } ?: 0.0
    val retailPerGram = market?.antamRetailInclTaxIdrPerGram?.takeIf { it > 0 } ?: retailBasePerGram
    val nisabValue = if (buybackPerGram > 0) (85.0 * buybackPerGram).toLong() else 0L

    val today = java.time.LocalDate.now()
    val year = today.year
    val monthStart = today.withDayOfMonth(1)
    val dateFormat = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("id", "ID"))
    val incomeEntries = entries.filter { it.income }
    val annualIncome = incomeEntries.filter {
        runCatching { java.time.LocalDate.parse(it.date, dateFormat).year == year }.getOrDefault(false)
    }.sumOf { it.amount }
    val monthlyIncome = incomeEntries.filter {
        runCatching {
            val date = java.time.LocalDate.parse(it.date, dateFormat)
            date.year == monthStart.year && date.month == monthStart.month
        }.getOrDefault(false)
    }.sumOf { it.amount }
    val monthlyIncomeNisab = 7_640_144L
    val annualIncomeNisab = 91_681_728L
    val monthlyIncomeEligible = monthlyIncome >= monthlyIncomeNisab
    val annualIncomeEligible = annualIncome >= annualIncomeNisab
    val incomeEligible = monthlyIncomeEligible || annualIncomeEligible
    val incomeZakat = when {
        monthlyIncomeEligible -> (monthlyIncome * 0.025).toLong()
        annualIncomeEligible -> (annualIncome * 0.025).toLong()
        else -> 0L
    }

    ScreenColumn {
        SectionTitle("Kalkulator Zakat", "Nisab emas dan zakat penghasilan")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "emas", onClick = { mode = "emas" }, label = { Text("Zakat emas") })
            FilterChip(selected = mode == "penghasilan", onClick = { mode = "penghasilan" }, label = { Text("Zakat penghasilan") })
        }

        if (mode == "emas") {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HARGA EMAS ANTAM", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    Text(
                        if (retailPerGram > 0) "${rupiah(retailPerGram.toLong())} / gram" else "Harga ritel belum tersedia",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (buybackPerGram > 0) "Buyback: ${rupiah(buybackPerGram.toLong())} / gram" else "Harga buyback belum tersedia",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (nisabValue > 0) "Nilai nisab 85 gram: ${rupiah(nisabValue)}" else "Nilai nisab menunggu harga",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp
                    )
                    Text(
                        when {
                            market == null -> "Harga ANTAM belum tersedia"
                            market.antamStale -> "Periksa tanggal harga · ${market.antamPriceDate}"
                            else -> "ANTAM · ${market.antamSourceUpdatedAt.ifBlank { market.antamPriceDate }}"
                        },
                        color = if (market?.antamStale == true) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
            }
            Text(
                "Nisab 85 gram · haul 1 tahun · kadar zakat 2,5%.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp
            )
        } else {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("BAZNAS 2026", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(rupiah(annualIncomeNisab), color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Nisab penghasilan per tahun · Rp7.640.144 per bulan", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Pemasukan bulan ini · ${today.month.getDisplayName(java.time.format.TextStyle.FULL, Locale("id", "ID"))}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(rupiah(monthlyIncome), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Nisab bulanan: ${rupiah(monthlyIncomeNisab)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Divider()
                    Text("Akumulasi pemasukan tahun $year", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(rupiah(annualIncome), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Nisab tahunan: ${rupiah(annualIncomeNisab)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = {
                            val ratio = if (monthlyIncomeEligible) monthlyIncome.toDouble() / monthlyIncomeNisab
                                else annualIncome.toDouble() / annualIncomeNisab
                            ratio.toFloat().coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (incomeEligible) Green else Brand
                    )
                    Text(
                        when {
                            monthlyIncomeEligible -> "Nisab bulanan terpenuhi. Estimasi zakat bulan ini (2,5%): ${rupiah(incomeZakat)}"
                            annualIncomeEligible -> "Nisab tahunan terpenuhi. Estimasi zakat tahunan (2,5%): ${rupiah(incomeZakat)}"
                            else -> "Belum mencapai nisab bulanan atau tahunan. Sisa menuju nisab bulanan: ${rupiah((monthlyIncomeNisab - monthlyIncome).coerceAtLeast(0L))}."
                        },
                        color = if (incomeEligible) Green else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Text("Tarif zakat penghasilan: 2,5%.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }
    }
}

private fun parseCurrencyAmount(raw: String): Double {
    val value = raw.trim().replace(" ", "")
    if (value.isBlank()) return 0.0
    val normalized = when {
        value.contains(".") && value.contains(",") -> value.replace(".", "").replace(",", ".")
        value.contains(",") -> {
            val decimals = value.substringAfterLast(",").length
            if (decimals in 1..2) value.replace(",", ".") else value.replace(",", "")
        }
        value.contains(".") -> {
            val decimals = value.substringAfterLast(".").length
            if (decimals == 3) value.replace(".", "") else value
        }
        else -> value
    }
    return normalized.toDoubleOrNull() ?: 0.0
}

private fun parseLong(s:String)=s.replace(Regex("[^0-9]"),"").toLongOrNull()?:0L

@Composable
private fun AssistantScreen(balance: Long, income: Long, expense: Long, entries: List<MoneyEntry>, business: String, cloudSession: CloudSession?) {
    var question by remember { mutableStateOf("") }
    var conversation by remember { mutableStateOf(listOf("Artha AI sedang menyiapkan analisis online...")) }
    var cloudBusy by remember { mutableStateOf(false) }
    var aiConnected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cloudSession?.userId) {
        val session = cloudSession
        if (session == null) {
            conversation = listOf("Masuk ke akun Arthanesia untuk menggunakan analisis AI online.")
            aiConnected = false
        } else {
            cloudBusy = true
            conversation = listOf("Menganalisis ringkasan keuangan usaha $business melalui AI cloud...")
            try {
                val answer = SupabaseApi.askAssistant(
                    session,
                    "Lakukan analisis otomatis kondisi keuangan usaha saya untuk bulan berjalan. Berikan ringkasan kesehatan usaha, temuan utama, risiko dengan levelnya, peluang perbaikan, dan tiga tindakan prioritas."
                )
                conversation = listOf(answer)
                aiConnected = true
            } catch (e: Exception) {
                conversation = listOf(e.message ?: "AI cloud belum dapat dihubungi. Periksa konfigurasi layanan AI dan koneksi internet.")
                aiConnected = false
            } finally {
                cloudBusy = false
            }
        }
    }

    fun askCloud(questionText: String) {
        val session = cloudSession ?: return
        val prompt = questionText.trim()
        if (prompt.isBlank() || cloudBusy) return
        question = ""
        conversation = conversation + prompt + "Artha AI sedang menganalisis data akun melalui layanan cloud..."
        cloudBusy = true
        scope.launch {
            try {
                val answer = SupabaseApi.askAssistant(session, prompt)
                conversation = conversation.dropLast(1) + answer
                aiConnected = true
            } catch (e: Exception) {
                conversation = conversation.dropLast(1) + (e.message ?: "AI online tidak tersedia. Silakan coba kembali.")
                aiConnected = false
            } finally {
                cloudBusy = false
            }
        }
    }

    ScreenColumn {
        SectionTitle("Artha AI", "Analisis otomatis berbasis cloud untuk $business")
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(Brand.copy(.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Artha AI · Gemini", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        when {
                            cloudBusy -> "Sedang menghubungkan dan menganalisis..."
                            aiConnected -> "Terhubung ke AI cloud"
                            cloudSession == null -> "Login diperlukan"
                            else -> "AI cloud belum tersambung"
                        },
                        fontSize = 9.sp, color = if (aiConnected) Green else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (cloudBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(if (aiConnected) Icons.Default.CloudDone else Icons.Default.CloudOff, null, tint = if (aiConnected) Green else Gold)
            }
        }
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                conversation.forEachIndexed { index, msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (index % 2 == 0) Arrangement.Start else Arrangement.End) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (index % 2 == 0) MaterialTheme.colorScheme.surfaceVariant else Brand.copy(.12f)
                        ) {
                            Text(msg, Modifier.widthIn(max = 310.dp).padding(12.dp), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Text("Analisis cepat", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        listOf(
            "Analisis kondisi arus kas dan risiko usaha saya.",
            "Apa kategori pengeluaran terbesar dan bagaimana menguranginya?",
            "Berikan tiga rekomendasi untuk meningkatkan margin usaha.",
            "Buat ringkasan kinerja keuangan bulan ini."
        ).forEach { suggestion ->
            AssistChip(
                onClick = { askCloud(suggestion) },
                enabled = cloudSession != null && !cloudBusy,
                label = { Text(suggestion, fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp)) }
            )
        }
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Tanyakan sesuatu tentang usaha Anda") },
            enabled = cloudSession != null && !cloudBusy,
            minLines = 2
        )
        Button(
            onClick = {
                val prompt = question.ifBlank { "Perbarui analisis otomatis kondisi keuangan usaha saya." }
                askCloud(prompt)
            },
            enabled = cloudSession != null && !cloudBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (cloudBusy) CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
            else { Icon(Icons.Default.Send, null); Spacer(Modifier.width(7.dp)); Text("Analisis dengan AI online") }
        }
        Text(
            "Analisis dilakukan secara online. Yang dikirim ke layanan AI hanya ringkasan angka dan agregat kategori transaksi milik akun Anda; deskripsi transaksi dan foto struk tidak dikirim. Jika layanan AI belum dikonfigurasi, aplikasi menampilkan pesan kendala dan tidak mengganti jawaban dengan analisis lokal.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp
        )
    }
}

private data class AchievementProgress(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val progress: Int,
    val target: Int
)

@Composable
private fun ProfileScreen(
    owner: String,
    business: String,
    currency: String,
    dark: Boolean,
    entries: List<MoneyEntry>,
    goals: List<SavingGoal>,
    onCurrency: (String) -> Unit,
    onTheme: () -> Unit,
    onLogout: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    ScreenColumn {
        SectionTitle("Profil & pengaturan", "Preferensi dan progres akun Arthanesia")
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                WalletLogo(52.dp); Spacer(Modifier.width(13.dp))
                Column {
                    Text(owner, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(business, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    Text("Pemilik usaha", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                }
            }
        }
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(15.dp)) {
                Text("Mata uang default pencatatan", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(currency); Icon(Icons.Default.ExpandMore, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf("IDR", "USD", "EUR", "GBP", "JPY").forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onCurrency(it); menu = false }) }
                    }
                }
                Text("Setiap transaksi menyimpan nominal asli dan kurs saat pencatatan, agar histori tidak berubah saat kurs pasar diperbarui.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Mode gelap", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(if (dark) "Tampilan gelap aktif" else "Tampilan terang aktif", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    }
                    Switch(checked = dark, onCheckedChange = { onTheme() })
                }
            }
        }
        AchievementsPanel(entries, goals)
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Tutup ruang kerja")
        }
    }
}

@Composable
private fun AchievementsPanel(entries: List<MoneyEntry>, goals: List<SavingGoal>) {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("id", "ID"))
    val today = java.time.LocalDate.now()
    val recentDays = entries.mapNotNull { entry ->
        runCatching { java.time.LocalDate.parse(entry.date, formatter) }.getOrNull()
    }.filter { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) }.distinct().size
    val categories = entries.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct().size
    val income = entries.filter { it.income }.sumOf { it.amount }
    val expense = entries.filter { !it.income }.sumOf { it.amount }

    val achievements = listOf(
        AchievementProgress("Langkah Pertama", "Catat transaksi pertamamu", Icons.Default.ReceiptLong, entries.size.coerceAtMost(1), 1),
        AchievementProgress("Pembukuan Konsisten", "Kumpulkan 10 catatan transaksi", Icons.Default.MenuBook, entries.size.coerceAtMost(10), 10),
        AchievementProgress("Kenali Pola Usaha", "Gunakan 4 kategori transaksi", Icons.Default.Insights, categories.coerceAtMost(4), 4),
        AchievementProgress("Punya Target", "Buat target keuangan pertama", Icons.Default.Flag, goals.size.coerceAtMost(1), 1),
        AchievementProgress("Arus Kas Positif", "Jaga pemasukan di atas pengeluaran", Icons.Default.AccountBalanceWallet, if (entries.size >= 5 && income > expense) 1 else 0, 1),
        AchievementProgress("Aktif Mingguan", "Catat transaksi pada 5 hari berbeda dalam 7 hari", Icons.Default.CalendarToday, recentDays.coerceAtMost(5), 5)
    )
    val unlocked = achievements.count { it.progress >= it.target }
    val xp = (entries.size * 10 + goals.size * 30 + unlocked * 25).coerceAtLeast(0)
    val level = xp / 250 + 1
    val progressXp = xp % 250

    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            SectionTitle("Pencapaian & pengalaman", "Progres dihitung dari aktivitas nyata akun Anda")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(Brand.copy(.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.EmojiEvents, null, tint = Gold, modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Level $level · Pengelola Usaha", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Text("$xp XP terkumpul · $unlocked dari ${achievements.size} badge terbuka", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("$progressXp/250 XP", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(progress = { progressXp / 250f }, modifier = Modifier.fillMaxWidth())
            achievements.forEach { achievement ->
                val earned = achievement.progress >= achievement.target
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(39.dp).background(
                            if (earned) Gold.copy(.14f) else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            achievement.icon, null,
                            tint = if (earned) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(achievement.title, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (earned) "TERBUKA" else "${achievement.progress}/${achievement.target}",
                                color = if (earned) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 8.sp, fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Text(achievement.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                        if (!earned) {
                            LinearProgressIndicator(
                                progress = { (achievement.progress.toFloat() / achievement.target.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
            Text("XP bertambah saat Anda mencatat transaksi dan membuat target. Badge akan terbuka otomatis ketika syarat aktivitas terpenuhi.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }
    }
}

private fun parseAmountFromText(text: String): Long? {
    val preferred = text.lines().filter { line ->
        Regex("(?i)(grand\\s*total|total|jumlah|amount\\s*due|tagihan)").containsMatchIn(line)
    }
    val source = if (preferred.isNotEmpty()) preferred.joinToString(" ") else text
    val matches = Regex("(?i)(?:rp\\.?\\s*)?([0-9][0-9.,]{2,})").findAll(source).toList()
    for (match in matches.asReversed()) {
        val normalized = match.groupValues[1].replace(".", "").replace(",", "")
        val value = normalized.toLongOrNull()
        if (value != null && value > 0) return value
    }
    return null
}

@Composable
private fun EntryMethodDialog(onDismiss: () -> Unit, onManual: () -> Unit, onReceipt: () -> Unit, onVoice: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catat transaksi", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pilih cara tercepat. Arthanesia akan mengisi nominal, deskripsi, dan kategori sebagai saran untuk diperiksa.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                FilledTonalButton(onClick = onReceipt, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ReceiptLong, null); Spacer(Modifier.width(9.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("Pindai foto struk", fontWeight = FontWeight.SemiBold)
                        Text("Baca item, total, dan kategori · tanpa menyimpan foto", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FilledTonalButton(onClick = onVoice, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(9.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("VoiceCash", fontWeight = FontWeight.SemiBold)
                        Text("Contoh: Pengeluaran 5.000 beli bahan baku", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(9.dp)); Text("Isi manual")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

@Composable
private fun TransactionDialog(
    initialIncome: Boolean,
    initialDescription: String,
    initialAmount: String,
    initialCategory: String,
    defaultCurrency: String,
    marketData: MarketData?,
    fromReceipt: Boolean,
    onDismiss: () -> Unit,
    onSave: (MoneyEntry) -> Unit
) {
    var income by remember { mutableStateOf(initialIncome) }
    var title by remember { mutableStateOf(initialDescription) }
    var amount by remember { mutableStateOf(initialAmount) }
    var category by remember { mutableStateOf(initialCategory) }
    var selectedCurrency by remember { mutableStateOf(defaultCurrency) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val currencies = listOf("IDR", "USD", "EUR", "GBP", "JPY", "SGD", "AUD", "MYR")
    val unitsPerIdr = marketData?.ratesPerIdr?.get(selectedCurrency) ?: if (selectedCurrency == "IDR") 1.0 else 0.0
    val rateToIdr = if (selectedCurrency == "IDR") 1.0 else if (unitsPerIdr > 0) 1.0 / unitsPerIdr else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catat transaksi", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (fromReceipt) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("HASIL PINDAI STRUK", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            if (amount.isNotBlank()) {
                                Text("Total pembayaran terdeteksi", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(
                                    amount.toLongOrNull()?.let(::rupiah) ?: "Periksa nominal",
                                    fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text("Pastikan sama dengan baris TOTAL BAYAR sebelum menyimpan.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            } else {
                                Text("Nominal total belum terbaca", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Isi jumlah yang tertera pada TOTAL / TOTAL BAYAR di struk.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = income, onClick = { income = true }, label = { Text("Pemasukan") })
                    FilterChip(selected = !income, onClick = { income = false }, label = { Text("Pengeluaran") })
                }
                OutlinedTextField(title, { title = it }, label = { Text("Deskripsi singkat") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Box {
                    OutlinedButton(onClick = { currencyExpanded = true }) {
                        Text("Mata uang: $selectedCurrency")
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                        currencies.forEach {
                            DropdownMenuItem(
                                text = { Text(it) },
                                onClick = { selectedCurrency = it; currencyExpanded = false; error = "" }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    amount,
                    { amount = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }; error = "" },
                    label = { Text(if (fromReceipt) "Total pembayaran ($selectedCurrency)" else "Nominal $selectedCurrency") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                if (selectedCurrency != "IDR") {
                    Text(
                        if (rateToIdr > 0) {
                            val sourceLabel = if (marketData?.fxSource == "exchangerate.dev") "Kurs indikatif intraday" else "Kurs referensi harian"
                            val stamp = marketData?.fxUpdatedAt?.let(::marketTimestamp)
                            val delay = marketData?.fxDelaySeconds?.takeIf { it > 0 }?.let { " · jeda ±${(it + 59) / 60} menit" }.orEmpty()
                            "1 $selectedCurrency ≈ ${rupiah(rateToIdr.toLong())} · $sourceLabel${stamp?.let { " · $it" } ?: ""}$delay"
                        } else "Memuat kurs terbaru. Transaksi mata uang asing belum dapat disimpan.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp
                    )
                }
                Box {
                    OutlinedButton(onClick = { categoryExpanded = true }) { Text(category); Icon(Icons.Default.ExpandMore, null) }
                    DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        listOf("Penjualan", "Pendapatan Jasa", "Bahan Baku", "Operasional", "Perlengkapan", "Transportasi", "Utilitas", "Marketing", "Gaji", "Investasi", "Lainnya").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { category = it; categoryExpanded = false })
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = Red, fontSize = 10.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                val original = parseCurrencyAmount(amount)
                if (title.isBlank() || original == null || original <= 0) {
                    error = "Isi deskripsi dan nominal yang valid."
                    return@Button
                }
                if (rateToIdr <= 0) {
                    error = "Kurs $selectedCurrency belum tersedia. Coba lagi saat tersambung internet."
                    return@Button
                }
                val baseIdr = (original * rateToIdr).toLong()
                if (baseIdr <= 0) {
                    error = "Hasil konversi tidak valid."
                    return@Button
                }
                onSave(
                    MoneyEntry(
                        title = title,
                        category = category,
                        amount = baseIdr,
                        income = income,
                        currency = selectedCurrency,
                        originalAmount = original,
                        fxRateToIdr = rateToIdr
                    )
                )
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun GoalDialog(onDismiss: () -> Unit, onSave: (SavingGoal) -> Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Buat target usaha") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nama target") }, singleLine = true)
                OutlinedTextField(target, { target = it }, label = { Text("Nominal target") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(current, { current = it }, label = { Text("Sudah terkumpul") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                val t = target.toLongOrNull()
                if (name.isNotBlank() && t != null && t > 0) onSave(SavingGoal(name, current.toLongOrNull() ?: 0L, t, "Belum ditentukan"))
            }) { Text("Simpan target") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun TaxScreen(entries: List<MoneyEntry>) {
    val year = java.time.LocalDate.now().year
    val annualGross = entries.filter { it.income && it.date.contains(year.toString()) }.sumOf { it.amount }
    var deductions by remember { mutableStateOf("") }
    val net = (annualGross - parseLong(deductions)).coerceAtLeast(0L)
    val ptkp = 54_000_000L
    val pkp = (net - ptkp).coerceAtLeast(0L)
    val tax = annualPph(pkp)
    ScreenColumn {
        SectionTitle("Pajak Penghasilan", "Tahun ${year} · otomatis berganti pada 1 Januari")
        Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("Bruto tahunan tercatat",fontWeight=FontWeight.Bold)
                Text(rupiah(annualGross),fontSize=20.sp,fontWeight=FontWeight.ExtraBold)
                OutlinedTextField(deductions,{deductions=it},label={Text("Pengurang yang memenuhi syarat")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                Text("Penghasilan bersih = bruto − pengurang",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rupiah(net),fontSize=18.sp,fontWeight=FontWeight.ExtraBold)
                Text("PTKP default ${rupiah(ptkp)} · PKP ${rupiah(pkp)}",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Estimasi PPh tahunan ${rupiah(tax)}",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Brand)
            }
        }
        Text("Arthanesia memakai penghasilan bersih tahunan sebagai dasar estimasi PKP setelah PTKP. Untuk PPh 21 karyawan, pemotongan bulanan memiliki mekanisme TER dan rekonsiliasi tahunan.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Transaksi tahun sebelumnya tidak dihapus; ketika tahun berganti, perhitungan periode aktif otomatis dimulai dari transaksi tahun baru.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private fun annualPph(pkp:Long):Long {
    var r=pkp; var out=0.0
    val bands=listOf(60_000_000L to .05,190_000_000L to .15,250_000_000L to .25,4_500_000_000L to .30,Long.MAX_VALUE to .35)
    for((cap,rate) in bands){if(r<=0)break;val part=min(r,cap);out+=part*rate;r-=part}
    return out.toLong()
}
