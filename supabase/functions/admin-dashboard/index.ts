import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "https://arthanesia.github.io",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  const secretMapRaw = Deno.env.get("SUPABASE_SECRET_KEYS") ?? "";
  let secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (secretMapRaw) {
    try { secret = JSON.parse(secretMapRaw).default ?? secret; } catch { /* legacy env fallback */ }
  }
  if (!url || !anon || !secret) return json({ error: "Admin backend belum dikonfigurasi." }, 503);

  const authorization = req.headers.get("Authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return json({ error: "Unauthorized" }, 401);
  const token = authorization.slice(7);

  const userClient = createClient(url, anon, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: authResult, error: authError } = await userClient.auth.getUser(token);
  const user = authResult?.user;
  if (authError || !user) return json({ error: "Sesi tidak valid." }, 401);
  if (user.app_metadata?.role !== "admin") return json({ error: "Akun ini tidak memiliki akses admin." }, 403);

  const admin = createClient(url, secret, { auth: { persistSession: false, autoRefreshToken: false } });
  const limitRaw = Number(new URL(req.url).searchParams.get("limit") ?? "100");
  const limit = Number.isFinite(limitRaw) ? Math.max(1, Math.min(100, Math.floor(limitRaw))) : 100;

  const [profileCount, transactionCount, profiles] = await Promise.all([
    admin.from("profiles").select("id", { count: "exact", head: true }),
    admin.from("transactions").select("id", { count: "exact", head: true }),
    admin.from("profiles").select("id,full_name,business_name,base_currency,created_at").order("created_at", { ascending: false }).limit(limit),
  ]);
  if (profileCount.error || transactionCount.error || profiles.error) {
    console.error("admin metrics query failed");
    return json({ error: "Data dashboard tidak dapat dimuat." }, 503);
  }

  const { error: auditError } = await admin.from("admin_audit_logs").insert({
    admin_user_id: user.id,
    action: "admin_dashboard_view",
    metadata: { profile_limit: limit, transaction_rows_returned: false },
  });
  if (auditError) {
    console.error("admin audit insert failed");
    return json({ error: "Audit log gagal ditulis; data tidak ditampilkan." }, 503);
  }

  return json({
    metrics: { registeredUsers: profileCount.count ?? 0, storedTransactions: transactionCount.count ?? 0 },
    profiles: profiles.data ?? [],
    privacy: { transactionRowsIncluded: false },
  });
});
