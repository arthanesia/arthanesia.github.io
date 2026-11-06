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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.min

private val Brand = Color(0xFF5B55ED)
private val BrandDark = Color(0xFF302B9A)
private val Muted = Color(0xFF858DA7)
private val Green = Color(0xFF159B72)
private val Red = Color(0xFFDB6475)
private val Gold = Color(0xFFC58B34)
private fun rupiah(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale("id", "ID")).format(value).replace(",00", "")

internal data class MoneyEntry(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val category: String,
    val amount: Long,
    val income: Boolean,
    val date: String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("id", "ID")))
)
private data class SavingGoal(val name: String, val current: Long, val target: Long, val due: String)
private enum class Page(val label: String) {
    HOME("Beranda"), TRANSACTIONS("Transaksi"), ANALYTICS("Analitik"),
    GOALS("Target"), ZAKAT("Zakat"), TAX("Pajak"), ASSISTANT("Artha AI"), PROFILE("Profil")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(246, 247, 252)
        window.navigationBarColor = android.graphics.Color.rgb(246, 247, 252)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { ArthanesiaApp() }
    }
}

@Composable
private fun ArthanesiaApp() {
    var loggedIn by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(Page.HOME) }
    var owner by remember { mutableStateOf("Pemilik Usaha") }
    var business by remember { mutableStateOf("Bisnis Saya") }
    var currency by remember { mutableStateOf("IDR") }
    var entries by remember {
        mutableStateOf(listOf(
            MoneyEntry(1, "Penjualan harian", "Penjualan", 3250000, true, "05 Nov 2026"),
            MoneyEntry(2, "Bahan baku kopi", "Operasional", 1250000, false, "04 Nov 2026"),
            MoneyEntry(3, "Pesanan katering", "Penjualan", 4800000, true, "03 Nov 2026"),
            MoneyEntry(4, "Promosi Instagram", "Marketing", 650000, false, "02 Nov 2026"),
            MoneyEntry(5, "Listrik & air", "Operasional", 475000, false, "01 Nov 2026")
        ))
    }
    var goals by remember {
        mutableStateOf(listOf(
            SavingGoal("Mesin espresso", 12500000, 20000000, "Desember 2026"),
            SavingGoal("Dana darurat usaha", 2450000, 12000000, "Maret 2027")
        ))
    }
    var showTransaction by remember { mutableStateOf(false) }
    var showEntryChooser by remember { mutableStateOf(false) }
    var initialIncome by remember { mutableStateOf(true) }
    var showGoal by remember { mutableStateOf(false) }
    var voiceDraft by remember { mutableStateOf("") }
    var parsedAmount by remember { mutableStateOf("") }
    val context = LocalContext.current
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
        primary = Color(0xFF8E89FF), secondary = Color(0xFF75D6B4),
        background = Color(0xFF111426), surface = Color(0xFF191D32),
        onBackground = Color(0xFFF0F1FB), onSurface = Color(0xFFF0F1FB),
        surfaceVariant = Color(0xFF252A43), onSurfaceVariant = Color(0xFFB2B8D0)
    ) else lightColorScheme(
        primary = Brand, secondary = Green, background = Color(0xFFF6F7FC),
        surface = Color.White, onBackground = Color(0xFF202440),
        onSurface = Color(0xFF202440), surfaceVariant = Color(0xFFF0F1F8),
        onSurfaceVariant = Muted
    )

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            initialIncome = !(spoken.contains("pengeluaran", true) || spoken.contains("beli", true) || spoken.contains("bayar", true) || spoken.contains("belanja", true))
            voiceDraft = spoken
            parsedAmount = parseAmountFromText(spoken)?.toString() ?: ""
            showTransaction = true
        }
    }
    val receiptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching { InputImage.fromFilePath(context, uri) }
                .onSuccess { image ->
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                        .addOnSuccessListener { result ->
                            val lines = result.text.lines().filter { it.isNotBlank() }
                            voiceDraft = lines.take(3).joinToString(" ").take(100).ifBlank { "Pembelian dari struk" }
                            parsedAmount = parseAmountFromText(result.text)?.toString() ?: ""
                            initialIncome = false
                            showTransaction = true
                            if (parsedAmount.isBlank()) Toast.makeText(context, "Teks terbaca. Masukkan nominal secara manual.", Toast.LENGTH_LONG).show()
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
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!loggedIn) {
                LoginScreen(
                    onDemo = { owner = "Aero Pratama"; business = "Kopi Senja"; loggedIn = true },
                    onContinue = { name, biz ->
                        owner = name.ifBlank { "Pemilik Usaha" }
                        business = biz.ifBlank { "Bisnis Saya" }
                        loggedIn = true
                    }
                )
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { AppTopBar(page, business, { dark = !dark }, dark) },
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
                                onAdd = { initialIncome = it; showEntryChooser = true; voiceDraft = ""; parsedAmount = "" },
                                onVoice = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                                onNavigate = { page = it })
                            Page.TRANSACTIONS -> TransactionsScreen(
                                entries = entries,
                                onAdd = { initialIncome = true; showEntryChooser = true; voiceDraft = ""; parsedAmount = "" },
                                onDelete = { id -> entries = entries.filterNot { it.id == id } },
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
                            Page.ZAKAT -> ZakatScreen()
                            Page.TAX -> TaxScreen(entries)
                            Page.ASSISTANT -> AssistantScreen(balance, incomeTotal, expenseTotal, entries, business)
                            Page.PROFILE -> ProfileScreen(owner, business, currency, dark,
                                onCurrency = { currency = it }, onTheme = { dark = !dark },
                                onLogout = { loggedIn = false; page = Page.HOME })
                        }
                    }
                }
            }
        }
    }
    if (showEntryChooser) {
        EntryMethodDialog(
            onDismiss = { showEntryChooser = false },
            onManual = { showEntryChooser = false; voiceDraft = ""; parsedAmount = ""; showTransaction = true },
            onReceipt = { showEntryChooser = false; receiptLauncher.launch("image/*") },
            onVoice = { showEntryChooser = false; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        )
    }
    if (showTransaction) {
        TransactionDialog(initialIncome, voiceDraft, parsedAmount,
            onDismiss = { showTransaction = false; voiceDraft = ""; parsedAmount = "" }) {
            entries = listOf(it) + entries
            showTransaction = false
            voiceDraft = ""
            parsedAmount = ""
        }
    }
    if (showGoal) GoalDialog(onDismiss = { showGoal = false }) { goals = goals + it; showGoal = false }
}

