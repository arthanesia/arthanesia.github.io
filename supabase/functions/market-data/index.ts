const cors = {
  "Access-Control-Allow-Origin": "https://arthanesia.github.io",
  "Access-Control-Allow-Headers": "apikey, content-type, authorization, x-client-info",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      ...cors,
      "Content-Type": "application/json",
      "Cache-Control": "public, max-age=300, stale-while-revalidate=900",
    },
  });

async function fetchJson(url: string) {
  const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error("Market provider unavailable");
  return response.json();
}

async function fetchText(url: string) {
  const response = await fetch(url, {
    signal: AbortSignal.timeout(12000),
    headers: { "Accept": "text/csv,text/plain,*/*" },
  });
  if (!response.ok) throw new Error("ANTAM daily feed unavailable (" + response.status + ")");
  return response.text();
}

function parseAntamDailyCsv(csv: string) {
  const lines = csv.trim().split(/\r?\n/);
  if (lines.length < 2) throw new Error("ANTAM daily feed is empty");
  const header = lines[0].split(",").map(x => x.trim());
  const index = (name: string) => header.indexOf(name);
  const required = ["date", "source", "brand", "product", "weight_g", "sell_price", "buyback_price", "source_updated_at", "scraped_at"];
  for (const name of required) if (index(name) < 0) throw new Error("ANTAM feed missing column " + name);

  const rows = lines.slice(1).map(line => {
    const cols = line.split(",");
    const get = (name: string) => cols[index(name)]?.trim() ?? "";
    return {
      date: get("date"),
      source: get("source"),
      brand: get("brand"),
      product: get("product"),
      weight: Number(get("weight_g")),
      sell: Number(get("sell_price")),
      buyback: Number(get("buyback_price")),
      sourceUpdatedAt: get("source_updated_at"),
      scrapedAt: get("scraped_at"),
    };
  }).filter(row =>
    row.source === "logammulia" &&
    row.brand === "antam" &&
    row.product === "gold_bar" &&
    row.weight === 1 &&
    Number.isFinite(row.sell) && row.sell > 100000 &&
    Number.isFinite(row.buyback) && row.buyback > 100000 &&
    /^\d{4}-\d{2}-\d{2}$/.test(row.date)
  ).sort((a, b) =>
    b.date.localeCompare(a.date) ||
    Date.parse(b.sourceUpdatedAt) - Date.parse(a.sourceUpdatedAt) ||
    Date.parse(b.scrapedAt) - Date.parse(a.scrapedAt)
  );

  const latest = rows[0];
  if (!latest) throw new Error("No valid ANTAM 1 gram retail/buyback row found");
  const ageMs = Date.now() - Date.parse(latest.sourceUpdatedAt);
  const stale = !Number.isFinite(ageMs) || ageMs > 36 * 60 * 60 * 1000;
  return { ...latest, stale, rowCount: rows.length };
}

function jakartaDate(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Jakarta",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const errors: string[] = [];
  let fx: any = null;
  let gold: any = null;
  let antam: ReturnType<typeof parseAntamDailyCsv> | null = null;
  const year = jakartaDate().slice(0, 4);
  const antamCsvUrl = `https://raw.githubusercontent.com/hafidznrg/gold-price-tracker/main/data/prices/logammulia/${year}.csv`;

  const [fxResult, goldResult, antamResult] = await Promise.allSettled([
    fetchJson("https://open.er-api.com/v6/latest/IDR"),
    fetchJson("https://api.gold-api.com/price/XAU"),
    fetchText(antamCsvUrl).then(parseAntamDailyCsv),
  ]);

  if (fxResult.status === "fulfilled") fx = fxResult.value; else errors.push("fx");
  if (goldResult.status === "fulfilled") gold = goldResult.value; else errors.push("gold-spot");
  if (antamResult.status === "fulfilled") antam = antamResult.value; else errors.push("antam-daily-feed");

  const usdPerIdr = Number(fx?.rates?.USD ?? 0);
  const idrPerUsd = usdPerIdr > 0 ? 1 / usdPerIdr : 0;
  const goldUsdPerOunce = Number(gold?.price ?? 0);
  const goldSpotIdrPerGram = idrPerUsd > 0 && goldUsdPerOunce > 0
    ? goldUsdPerOunce * idrPerUsd / 31.1034768
    : null;

  const currencies = ["IDR", "USD", "EUR", "GBP", "JPY", "SGD", "AUD", "MYR"];
  const rates: Record<string, number> = { IDR: 1 };
  for (const code of currencies.filter(code => code !== "IDR")) {
    const raw = Number(fx?.rates?.[code] ?? 0);
    if (raw > 0) rates[code] = raw;
  }

  const now = new Date().toISOString();
  if (!fx && !gold && !antam) {
    return json({ error: "Data pasar sementara tidak tersedia.", providerErrors: errors }, 503);
  }

  return json({
    base: "IDR",
    rates,
    rateSource: "open.er-api.com",
    rateUpdatedAt: fx?.time_last_update_utc ?? null,
    fx: {
      source: "open.er-api.com",
      updatedAt: fx?.time_last_update_utc ?? null,
      note: "Kurs referensi provider, mengikuti jadwal pembaruan provider dan bukan harga transaksi bank.",
    },
    gold: {
      spotUsdPerOunce: goldUsdPerOunce || null,
      spotIdrPerGram: goldSpotIdrPerGram,
      idrPerGram: goldSpotIdrPerGram,
      source: "Gold API XAU spot",
      updatedAt: gold?.updatedAt ?? gold?.timestamp ?? null,
      note: "Harga spot global 24K, bukan harga ritel Indonesia.",
    },
    antam: {
      retailIdrPerGram: antam?.sell ?? null,
      retailInclTaxIdrPerGram: antam?.sell ?? null,
      buybackIdrPerGram: antam?.buyback ?? null,
      source: antam ? "Logam Mulia ANTAM · mirrored daily feed" : null,
      sourceUrl: antamCsvUrl,
      priceDate: antam?.date ?? null,
      sourceUpdatedAt: antam?.sourceUpdatedAt ?? null,
      scrapedAt: antam?.scrapedAt ?? null,
      updatedAt: now,
      stale: antam?.stale ?? true,
      available: !!antam,
      priceUpdateSchedule: "Snapshot harian ANTAM; bukan tick-by-tick.",
      tax: {
        purchasePph22FinalConsumer: 0,
        purchasePph22MerchantWithNpwp: 0.0025,
        purchasePph22MerchantWithoutNpwp: 0.005,
        buybackPph22ThresholdIdr: 10000000,
        buybackPph22WithNpwp: 0.015,
        buybackPph22WithoutNpwp: 0.03,
        note: "Estimasi PPh 22; pajak aktual mengikuti peraturan, NPWP, dan nilai transaksi.",
      },
      zakatReference: "Nisab zakat emas 85 gram; BAZNAS merujuk harga buyback emas ANTAM.",
    },
    serverTime: now,
    jakartaDate: jakartaDate(),
    providerErrors: errors,
  });
});
