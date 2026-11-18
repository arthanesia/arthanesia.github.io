const cors = {
  "Access-Control-Allow-Origin": "https://arthanesia.github.io",
  "Access-Control-Allow-Headers": "apikey, content-type",
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

async function fetchHtml(url: string) {
  const response = await fetch(url, {
    signal: AbortSignal.timeout(12000),
    headers: {
      "User-Agent": "Mozilla/5.0 (compatible; ArthanesiaMarket/1.0; +https://arthanesia.github.io)",
      "Accept": "text/html,application/xhtml+xml",
    },
  });
  if (!response.ok) throw new Error("ANTAM page unavailable");
  return response.text();
}

function plainText(html: string): string {
  return html
    .replace(/<script\b[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&#?[a-z0-9]+;/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function parseRupiah(value: string): number | null {
  const digits = value.replace(/[^0-9]/g, "");
  const amount = Number(digits);
  return Number.isFinite(amount) && amount >= 100000 && amount <= 20000000 ? amount : null;
}

function parseRetail(html: string) {
  const rows = html.match(/<tr\b[^>]*>[\s\S]*?<\/tr>/gi) ?? [];
  for (const row of rows) {
    const cells = [...row.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)]
      .map(match => plainText(match[1]));
    if (!cells.length || !/^1\s*(?:gr|gram)$/i.test(cells[0].trim())) continue;
    const base = parseRupiah(cells[1] ?? "");
    const afterTax = parseRupiah(cells[2] ?? "");
    if (base) {
      const text = plainText(html);
      const date = text.match(/Harga Emas Hari Ini[,\s]+([0-9]{1,2}\s+[A-Za-z]+\s+[0-9]{4})/i)?.[1] ?? null;
      return { base, afterTax: afterTax ?? base, priceDate: date };
    }
  }
  return null;
}

function parseBuyback(html: string) {
  const lower = html.toLowerCase();
  const index = lower.indexOf("harga buyback");
  if (index < 0) return null;
  const fragment = html.slice(index, index + 2500);
  const text = plainText(fragment);
  const textMatch = text.match(/harga\s*buyback[\s\S]{0,180}?rp\s*([0-9][0-9.,]{4,})/i);
  if (textMatch) {
    const value = parseRupiah(textMatch[1]);
    if (value) return value;
  }
  const rawCandidates = [...fragment.matchAll(/(?:value\s*=\s*["']|Rp\s*)([0-9][0-9.,]{4,})/gi)]
    .map(match => parseRupiah(match[1]))
    .filter((value): value is number => value !== null);
  return rawCandidates.find(value => value > 500000) ?? null;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const errors: string[] = [];
  let fx: any = null;
  let gold: any = null;
  let antamRetail: ReturnType<typeof parseRetail> = null;
  let antamBuyback: number | null = null;
  let antamPageDate: string | null = null;

  const [fxResult, goldResult, retailResult, buybackResult] = await Promise.allSettled([
    fetchJson("https://open.er-api.com/v6/latest/IDR"),
    fetchJson("https://api.gold-api.com/price/XAU"),
    fetchHtml("https://www.logammulia.com/id/harga-emas-hari-ini").then(html => {
      antamRetail = parseRetail(html);
      if (!antamRetail) throw new Error("Retail price not found");
      antamPageDate = antamRetail.priceDate;
      return true;
    }),
    fetchHtml("https://www.logammulia.com/id/sell/gold?npwp=off").then(html => {
      antamBuyback = parseBuyback(html);
      if (!antamBuyback) throw new Error("Buyback price not found");
      return true;
    }),
  ]);

  if (fxResult.status === "fulfilled") fx = fxResult.value; else errors.push("fx");
  if (goldResult.status === "fulfilled") gold = goldResult.value; else errors.push("gold");
  if (retailResult.status === "rejected") errors.push("antam-retail");
  if (buybackResult.status === "rejected") errors.push("antam-buyback");

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
    if (raw > 0 && idrPerUsd > 0) {
      // open.er-api rates are units of currency per one IDR.
      rates[code] = raw;
    }
  }

  const now = new Date().toISOString();
  const antamAvailable = !!(antamRetail?.base || antamBuyback);
  if (!fx && !gold && !antamAvailable) {
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
      note: "Harga spot global 24K, hanya referensi pasar dan bukan harga ritel Indonesia.",
    },
    antam: {
      retailIdrPerGram: antamRetail?.base ?? null,
      retailInclTaxIdrPerGram: antamRetail?.afterTax ?? null,
      buybackIdrPerGram: antamBuyback,
      source: "Logam Mulia ANTAM official",
      priceDate: antamPageDate,
      updatedAt: now,
      priceUpdateSchedule: "Harga retail ANTAM diperbarui harian sekitar pukul 08.30 WIB; buyback mengikuti harga pada hari transaksi.",
      zakatReference: "Nisab zakat emas 85 gram; BAZNAS merujuk harga buyback emas ANTAM.",
    },
    serverTime: now,
    providerErrors: errors,
  });
});
