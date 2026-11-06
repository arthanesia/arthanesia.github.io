import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "https://arthanesia.github.io",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  const geminiKey = Deno.env.get("GEMINI_API_KEY") ?? "";
  if (!url || !anon) return json({ error: "Backend belum dikonfigurasi." }, 503);
  const authorization = req.headers.get("Authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return json({ error: "Silakan masuk terlebih dahulu." }, 401);

  let body: { question?: string; consent?: boolean; from?: string; to?: string };
  try { body = await req.json(); } catch { return json({ error: "Format pertanyaan tidak valid." }, 400); }
  if (body.consent !== true) return json({ error: "Aktifkan persetujuan berbagi ringkasan keuangan untuk Artha AI terlebih dahulu." }, 403);
  const question = String(body.question ?? "").trim().slice(0, 1000);
  if (!question) return json({ error: "Pertanyaan tidak boleh kosong." }, 400);

  const client = createClient(url, anon, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const token = authorization.slice(7);
  const { data: auth, error: authError } = await client.auth.getUser(token);
  if (authError || !auth.user) return json({ error: "Sesi tidak valid." }, 401);

  if (!geminiKey) return json({ error: "AI cloud belum diaktifkan karena GEMINI_API_KEY belum dipasang oleh administrator. Mode lokal tetap tersedia." }, 503);

  const dateTo = /^\d{4}-\d{2}-\d{2}$/.test(body.to ?? "") ? body.to! : new Date().toISOString().slice(0, 10);
  const dateFrom = /^\d{4}-\d{2}-\d{2}$/.test(body.from ?? "") ? body.from! : dateTo.slice(0, 8) + "01";
  if (dateFrom > dateTo) return json({ error: "Rentang tanggal tidak valid." }, 400);
  const { data: rows, error } = await client.from("transactions")
    .select("type,category,amount_base,base_currency,occurred_at")
    .gte("occurred_at", dateFrom + "T00:00:00+07:00")
    .lte("occurred_at", dateTo + "T23:59:59+07:00")
    .limit(1000);
  if (error) return json({ error: "Data transaksi tidak dapat dibaca." }, 503);

  let income = 0, expense = 0;
  const byCategory = new Map<string, number>();
  for (const row of rows ?? []) {
    const amount = Number(row.amount_base ?? 0);
    if (!(amount > 0)) continue;
    if (row.type === "income") income += amount;
    if (row.type === "expense") {
      expense += amount;
      const name = String(row.category ?? "Lainnya").slice(0, 40);
      byCategory.set(name, (byCategory.get(name) ?? 0) + amount);
    }
  }
  const aggregate = {
    period: { from: dateFrom, to: dateTo },
    currency: rows?.[0]?.base_currency ?? "IDR",
    transactionCount: rows?.length ?? 0,
    income, expense, netCashFlow: income - expense,
    expensesByCategory: [...byCategory.entries()].sort((a,b)=>b[1]-a[1]).slice(0,8)
      .map(([category,amount])=>({category,amount})),
  };
  const prompt = [
    "Kamu adalah Artha AI, asisten pembukuan usaha Indonesia.",
    "Jawab dalam Bahasa Indonesia yang profesional, jelas, singkat, tidak mengarang data. Bedakan fakta dan saran.",
    "Data ini agregat transaksi milik pengguna yang telah login. Jangan meminta password, kode OTP, atau data rekening. Jangan memberi klaim pajak/zakat yang pasti; berikan batasan dan sarankan verifikasi profesional bila perlu.",
    "Ringkasan keuangan:", JSON.stringify(aggregate),
    "Pertanyaan:", question,
  ].join("\n\n");
  const model = Deno.env.get("GEMINI_MODEL") || "gemini-2.5-flash";
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": geminiKey },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [{ text: prompt }] }],
        generationConfig: { temperature: 0.25, maxOutputTokens: 700 },
      }),
      signal: AbortSignal.timeout(25000),
    },
  );
  if (!response.ok) {
    console.error("Gemini provider failure", response.status);
    return json({ error: "Artha AI cloud sedang tidak tersedia. Coba kembali nanti." }, 502);
  }
  const result = await response.json();
  const answer = result?.candidates?.[0]?.content?.parts?.map((part: {text?:string})=>part.text ?? "").join("\n").trim();
  if (!answer) return json({ error: "AI tidak menghasilkan jawaban." }, 502);
  return json({ answer, period: aggregate.period, transactionCount: aggregate.transactionCount });
});
