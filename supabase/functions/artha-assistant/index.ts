import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const geminiKey = Deno.env.get("GEMINI_API_KEY");
  if (!supabaseUrl || !anonKey || !geminiKey) {
    return json({ error: "Backend belum dikonfigurasi. Hubungi administrator." }, 503);
  }

  const authorization = req.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return json({ error: "Unauthorized" }, 401);

  let body: { question?: string; from?: string; to?: string; consent?: boolean };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Request JSON tidak valid." }, 400);
  }

  if (body.consent !== true) return json({ error: "Aktifkan persetujuan pengiriman ringkasan transaksi ke layanan AI terlebih dahulu." }, 403);

  const question = String(body.question ?? "").trim().slice(0, 1000);
  if (!question) return json({ error: "Pertanyaan tidak boleh kosong." }, 400);

  const today = new Date();
  const firstDay = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1));
  const dateTo = /^\d{4}-\d{2}-\d{2}$/.test(body.to ?? "") ? body.to! : today.toISOString().slice(0, 10);
  const dateFrom = /^\d{4}-\d{2}-\d{2}$/.test(body.from ?? "")
    ? body.from!
    : firstDay.toISOString().slice(0, 10);
  if (dateFrom > dateTo) return json({ error: "Rentang tanggal tidak valid." }, 400);

  // The user JWT is forwarded to Postgres, so database RLS scopes the query.
  const supabase = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userResult, error: userError } = await supabase.auth.getUser();
  if (userError || !userResult.user) return json({ error: "Sesi tidak valid. Silakan masuk ulang." }, 401);

  // Only aggregate fields are selected. Descriptions, merchant names, account numbers,
  // receipt images and other raw identifiers are deliberately excluded from the AI prompt.
  const { data: rows, error: transactionError } = await supabase
    .from("transactions")
    .select("transaction_type, category, base_amount, base_currency, transaction_date")
    .gte("transaction_date", dateFrom)
    .lte("transaction_date", dateTo)
    .order("transaction_date", { ascending: false })
    .limit(1000);

  if (transactionError) {
    console.error("Transaction aggregate query failed", transactionError.message);
    return json({ error: "Data pembukuan belum tersedia. Pastikan skema backend sudah diterapkan." }, 503);
  }

  const categories = new Map<string, number>();
  let income = 0;
  let expense = 0;
  const currency = rows?.[0]?.base_currency ?? "IDR";
  for (const row of rows ?? []) {
    const value = Number(row.base_amount ?? 0);
    if (!Number.isFinite(value) || value <= 0) continue;
    const category = String(row.category ?? "Lainnya").slice(0, 48);
    if (row.transaction_type === "income") income += value;
    if (row.transaction_type === "expense") {
      expense += value;
      categories.set(category, (categories.get(category) ?? 0) + value);
    }
  }

  const aggregate = {
    period: { from: dateFrom, to: dateTo },
    currency,
    transactionCount: rows?.length ?? 0,
    income,
    expense,
    netCashFlow: income - expense,
    expensesByCategory: [...categories.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, 10)
      .map(([category, amount]) => ({ category, amount })),
  };

  const prompt = [
    "Kamu adalah Artha Assistant, asisten analisis keuangan untuk usaha kecil Indonesia.",
    "Jawab dalam Bahasa Indonesia yang jernih, praktis, dan tidak menghakimi.",
    "Gunakan hanya agregat transaksi berikut. Jangan mengarang data, rasio, tanggal, atau fakta yang tidak tersedia.",
    "Pisahkan fakta dari rekomendasi. Jangan mengaku sebagai konsultan pajak/akuntan. Untuk pajak dan zakat, jelaskan bahwa hasil butuh verifikasi aturan dan kondisi pengguna.",
    "Jangan mendorong keputusan investasi, pinjaman, atau transfer. Jangan meminta data pribadi/rahasia.",
    "Batasi jawaban sekitar 250 kata. Bila data kurang, sebutkan keterbatasannya.",
    "AGREGAT TRANSAKSI (sudah dibatasi oleh RLS pengguna):",
    JSON.stringify(aggregate),
    "PERTANYAAN PENGGUNA:",
    question,
  ].join("\n\n");

  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${Deno.env.get("GEMINI_MODEL") ?? "gemini-3.8-flash"}:generateContent`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": geminiKey },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [{ text: prompt }] }],
        generationConfig: { temperature: 0.3, maxOutputTokens: 700 },
      }),
      signal: AbortSignal.timeout(25000),
    },
  );

  if (!response.ok) {
    console.error("Gemini API failed", response.status);
    return json({ error: "Asisten AI sedang tidak tersedia. Coba lagi nanti." }, 502);
  }

  const generated = await response.json();
  const answer = generated?.candidates?.[0]?.content?.parts
    ?.map((part: { text?: string }) => part.text ?? "")
    .join("\n")
    .trim();

  if (!answer) return json({ error: "AI belum menghasilkan jawaban. Coba ubah pertanyaan." }, 502);
  return json({ answer, period: aggregate.period, transactionCount: aggregate.transactionCount });
});
