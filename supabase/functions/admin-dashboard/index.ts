import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anonKey || !serviceKey) return json({ error: "Admin backend belum dikonfigurasi." }, 503);

  const authorization = req.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return json({ error: "Unauthorized" }, 401);

  // Verify the caller with Supabase Auth. Authorization uses server-managed app_metadata.
  const callerClient = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: authData, error: authError } = await callerClient.auth.getUser();
  if (authError || !authData.user) return json({ error: "Sesi tidak valid." }, 401);
  if (authData.user.app_metadata?.role !== "admin") return json({ error: "Forbidden" }, 403);

  // Privileged client is server-side only and returns profile directory fields + counts,
  // never financial transaction rows. The secret key must remain in Edge Function secrets.
  const adminClient = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const requestedLimit = Number(new URL(req.url).searchParams.get("limit") ?? "25");
  const limit = Number.isFinite(requestedLimit) ? Math.max(1, Math.min(50, Math.floor(requestedLimit))) : 25;

  const [profilesResult, profileCountResult, transactionCountResult] = await Promise.all([
    adminClient.from("profiles")
      .select("id, display_name, business_name, base_currency, created_at")
      .order("created_at", { ascending: false })
      .limit(limit),
    adminClient.from("profiles").select("id", { count: "exact", head: true }),
    adminClient.from("transactions").select("id", { count: "exact", head: true }),
  ]);

  if (profilesResult.error || profileCountResult.error || transactionCountResult.error) {
    console.error("Admin metrics query failed");
    return json({ error: "Metrik admin tidak dapat dimuat." }, 503);
  }

  const { error: auditError } = await adminClient.from("admin_audit_logs").insert({
    actor_admin_id: authData.user.id,
    action: "view_admin_dashboard",
    purpose: "Melihat direktori profil terbatas dan metrik agregat",
    metadata: { profile_limit: limit },
  });
  if (auditError) {
    console.error("Admin audit write failed");
    return json({ error: "Audit log gagal ditulis; permintaan dihentikan." }, 503);
  }

  return json({
    metrics: {
      registeredUsers: profileCountResult.count ?? 0,
      storedTransactions: transactionCountResult.count ?? 0,
    },
    profiles: profilesResult.data ?? [],
    privacy: {
      transactionRowsIncluded: false,
      note: "Dashboard menampilkan profil dasar dan statistik agregat. Detail transaksi memerlukan persetujuan bantuan terpisah.",
    },
  });
});
