const cors = {
  "Access-Control-Allow-Origin": "https://arthanesia.github.io",
  "Access-Control-Allow-Headers": "apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "public, max-age=900" } });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);
  try {
    const [fxResponse, goldResponse] = await Promise.all([
      fetch("https://open.er-api.com/v6/latest/IDR", { signal: AbortSignal.timeout(10000) }),
      fetch("https://api.gold-api.com/price/XAU", { signal: AbortSignal.timeout(10000) }),
    ]);
    if (!fxResponse.ok || !goldResponse.ok) throw new Error("Market provider unavailable");
    const [fx, gold] = await Promise.all([fxResponse.json(), goldResponse.json()]);
    const usdPerIdr = Number(fx?.rates?.USD);
    const goldUsdPerOunce = Number(gold?.price);
    if (!(usdPerIdr > 0) || !(goldUsdPerOunce > 0)) throw new Error("Invalid provider data");
    const idrPerUsd = 1 / usdPerIdr;
    const goldIdrPerGram = goldUsdPerOunce * idrPerUsd / 31.1034768;
    const currencies = ["IDR", "USD", "EUR", "GBP", "JPY", "SGD", "AUD", "MYR"];
    const rates = Object.fromEntries(currencies.map(code => [code, Number(fx?.rates?.[code] ?? (code === "IDR" ? 1 : 0))]));
    return json({
      base: "IDR", rates, rateSource: "open.er-api.com",
      rateUpdatedAt: fx?.time_last_update_utc ?? null,
      rateInterval: "Provider reference rates; update schedule is daily, not tick-by-tick.",
      gold: {
        spotUsdPerOunce: goldUsdPerOunce, idrPerGram: goldIdrPerGram,
        source: "gold-api.com XAU spot",
        updatedAt: gold?.updatedAt ?? gold?.timestamp ?? null,
        interval: "Spot feed; not an Antam retail quote.",
      },
      serverTime: new Date().toISOString(),
    });
  } catch (error) {
    console.error("market-data provider error", error);
    return json({ error: "Harga pasar sementara tidak tersedia." }, 503);
  }
});
