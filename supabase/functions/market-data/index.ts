const cors = {
  "Access-Control-Allow-Origin": "https://arthanesia.github.io",
  "Access-Control-Allow-Headers": "apikey, content-type, authorization, x-client-info",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: {...cors, "Content-Type":"application/json", "Cache-Control":"no-store, no-cache, must-revalidate", "Pragma":"no-cache"},
});
async function fetchJson(url: string, headers: HeadersInit = {}) {
  const response = await fetch(url,{cache:"no-store",signal:AbortSignal.timeout(10000),headers});
  if(!response.ok)throw new Error("Market provider unavailable ("+response.status+")");
  return response.json();
}
async function fetchText(url:string) {
  const response=await fetch(url,{cache:"no-store",signal:AbortSignal.timeout(12000),headers:{"Accept":"text/csv,text/plain,*/*"}});
  if(!response.ok)throw new Error("ANTAM daily feed unavailable ("+response.status+")");
  return response.text();
}
function parseAntamDailyCsv(csv:string) {
  const lines=csv.trim().split(/\r?\n/);if(lines.length<2)throw new Error("ANTAM feed is empty");
  const header=lines[0].split(",").map(x=>x.trim()),index=(name:string)=>header.indexOf(name);
  const required=["date","source","brand","product","weight_g","sell_price","buyback_price","source_updated_at","scraped_at"];
  for(const name of required)if(index(name)<0)throw new Error("ANTAM feed missing column "+name);
  const rows=lines.slice(1).map(line=>{
    const cols=line.split(","),get=(name:string)=>cols[index(name)]?.trim()??"";
    return {date:get("date"),source:get("source"),brand:get("brand"),product:get("product"),weight:Number(get("weight_g")),sell:Number(get("sell_price")),buyback:Number(get("buyback_price")),sourceUpdatedAt:get("source_updated_at"),scrapedAt:get("scraped_at")};
  }).filter(row=>row.source==="logammulia"&&row.brand==="antam"&&row.product==="gold_bar"&&row.weight===1&&Number.isFinite(row.sell)&&row.sell>100000&&Number.isFinite(row.buyback)&&row.buyback>100000&&/^\d{4}-\d{2}-\d{2}$/.test(row.date))
    .sort((a,b)=>b.date.localeCompare(a.date)||Date.parse(b.sourceUpdatedAt)-Date.parse(a.sourceUpdatedAt)||Date.parse(b.scrapedAt)-Date.parse(a.scrapedAt));
  const latest=rows[0];if(!latest)throw new Error("No valid ANTAM 1 gram retail/buyback row found");
  const ageMs=Date.now()-Date.parse(latest.sourceUpdatedAt),stale=!Number.isFinite(ageMs)||ageMs>36*60*60*1000;
  return {...latest,stale,rowCount:rows.length};
}
function jakartaDate():string {
  return new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Jakarta",year:"numeric",month:"2-digit",day:"2-digit"}).format(new Date());
}
type FxSnapshot={rates:Record<string,number>;rateSource:string;rateUpdatedAt:string|null;fx:{source:string;sourceType:string;updatedAt:string|null;servedAt:string;delaySeconds:number|null;marketSession:string|null;stale:boolean;note:string;fallbackReason?:string}};
let fxCache:{expiresAt:number;value:FxSnapshot}|null=null;
let fxPending:Promise<FxSnapshot>|null=null;
const supportedCurrencies=["USD","EUR","GBP","JPY","SGD","AUD","MYR"];
async function fetchFxSnapshot():Promise<FxSnapshot>{
  if(fxCache&&fxCache.expiresAt>Date.now())return fxCache.value;
  if(fxPending)return fxPending;
  fxPending=(async()=>{
    const apiKey=Deno.env.get("EXCHANGERATE_API_KEY"),apiHeaders:Record<string,string>={};
    if(apiKey)apiHeaders.Authorization="Bearer "+apiKey;
    try{
      const url="https://api.exchangerate.dev/v1/latest/IDR?symbols="+supportedCurrencies.join(",");
      const response=await fetch(url,{cache:"no-store",signal:AbortSignal.timeout(10000),headers:apiHeaders});
      if(!response.ok)throw new Error("exchangerate.dev HTTP "+response.status);
      const data=await response.json();
      if(data?.result!=="success"||!data?.rates)throw new Error(data?.message||data?.code||"Invalid FX response");
      const rates:Record<string,number>={IDR:1};
      for(const code of supportedCurrencies){const rate=Number(data.rates[code]??0);if(Number.isFinite(rate)&&rate>0)rates[code]=rate;}
      if(supportedCurrencies.some(code=>!rates[code]))throw new Error("Intraday provider returned an incomplete currency table");
      const now=new Date().toISOString(),headerDelay=response.headers.get("X-Price-Delay-Seconds"),configuredDelay=Deno.env.get("EXCHANGERATE_DELAY_SECONDS");
      const delaySeconds=headerDelay!==null?Math.max(0,Number(headerDelay)||0):Math.max(0,Number(configuredDelay??900)||0);
      const updatedAt=data.data_updated_at||data.timestamp||null,stale=Object.values(data.stale||{}).some(value=>value===true);
      return {rates,rateSource:"exchangerate.dev",rateUpdatedAt:updatedAt,fx:{
        source:"exchangerate.dev",sourceType:String(data.source||"unknown"),updatedAt,servedAt:now,delaySeconds,
        marketSession:data.market_session||null,stale,note:data.notice||"Indicative rates, not for settlement.",
      }};
    }catch(primaryError){
      const fallback=await fetchJson("https://open.er-api.com/v6/latest/IDR");
      const rates:Record<string,number>={IDR:1};
      for(const code of supportedCurrencies){const rate=Number(fallback?.rates?.[code]??0);if(Number.isFinite(rate)&&rate>0)rates[code]=rate;}
      if(!rates.USD)throw primaryError;
      const now=new Date().toISOString(),updatedAt=fallback?.time_last_update_utc??null;
      return {rates,rateSource:"open.er-api.com",rateUpdatedAt:updatedAt,fx:{
        source:"open.er-api.com",sourceType:"daily",updatedAt,servedAt:now,delaySeconds:null,marketSession:null,stale:false,
        note:"Fallback kurs referensi harian; bukan harga intraday.",
        fallbackReason:primaryError instanceof Error?primaryError.message:"Intraday provider unavailable",
      }};
    }
  })().then(snapshot=>{fxCache={value:snapshot,expiresAt:Date.now()+300000};return snapshot;}).finally(()=>{fxPending=null;});
  return fxPending;
}
Deno.serve(async(req:Request)=>{
  if(req.method==="OPTIONS")return new Response("ok",{headers:cors});
  if(req.method!=="GET")return json({error:"Method not allowed"},405);
  const requestUrl=new URL(req.url),fxOnly=requestUrl.searchParams.get("scope")==="fx",errors:string[]=[];
  const year=jakartaDate().slice(0,4),antamCsvUrl="https://raw.githubusercontent.com/hafidznrg/gold-price-tracker/main/data/prices/logammulia/"+year+".csv";
  const fxPromise=fetchFxSnapshot().catch(error=>{errors.push("fx: "+(error instanceof Error?error.message:"provider unavailable"));return null;});
  const goldPromise=fxOnly?Promise.resolve(null):fetchJson("https://api.gold-api.com/price/XAU").catch(error=>{errors.push("gold-spot: "+(error instanceof Error?error.message:"provider unavailable"));return null;});
  const antamPromise=fxOnly?Promise.resolve(null):fetchText(antamCsvUrl).then(parseAntamDailyCsv).catch(error=>{errors.push("antam-daily-feed: "+(error instanceof Error?error.message:"provider unavailable"));return null;});
  const [fx,gold,antam]=await Promise.all([fxPromise,goldPromise,antamPromise]);
  if(fxOnly&&!fx)return json({error:"Kurs intraday dan sumber cadangan sementara tidak tersedia.",providerErrors:errors},503);
  if(!fx&&!gold&&!antam)return json({error:"Data pasar sementara tidak tersedia.",providerErrors:errors},503);
  const usdPerIdr=Number(fx?.rates?.USD??0),idrPerUsd=usdPerIdr>0?1/usdPerIdr:0,goldUsdPerOunce=Number(gold?.price??0);
  const goldSpotIdrPerGram=idrPerUsd>0&&goldUsdPerOunce>0?goldUsdPerOunce*idrPerUsd/31.1034768:null,now=new Date().toISOString();
  return json({
    base:"IDR",rates:fx?.rates??{IDR:1},rateSource:fx?.rateSource??null,rateUpdatedAt:fx?.rateUpdatedAt??null,fx:fx?.fx??null,
    gold:fxOnly?undefined:{
      spotUsdPerOunce:goldUsdPerOunce||null,spotIdrPerGram:goldSpotIdrPerGram,idrPerGram:goldSpotIdrPerGram,
      source:"Gold API XAU spot",updatedAt:gold?.updatedAt??gold?.timestamp??null,note:"Harga spot global 24K, bukan harga ritel Indonesia.",
    },
    antam:fxOnly?undefined:{
      retailIdrPerGram:antam?.sell??null,retailInclTaxIdrPerGram:antam?.sell??null,buybackIdrPerGram:antam?.buyback??null,
      source:antam?"Logam Mulia ANTAM · mirrored daily feed":null,sourceUrl:antamCsvUrl,priceDate:antam?.date??null,
      sourceUpdatedAt:antam?.sourceUpdatedAt??null,scrapedAt:antam?.scrapedAt??null,updatedAt:now,stale:antam?.stale??true,available:!!antam,
      priceUpdateSchedule:"Snapshot harian ANTAM; bukan tick-by-tick.",
      tax:{purchasePph22FinalConsumer:0,purchasePph22MerchantWithNpwp:0.0025,purchasePph22MerchantWithoutNpwp:0.005,buybackPph22ThresholdIdr:10000000,buybackPph22WithNpwp:0.015,buybackPph22WithoutNpwp:0.03,note:"Estimasi PPh 22; pajak aktual mengikuti peraturan, NPWP, dan nilai transaksi."},
      zakatReference:"Nisab zakat emas 85 gram; BAZNAS merujuk harga buyback emas ANTAM.",
    },
    serverTime:now,jakartaDate:jakartaDate(),providerErrors:errors,
  });
});
