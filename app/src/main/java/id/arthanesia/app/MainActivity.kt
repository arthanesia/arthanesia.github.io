package id.arthanesia.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale

private const val URL="https://ixihqkifpmxruvjyfnrk.supabase.co"
private const val KEY="sb_publishable_-kbqcW7De6AgeVLgBkTaPg_iD1EicRt"
private val P=Color(0xFF5B55DF); private val BG=Color(0xFFF6F7FB); private val INK=Color(0xFF181B2F); private val G=Color(0xFF148C68); private val R=Color(0xFFC9576E); private val GOLD=Color(0xFFB57A21)

data class Tx(val id:String,val type:String,val description:String,val category:String,val amount:Double,val base:Double)

class Api(c:Context){
 private val http=OkHttpClient(); private val pref=c.getSharedPreferences("arthanesia",0)
 var token:String? get()=pref.getString("token",null) private set(v){pref.edit().putString("token",v).apply()}
 var uid:String? get()=pref.getString("uid",null) private set(v){pref.edit().putString("uid",v).apply()}
 var email:String? get()=pref.getString("email",null) private set(v){pref.edit().putString("email",v).apply()}
 fun logged()=token!=null
 fun logout(){pref.edit().clear().apply()}
 private fun call(path:String,method:String="GET",body:String?=null,auth:Boolean=true):String{
  val b=Request.Builder().url(URL+path).addHeader("apikey",KEY).addHeader("Content-Type","application/json")
  if(auth&&token!=null)b.addHeader("Authorization","Bearer $token")
  val req=when(method){"POST"->b.post(body!!.toRequestBody("application/json".toMediaType())).build();else->b.get().build()}
  http.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(!r.isSuccessful)throw IOException(t.ifBlank{"Permintaan gagal"});return t}
 }
 fun login(e:String,p:String){val j=Json.parseToJsonElement(call("/auth/v1/token?grant_type=password","POST",buildJsonObject{"email" to JsonPrimitive(e);"password" to JsonPrimitive(p)}.toString(),false)).jsonObject;token=j["access_token"]?.jsonPrimitive?.content;uid=j["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content;email=e;if(token==null)throw IOException("Login gagal")}
 fun signup(e:String,p:String,n:String,b:String){val body=buildJsonObject{"email" to JsonPrimitive(e);"password" to JsonPrimitive(p);"data" to buildJsonObject{"full_name" to JsonPrimitive(n);"business_name" to JsonPrimitive(b)}}.toString();val j=Json.parseToJsonElement(call("/auth/v1/signup","POST",body,false)).jsonObject;if(j["access_token"]==null)throw IOException("Akun dibuat. Verifikasi email lalu masuk.");token=j["access_token"]!!.jsonPrimitive.content;uid=j["user"]!!.jsonObject["id"]!!.jsonPrimitive.content;email=e}
 fun tx():List<Tx>{val a=Json.parseToJsonElement(call("/rest/v1/transactions?select=id,type,description,category,amount,amount_base&order=occurred_at.desc&limit=500")).jsonArray;return a.map{val x=it.jsonObject;Tx(x["id"]!!.jsonPrimitive.content,x["type"]!!.jsonPrimitive.content,x["description"]?.jsonPrimitive?.content.orEmpty(),x["category"]?.jsonPrimitive?.content.orEmpty(),x["amount"]?.jsonPrimitive?.doubleOrNull?:0.0,x["amount_base"]?.jsonPrimitive?.doubleOrNull?:0.0)}}
 fun add(type:String,amount:Double,cat:String,desc:String){call("/rest/v1/transactions","POST",buildJsonObject{"user_id" to JsonPrimitive(uid);"client_ref" to JsonPrimitive(java.util.UUID.randomUUID().toString());"type" to JsonPrimitive(type);"amount" to JsonPrimitive(amount);"currency" to JsonPrimitive("IDR");"amount_base" to JsonPrimitive(amount);"base_currency" to JsonPrimitive("IDR");"exchange_rate" to JsonPrimitive(1);"category" to JsonPrimitive(cat);"description" to JsonPrimitive(desc);"occurred_at" to JsonPrimitive(java.time.OffsetDateTime.now().toString());"source" to JsonPrimitive("manual")}.toString())}
 fun market():JsonObject=Json.parseToJsonElement(call("/functions/v1/market-data",auth=false)).jsonObject
}
class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App(this)}}}
@Composable fun App(c:Context){val api=remember{Api(c)};var ok by remember{mutableStateOf(api.logged())};MaterialTheme(colorScheme=lightColorScheme(primary=P,background=BG)){if(ok)HomeApp(api){api.logout();ok=false}else Login(api){ok=true}}}
@Composable fun Login(api:Api,done:()->Unit){var reg by remember{mutableStateOf(false)};var e by remember{mutableStateOf("")};var p by remember{mutableStateOf("")};var n by remember{mutableStateOf("")};var b by remember{mutableStateOf("")};var err by remember{mutableStateOf("")};Column(Modifier.fillMaxSize().background(BG).padding(24.dp),verticalArrangement=Arrangement.Center){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Arthanesia",fontSize=30.sp,fontWeight=FontWeight.Black,color=INK);Text(if(reg)"Buat akun":"Masuk",fontWeight=FontWeight.Bold);if(reg){Field("Nama pemilik",n){n=it};Field("Nama usaha",b){b=it}};Field("Email",e){e=it};Field("Password",p,true){p=it};if(err.isNotBlank())Text(err,color=R,fontSize=12.sp);Button(onClick={Thread{try{if(reg)api.signup(e,p,n,b)else api.login(e,p);done()}catch(x:Exception){err=x.message?:"Gagal"}}},Modifier.fillMaxWidth()){Text(if(reg)"Daftar" else "Masuk")};TextButton(onClick={reg=!reg}){Text(if(reg)"Sudah punya akun? Masuk" else "Buat akun")}}}}}
@Composable fun Field(l:String,v:String,pw:Boolean=false,set:(String)->Unit){OutlinedTextField(v,set,label={Text(l)},singleLine=true,visualTransformation=if(pw)PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,modifier=Modifier.fillMaxWidth())}
@Composable fun HomeApp(api:Api,out:()->Unit){var tab by remember{mutableStateOf(0)};var tx by remember{mutableStateOf(emptyList<Tx>())};var market by remember{mutableStateOf<JsonObject?>(null)};LaunchedEffect(Unit){Thread{try{tx=api.tx();market=api.market()}catch(_:Exception){}}};Scaffold(topBar={TopAppBar(title={Text("Arthanesia",fontWeight=FontWeight.Black)},actions={TextButton(onClick=out){Text("Keluar")}})},bottomBar={NavigationBar{val n=listOf("Ringkasan","Transaksi","Pasar");val i=listOf(Icons.Default.Home,Icons.Default.List,Icons.Default.TrendingUp);n.forEachIndexed{k,v->NavigationBarItem(tab==k,{tab=k},{Icon(i[k],null)},{Text(v,fontSize=9.sp)})}}}){pad->Box(Modifier.fillMaxSize().background(BG).padding(pad).padding(16.dp)){when(tab){0->Summary(tx,market);1->Transactions(api,tx){tx=api.tx()};2->Market(market)}}}}
@Composable fun Summary(tx:List<Tx>,m:JsonObject?){val income=tx.filter{it.type=="income"}.sumOf{it.base};val expense=tx.filter{it.type=="expense"}.sumOf{it.base};LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Ringkasan",fontSize=27.sp,fontWeight=FontWeight.Black,color=INK);Text("Saldo bersih",fontSize=10.sp,color=Color.Gray);Text(rp(income-expense),fontSize=31.sp,fontWeight=FontWeight.Black)};item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("Masuk",income,G);Metric("Keluar",expense,R)}};item{MarketMini(m)};item{Text("Transaksi terbaru",fontWeight=FontWeight.Bold)};items(tx.take(8)){Row(Modifier.fillMaxWidth().padding(vertical=8.dp),Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text(it.description.ifBlank{"Transaksi"},fontWeight=FontWeight.SemiBold,fontSize=12.sp);Text(it.category,fontSize=9.sp,color=Color.Gray)};Text((if(it.type=="income")"+ " else "− ")+rp(it.base),fontWeight=FontWeight.Bold,color=if(it.type=="income")G else R,fontSize=12.sp)}}}}
@Composable fun Metric(l:String,v:Double,c:Color)=Card(Modifier.weight(1f),colors=CardDefaults.cardColors(Color.White),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){Text(l,fontSize=9.sp,color=Color.Gray);Text(rp(v),fontSize=15.sp,fontWeight=FontWeight.Bold,color=c)}}
@Composable fun MarketMini(m:JsonObject?){val r=m?.get("rates")?.jsonObject?.get("USD")?.jsonPrimitive?.doubleOrNull?:0.0;val g=m?.get("gold")?.jsonObject?.get("spotIdrPerGram")?.jsonPrimitive?.doubleOrNull?:0.0;Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Metric("USD/IDR",r,INK);Metric("Emas 24K/g",g,GOLD)}}
@Composable fun Transactions(api:Api,tx:List<Tx>,refresh:()->Unit){var type by remember{mutableStateOf("income")};var a by remember{mutableStateOf("")};var d by remember{mutableStateOf("")};var c by remember{mutableStateOf("Operasional")};Column{Text("Transaksi",fontSize=27.sp,fontWeight=FontWeight.Black,color=INK);Card(Modifier.fillMaxWidth().padding(vertical=12.dp),colors=CardDefaults.cardColors(Color.White),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(type=="income",{type="income"},{Text("Pemasukan")});FilterChip(type=="expense",{type="expense"},{Text("Pengeluaran")})};Field("Nominal IDR",a){a=it};Field("Deskripsi",d){d=it};Field("Kategori",c){c=it};Button(onClick={Thread{try{api.add(type,a.toDouble(),c,d);refresh()}catch(_:Exception){}}},Modifier.fillMaxWidth()){Text("Simpan")}}};LazyColumn{items(tx){t->Text(t.description+" · "+rp(t.base),Modifier.padding(vertical=8.dp),fontSize=11.sp)}}}}
@Composable fun Market(m:JsonObject?){val r=m?.get("rates")?.jsonObject;val g=m?.get("gold")?.jsonObject;val a=m?.get("antam")?.jsonObject;LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("Pasar",fontSize=27.sp,fontWeight=FontWeight.Black,color=INK)};item{MCard("USD/IDR",r?.get("USD")?.jsonPrimitive?.doubleOrNull?:0.0,"Live intraday")};item{MCard("EUR/IDR",r?.get("EUR")?.jsonPrimitive?.doubleOrNull?:0.0,"Live intraday")};item{MCard("Emas 24K / gram",g?.get("spotIdrPerGram")?.jsonPrimitive?.doubleOrNull?:0.0,"Spot · 30 detik")};item{MCard("ANTAM 1 gram",a?.get("idrPerGram")?.jsonPrimitive?.doubleOrNull?:0.0,"Referensi harian")}}}
@Composable fun MCard(n:String,v:Double,s:String)=Card(colors=CardDefaults.cardColors(Color.White),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(15.dp)){Text(n,fontSize=10.sp,color=Color.Gray);Text(if(v>0)rp(v) else "—",fontSize=20.sp,fontWeight=FontWeight.Black);Text(s,fontSize=9.sp,color=GOLD)}}
fun rp(v:Double)=NumberFormat.getCurrencyInstance(Locale("id","ID")).format(v).replace(",00","")
