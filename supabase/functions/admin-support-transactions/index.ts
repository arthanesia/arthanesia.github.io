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

  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anonKey || !serviceKey) return json({ error: "Admin backend belum dikonfigurasi." }, 503);

  const authorization = req.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return json({ error: "Unauthorized" }, 401);

  const callerClient = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: authData, error: authError } = await callerClient.auth.getUser();
  if (authError || !authData.user) return json({ error: "Sesi tidak valid." }, 401);
  if (authData.user.app_metadata?.role !== "admin") return json({ error: "Forbidden" }, 403);

  let body: { supportRequestId?: string; limit?: number };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Request JSON tidak valid." }, 400);
  }
  const requestId = String(body.supportRequestId ?? "");
  if (!/^[0-9a-f-]{36}$/i.test(requestId)) return json({ error: "ID permintaan bantuan tidak valid." }, 400);
  const askedLimit = Number(body.limit ?? 50);
  const limit = Number.isFinite(askedLimit) ? Math.max(1, Math.min(100, Math.floor(askedLimit))) : 50;

  const privileged = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  // Verify the approval is bound to this admin, is active, and has not expired or been revoked.
  const { data: support, error: supportError } = await privileged
    .from("support_access_requests")
    .select("id, user_id, admin_id, reason, status, approved_at, expires_at")
    .eq("id", requestId)
    .eq("admin_id", authData.user.id)
    .eq("status", "approved")
    .not("approved_at", "is", null)
    .gt("expires_at", new Date().toISOString())
    .maybeSingle();

  if (supportError || !support) return json({ error: "Persetujuan tidak ditemukan, sudah dicabut, atau telah kedaluwarsa." }, 403);

  // Log before reading any individual financial rows; abort if audit cannot be persisted.
  const { error: auditError } = await privileged.from("admin_audit_logs").insert({
    actor_admin_id: authData.user.id,
    target_user_id: support.user_id,
    support_request_id: support.id,
    action: "view_user_transactions",
    purpose: support.reason,
    metadata: { limit },
  });
  if (auditError) {
    console.error("Support access audit write failed");
    return json({ error: "Audit log gagal ditulis; data tidak dibuka." }, 503);
  }

  const { data: transactions, error: transactionError } = await privileged
    .from("transactions")
    .select("id, transaction_date, transaction_type, description, category, amount, currency, base_amount, base_currency")
    .eq("user_id", support.user_id)
    .order("transaction_date", { ascending: false })
    .limit(limit);

  if (transactionError) {
    console.error("Support transaction query failed");
    return json({ error: "Data transaksi tidak dapat dimuat." }, 503);
  }

  return json({
    supportRequestId: support.id,
    userId: support.user_id,
    expiresAt: support.expires_at,
    transactions: transactions ?? [],
    limit,
  });
});
