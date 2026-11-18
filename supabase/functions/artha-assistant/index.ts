import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "https://arthanesia.github.io",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });

function jakartaDate(date = new Date()): string {
  return new Date(date.getTime() + 7 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

function idr(value: number): string {
  return new Intl.NumberFormat("id-ID", {
    style: "currency", currency: "IDR", maximumFractionDigits: 0,
  }).format(value);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  const geminiKey = Deno.env.get("GEMINI_API_KEY") ?? "";
  const model = Deno.env.get("GEMINI_MODEL") || "gemini-2.5-flash";

  // Safe configuration probe: report presence only, never reveal any secret value.
  if (req.method === "GET" && new URL(req.url).searchParams.get("health") === "1") {
    return json({
      status: "ok",
      provider: "Gemini",
      configured: Boolean(url && anon && geminiKey),
      model,
    });
  }
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  if (!url || !anon) return json({ error: "Backend AI belum dikonfigurasi." }, 503);
  if (!geminiKey) {
    return json({
      error: "Layanan AI online belum aktif: administrator perlu mengisi GEMINI_API_KEY di Supabase Edge Function Secrets. Tidak ada analisis lokal atau jawaban palsu.",
      code: "AI_PROVIDER_NOT_CONFIGURED",
    }, 503);
  }

  const authorization = req.headers.get("Authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return json({ error: "Silakan masuk ke akun Arthanesia terlebih dahulu." }, 401);

  let body: { question?: string; from?: string; to?: string };
  try { body = await req.json(); } catch { return json({ error: "Format permintaan tidak valid." }, 400); }
  const question = String(body.question ?? "").trim().slice(0, 1200);
  if (!question) return json({ error: "Pertanyaan tidak boleh kosong." }, 400);

  const client = createClient(url, anon, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const token = authorization.slice(7);
  const { data: auth, error: authError } = await client.auth.getUser(token);
  if (authError || !auth.user) return json({ error: "Sesi akun tidak valid. Silakan masuk kembali." }, 401);

  const dateTo = /^\d{4}-\d{2}-\d{2}$/.test(body.to ?? "") ? body.to! : jakartaDate();
  const dateFrom = /^\d{4}-\d{2}-\d{2}$/.test(body.from ?? "") ? body.from! : dateTo.slice(0, 8) + "01";
  if (dateFrom > dateTo) return json({ error: "Rentang tanggal tidak valid." }, 400);

  const { data: rows, error } = await client.from("transactions")
    .select("type,category,amount_base,base_currency,occurred_at")
    .eq("user_id", auth.user.id)
    .gte("occurred_at", dateFrom + "T00:00:00+07:00")
    .lte("occurred_at", dateTo + "T23:59:59+07:00")
    .order("occurred_at", { ascending: true })
    .limit(2000);
  if (error) {
    console.error("Artha AI transaction read failed", error.message);
    return json({ error: "Ringkasan transaksi akun tidak dapat dibaca. Periksa koneksi lalu coba kembali." }, 503);
  }

  let income = 0;
  let expense = 0;
  let transfers = 0;
  const byCategory = new Map<string, number>();
  const byDay = new Map<string, { income: number; expense: number }>();
  for (const row of rows ?? []) {
    const amount = Number(row.amount_base ?? 0);
    if (!(amount > 0) || !Number.isFinite(amount)) continue;
    const day = String(row.occurred_at ?? "").slice(0, 10);
    const dayTotal = byDay.get(day) ?? { income: 0, expense: 0 };
    if (row.type === "income") {
      income += amount;
      dayTotal.income += amount;
    } else if (row.type === "expense") {
      expense += amount;
      dayTotal.expense += amount;
      const name = String(row.category ?? "Lainnya").slice(0, 40);
      byCategory.set(name, (byCategory.get(name) ?? 0) + amount);
    } else if (row.type === "transfer") {
      transfers += amount;
    }
    byDay.set(day, dayTotal);
  }

  const expensesByCategory = [...byCategory.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([category, amount]) => ({ category, amount }));
  const topExpense = expensesByCategory[0] ?? null;
  const expenseRatio = income > 0 ? expense / income : null;
  const aggregate = {
    period: { from: dateFrom, to: dateTo, timezone: "Asia/Jakarta" },
    currency: "IDR",
    transactionCount: rows?.length ?? 0,
    income,
    expense,
    transfers,
    netCashFlow: income - expense,
    expenseRatio,
    expensesByCategory,
    dailyCashflow: [...byDay.entries()].slice(-14).map(([date, value]) => ({ date, ...value })),
    coverageNote: (rows?.length ?? 0) >= 2000
      ? "Data dibatasi 2.000 transaksi terbaru pada rentang ini."
      : null,
  };

  const prompt = [
    "Kamu adalah Artha AI, analis keuangan dan pembukuan untuk UMKM Indonesia.",
    "Gunakan hanya angka ringkasan yang disediakan. Jangan mengarang saldo, tren, pajak, atau data yang tidak tersedia.",
    "Jawab dalam Bahasa Indonesia yang profesional, jelas, hangat, langsung, dan mudah dipahami pemilik usaha.",
    "Susun jawaban dengan bagian: Kondisi usaha, Temuan utama, Risiko (beri level Rendah/Sedang/Tinggi dan alasan), Peluang perbaikan, serta 3 tindakan prioritas.",
    "Bila data transaksi sedikit, katakan bahwa keyakinan analisis terbatas. Jangan menyamakan arus kas dengan laba akuntansi.",
    "Jangan meminta password, OTP, nomor rekening, atau data pribadi. Jangan memberikan kepastian hukum/pajak/zakat; tandai sebagai estimasi dan sarankan verifikasi bila relevan.",
    "Data yang dibagikan hanya agregat transaksi; tidak ada deskripsi transaksi, lampiran, atau foto struk.",
    "RINGKASAN KEUANGAN:", JSON.stringify(aggregate),
    "PERMINTAAN PENGGUNA:", question,
  ].join("\n\n");

  let response: Response;
  try {
    response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": geminiKey },
        body: JSON.stringify({
          contents: [{ role: "user", parts: [{ text: prompt }] }],
          generationConfig: { temperature: 0.25, maxOutputTokens: 1400 },
        }),
        signal: AbortSignal.timeout(30000),
      },
    );
  } catch (error) {
    console.error("Artha AI network failure", error);
    return json({ error: "Tidak dapat terhubung ke penyedia AI. Periksa koneksi lalu coba lagi." }, 502);
  }
  if (!response.ok) {
    console.error("Gemini provider failure", response.status);
    return json({ error: "Penyedia AI sedang menolak permintaan. Coba lagi beberapa saat." }, 502);
  }

  const result = await response.json();
  const answer = result?.candidates?.[0]?.content?.parts
    ?.map((part: { text?: string }) => part.text ?? "")
    .join("\n").trim();
  if (!answer) return json({ error: "AI online tidak menghasilkan jawaban. Silakan coba kembali." }, 502);

  return json({
    answer,
    mode: "cloud",
    provider: "Gemini",
    model,
    period: aggregate.period,
    transactionCount: aggregate.transactionCount,
    summary: {
      income: idr(income),
      expense: idr(expense),
      netCashFlow: idr(income - expense),
      topExpenseCategory: topExpense?.category ?? null,
    },
  });
});