@Composable
private fun LoginScreen(onDemo: () -> Unit, onContinue: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var business by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFFF0EFFF), Color(0xFFFAFBFF)))
    )) {
        val compact = maxWidth < 600.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 24.dp else 72.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            WalletLogo(if (compact) 82.dp else 100.dp)
            Spacer(Modifier.height(16.dp))
            Text("Arthanesia", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = BrandDark)
            Text("KELOLA  ·  RENCANAKAN  ·  TUMBUH", fontSize = 9.sp, letterSpacing = 2.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Text("Smart Financial Planner untuk UMKM Indonesia", fontSize = 14.sp, color = Color(0xFF202440), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            Card(Modifier.fillMaxWidth().widthIn(max = 440.dp), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(8.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (register) "Buat profil Arthanesia" else "Selamat datang kembali", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202440))
                    Text(if (register) "Mulai perjalanan finansial bisnismu." else "Masuk untuk melanjutkan pengelolaan usaha.", fontSize = 12.sp, color = Muted)
                    if (register) {
                        OutlinedTextField(name, { name = it }, label = { Text("Nama pemilik") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(business, { business = it }, label = { Text("Nama usaha") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Button(onClick = onDemo, modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(13.dp)) {
                        Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(8.dp)); Text("Lanjutkan dengan akun demo")
                    }
                    OutlinedButton(onClick = { register = !register }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(13.dp)) {
                        Text(if (register) "Kembali ke halaman masuk" else "Buat profil lokal")
                    }
                    if (register) TextButton(onClick = { onContinue(name, business) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Mulai aplikasi", fontWeight = FontWeight.Bold)
                    }
                    Text("Mode prototipe lokal · akun demo pada sesi perangkat", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun WalletLogo(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFF6D68FF), Color(0xFF4F49E8))), RoundedCornerShape(size * .27f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.AccountBalanceWallet, "Logo Arthanesia", tint = Color.White, modifier = Modifier.size(size * .47f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(page: Page, business: String, onTheme: () -> Unit, dark: Boolean) {
    TopAppBar(
        title = { Column { Text(page.label, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(business, fontSize = 10.sp, color = Muted) } },
        actions = {
            IconButton(onClick = onTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Ganti tema") }
            IconButton(onClick = {}) { Icon(Icons.Default.NotificationsNone, "Notifikasi") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 17.dp)
        .padding(top = 15.dp, bottom = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = Muted)
        }
        trailing?.invoke()
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f))
                Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeScreen(owner: String, business: String, balance: Long, income: Long, expense: Long,
    entries: List<MoneyEntry>, onAdd: (Boolean) -> Unit, onVoice: () -> Unit, onNavigate: (Page) -> Unit) {
    ScreenColumn {
        Column {
            Text("HALO, $owner 👋", fontSize = 10.sp, color = Brand, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Ringkasan keuanganmu", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("Pantau kondisi $business hari ini.", color = Muted, fontSize = 11.sp)
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = BrandDark)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Total saldo kas", color = Color.White.copy(.75f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White.copy(.8f))
                }
                Text(rupiah(balance), fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(17.dp))
                Row {
                    Column(Modifier.weight(1f)) { Text("PEMASUKAN", color = Color(0xFF9BE8CB), fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(rupiah(income), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    Column(Modifier.weight(1f)) { Text("PENGELUARAN", color = Color(0xFFFFB3C0), fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(rupiah(expense), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction("Catat", Icons.Default.Add, Brand, Modifier.weight(1f)) { onAdd(true) }
            QuickAction("VoiceCash", Icons.Default.Mic, Color(0xFF8A63D2), Modifier.weight(1f), onVoice)
            QuickAction("Analitik", Icons.Default.Insights, Green, Modifier.weight(1f)) { onNavigate(Page.ANALYTICS) }
            QuickAction("Tanya AI", Icons.Default.AutoAwesome, Gold, Modifier.weight(1f)) { onNavigate(Page.ASSISTANT) }
        }
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("ArthaScore", "Skor kesehatan finansial dari catatan")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("82", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = Brand)
                    Spacer(Modifier.width(8.dp))
                    Column { Text("Kondisi sehat", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Jaga keseimbangan arus kas.", fontSize = 10.sp, color = Muted) }
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
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
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
    Card(shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Icon(icon, null, tint = Gold)
            Column { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(body, fontSize = 10.sp, color = Muted, lineHeight = 16.sp) }
        }
    }
}

@Composable
private fun TransactionRow(entry: MoneyEntry, onDelete: (() -> Unit)? = null) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(37.dp).background((if (entry.income) Green else Red).copy(.12f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(if (entry.income) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, tint = if (entry.income) Green else Red)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entry.category} · ${entry.date}", fontSize = 9.sp, color = Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (entry.income) "+" else "−") + rupiah(entry.amount), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (entry.income) Green else Red)
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
                Text("${filtered.size} transaksi · sesuai filter", color = Muted, fontSize = 10.sp)
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
        if (filtered.isEmpty()) Text("Belum ada transaksi pada filter ini.", color = Muted)
        Text("Laporan PDF memuat seluruh transaksi tersimpan. CSV dapat dibuka di Excel atau Google Sheets.", fontSize = 9.sp, color = Muted)
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
                        Text("Terkumpul ${rupiah(goal.current)}", fontSize = 10.sp, color = Muted)
                        Text("Target ${rupiah(goal.target)}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Target waktu: ${goal.due}", fontSize = 9.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun ZakatScreen() {
    var goldIdrPerGram by remember { mutableStateOf(0.0) }
    var loading by remember { mutableStateOf(true) }
    var assets by remember { mutableStateOf("") }
    var debts by remember { mutableStateOf("") }
    var haul by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching {
            val fx = withContext(Dispatchers.IO) {
                val f = (URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection).apply { connectTimeout=8000; readTimeout=8000 }
                JSONObject(f.inputStream.bufferedReader().use { it.readText() })
            }
            val usdIdr = fx.getJSONObject("rates").optDouble("IDR", 0.0)
            val g = withContext(Dispatchers.IO) {
                val x = (URL("https://api.gold-api.com/price/XAU").openConnection() as HttpURLConnection).apply { connectTimeout=8000; readTimeout=8000 }
                JSONObject(x.inputStream.bufferedReader().use { it.readText() })
            }
            goldIdrPerGram = g.optDouble("price", 0.0) * usdIdr / 31.1034768
        }.also { loading=false }
    }
    val nisab = goldIdrPerGram * 250.0
    val netAssets = (parseLong(assets) - parseLong(debts)).coerceAtLeast(0L)
    val wajib = nisab > 0 && netAssets >= nisab && haul
    ScreenColumn {
        SectionTitle("Zakat Penghasilan", "Nisab 250 gram emas · periode tahunan")
        Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=BrandDark)) {
            Column(Modifier.padding(18.dp)) {
                Text("HARGA EMAS REFERENSI",color=Color.White.copy(.7f),fontSize=9.sp,fontWeight=FontWeight.Bold)
                Text(if(loading)"Memuat harga…" else "${goldIdrPerGram.let { rupiah(it.toLong()) }} / gram",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.ExtraBold)
                Text(if(loading)"Mengambil feed harga saat aplikasi dibuka." else "Nisab 250 g = ${rupiah(nisab.toLong())}",color=Color.White.copy(.75f),fontSize=10.sp)
            }
        }
        Card(shape=RoundedCornerShape(17.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(assets,{assets=it},label={Text("Aset yang menjadi dasar")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                OutlinedTextField(debts,{debts=it},label={Text("Kewajiban/utang jangka pendek")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(haul,{haul=it});Text("Sudah mencapai haul 1 tahun",fontSize=11.sp)}
                Text(if(wajib)"Status: WAJIB • estimasi 2,5% = ${rupiah((netAssets*.025).toLong())}" else "Status: belum memenuhi parameter nisab/haul",color=if(wajib)Green else Muted,fontWeight=FontWeight.Bold)
            }
        }
        Text("Harga emas menggunakan spot XAU sebagai referensi, bukan harga retail Antam. Feed FX pada implementasi ini adalah provider terbaru yang tersedia; bukan tick-by-tick.",fontSize=9.sp,color=Muted)
    }
}
private fun parseLong(s:String)=s.replace(Regex("[^0-9]"),"").toLongOrNull()?:0L

@Composable
private fun AssistantScreen(balance: Long, income: Long, expense: Long, entries: List<MoneyEntry>, business: String) {
    var question by remember { mutableStateOf("") }
    var conversation by remember { mutableStateOf(listOf("Halo! Saya Artha Assistant. Saya dapat menganalisis catatan keuangan $business yang tersedia di perangkat ini.")) }
    ScreenColumn {
        SectionTitle("Artha Assistant", "Analisis pembukuan lokal · offline")
        Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                conversation.forEachIndexed { index, msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (index % 2 == 0) Arrangement.Start else Arrangement.End) {
                        Surface(shape = RoundedCornerShape(14.dp), color = if (index % 2 == 0) MaterialTheme.colorScheme.surfaceVariant else Brand.copy(.12f)) {
                            Text(msg, Modifier.widthIn(max = 290.dp).padding(12.dp), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Text("Coba tanyakan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        listOf("Bagaimana kondisi arus kas saya?", "Apa pengeluaran terbesar?", "Bagaimana cara menghemat biaya?", "Buat ringkasan pembukuan").forEach {
            AssistChip(onClick = { question = it }, label = { Text(it, fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp)) })
        }
        OutlinedTextField(question, { question = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Tulis pertanyaan...") })
        Button(onClick = {
            if (question.isNotBlank()) {
                val q = question.trim()
                val expenseByCategory = entries.filterNot { it.income }
                    .groupBy { it.category }
                    .mapValues { (_, rows) -> rows.sumOf { it.amount } }
                    .toList().sortedByDescending { it.second }
                val incomeByCategory = entries.filter { it.income }
                    .groupBy { it.category }
                    .mapValues { (_, rows) -> rows.sumOf { it.amount } }
                    .toList().sortedByDescending { it.second }
                val answer = when {
                    q.contains("arus kas", true) || q.contains("cash flow", true) ->
                        "Dari ${entries.size} transaksi yang tersedia, pemasukan ${rupiah(income)}, pengeluaran ${rupiah(expense)}, dan arus kas bersih ${rupiah(balance)}. ${if (balance >= 0) "Catatan menunjukkan arus kas positif." else "Catatan menunjukkan arus kas negatif; tinjau biaya yang bisa ditunda."}"
                    q.contains("terbesar", true) || q.contains("kategori", true) ->
                        expenseByCategory.firstOrNull()?.let { "Kategori pengeluaran tertinggi adalah ${it.first} sebesar ${rupiah(it.second)}. Periksa rincian transaksi dan bandingkan kebutuhan rutin sebelum menentukan pengurangan biaya." }
                            ?: "Belum ada pengeluaran yang tercatat untuk dianalisis."
                    q.contains("hemat", true) || q.contains("efisiensi", true) ->
                        expenseByCategory.take(3).takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.first}: ${rupiah(it.second)}" }?.let { "Tiga pos pengeluaran tertinggi saat ini: $it. Mulailah dengan meninjau biaya berulang, negosiasi pemasok, dan pembelian yang belum mendesak. Ini adalah area evaluasi, bukan jaminan penghematan." }
                            ?: "Tambahkan beberapa transaksi pengeluaran agar saya dapat menemukan kategori yang layak dievaluasi."
                    q.contains("pemasukan", true) || q.contains("pendapatan", true) || q.contains("penjualan", true) ->
                        incomeByCategory.take(3).takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.first}: ${rupiah(it.second)}" }?.let { "Pemasukan tercatat ${rupiah(income)}. Kontributor kategori teratas: $it." }
                            ?: "Belum ada pemasukan yang tercatat."
                    q.contains("margin", true) || q.contains("laba", true) || q.contains("profit", true) ->
                        if (income > 0) "Saldo bersih ${rupiah(balance)} dengan margin sederhana ${((balance.toDouble() / income) * 100).toInt()}%. Rasio ini hanya memakai total transaksi yang tersedia dan belum tentu sama dengan laba akuntansi."
                        else "Margin belum dapat dihitung karena belum ada pemasukan yang tercatat."
                    q.contains("laporan", true) || q.contains("ringkasan", true) ->
                        "Ringkasan: ${entries.size} transaksi, pemasukan ${rupiah(income)}, pengeluaran ${rupiah(expense)}, dan saldo bersih ${rupiah(balance)}. Buka Buku Transaksi untuk mencetak PDF atau mengekspor CSV."
                    q.contains("saldo", true) ->
                        "Saldo bersih dari catatan yang tersedia adalah ${rupiah(balance)}, dihitung dari pemasukan dikurangi pengeluaran."
                    q.contains("pajak", true) ->
                        "Estimasi pajak membutuhkan penghasilan bersih tahunan, status PTKP, pengurang yang memenuhi syarat, dan verifikasi aturan terbaru. Gunakan layar Pajak sebagai perkiraan awal, bukan pengganti pelaporan resmi."
                    else ->
                        "Saya bisa menganalisis arus kas, kategori pengeluaran, efisiensi, pemasukan, margin sederhana, laporan, dan dasar perhitungan pajak. Jawaban saat ini dihitung dari catatan di perangkat."
                }
                conversation = conversation + q + answer
                question = ""
            }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(7.dp)); Text("Kirim pertanyaan") }
        Text("Mode saat ini: analisis lokal tanpa mengirim transaksi ke pihak ketiga. Integrasi Gemini memerlukan project backend aktif dan persetujuan pengguna sebelum agregat keuangan dikirim.", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun ProfileScreen(owner: String, business: String, currency: String, dark: Boolean,
    onCurrency: (String) -> Unit, onTheme: () -> Unit, onLogout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ScreenColumn {
        SectionTitle("Profil & pengaturan", "Preferensi akun Arthanesia")
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = BrandDark)) {
            Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                WalletLogo(52.dp); Spacer(Modifier.width(13.dp))
                Column {
                    Text(owner, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(business, color = Color.White.copy(.7f), fontSize = 10.sp)
                    Text("Business owner · Demo", color = Color.White.copy(.75f), fontSize = 9.sp)
                }
            }
        }
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(15.dp)) {
                Text("Preferensi mata uang", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(currency); Icon(Icons.Default.ExpandMore, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf("IDR", "USD", "EUR", "GBP", "JPY").forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onCurrency(it); menu = false }) }
                    }
                }
                Text("Pilihan mata uang saat ini baru mengubah preferensi. Kurs live dan konversi belum diaktifkan.", color = Muted, fontSize = 10.sp)
            }
        }
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DarkMode, null, tint = Brand); Spacer(Modifier.width(10.dp))
                Text("Mode gelap", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Switch(checked = dark, onCheckedChange = { onTheme() })
            }
        }
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(15.dp)) {
                SectionTitle("Pencapaian bisnis", "Lencana demo")
                ProfileBadge("Raja Penjualan", "Mencatat pemasukan usaha", Icons.Default.EmojiEvents)
                ProfileBadge("Kolega AI", "Menggunakan Artha Assistant", Icons.Default.SmartToy)
                ProfileBadge("Pejuang Hemat", "Mengelola pengeluaran", Icons.Default.Savings)
            }
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Keluar dari demo") }
    }
}
@Composable
private fun ProfileBadge(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Gold); Spacer(Modifier.width(10.dp))
        Column { Text(title, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text(subtitle, color = Muted, fontSize = 9.sp) }
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
        title = { Text("Pilih metode pencatatan", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onReceipt, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ReceiptLong, null); Spacer(Modifier.width(9.dp)); Text("Foto Struk / OCR")
                }
                FilledTonalButton(onClick = onVoice, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(9.dp)); Text("Suara / VoiceCash")
                }
                OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(9.dp)); Text("Manual")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

@Composable
private fun TransactionDialog(initialIncome: Boolean, initialDescription: String, initialAmount: String, onDismiss: () -> Unit, onSave: (MoneyEntry) -> Unit) {
    var income by remember { mutableStateOf(initialIncome) }
    var title by remember { mutableStateOf(initialDescription) }
    var amount by remember { mutableStateOf(initialAmount) }
    var category by remember { mutableStateOf("Penjualan") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catat transaksi", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = income, onClick = { income = true }, label = { Text("Pemasukan") })
                    FilterChip(selected = !income, onClick = { income = false }, label = { Text("Pengeluaran") })
                }
                OutlinedTextField(title, { title = it }, label = { Text("Deskripsi") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Nominal IDR") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(category); Icon(Icons.Default.ExpandMore, null) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("Penjualan", "Operasional", "Marketing", "Transportasi", "Gaji", "Investasi", "Lainnya").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { category = it; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = amount.toLongOrNull()
                if (title.isNotBlank() && value != null && value > 0) onSave(MoneyEntry(title = title, category = category, amount = value, income = income))
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
                Text("Penghasilan bersih = bruto − pengurang",fontSize=9.sp,color=Muted)
                Text(rupiah(net),fontSize=18.sp,fontWeight=FontWeight.ExtraBold)
                Text("PTKP default ${rupiah(ptkp)} · PKP ${rupiah(pkp)}",fontSize=9.sp,color=Muted)
                Text("Estimasi PPh tahunan ${rupiah(tax)}",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Brand)
            }
        }
        Text("Arthanesia memakai penghasilan bersih tahunan sebagai dasar estimasi PKP setelah PTKP. Untuk PPh 21 karyawan, pemotongan bulanan memiliki mekanisme TER dan rekonsiliasi tahunan.",fontSize=10.sp,color=Muted)
        Text("Transaksi tahun sebelumnya tidak dihapus; ketika tahun berganti, perhitungan periode aktif otomatis dimulai dari transaksi tahun baru.",fontSize=10.sp,color=Muted)
    }
}
private fun annualPph(pkp:Long):Long {
    var r=pkp; var out=0.0
    val bands=listOf(60_000_000L to .05,190_000_000L to .15,250_000_000L to .25,4_500_000_000L to .30,Long.MAX_VALUE to .35)
    for((cap,rate) in bands){if(r<=0)break;val part=min(r,cap);out+=part*rate;r-=part}
    return out.toLong()
}
