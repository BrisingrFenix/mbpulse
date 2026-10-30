// Supabase Edge Function: confirm-withdrawal
// -----------------------------------------------------------------------------
// Verifies that a withdrawal request is signed by the participant's private key,
// then erases their data via the service_role.
//
// RSA signature verification is impossible in pgcrypto, so it lives here (Deno +
// Web Crypto). The function is the *only* path to withdraw_participant(), which
// is revoked from anon (see withdraw.sql).
//
// Two independent checks (docs/design/participant-accounts.md §5.4):
//   1. the caller's session JWT belongs to the participant's account, and
//   2. the device-key signature over the challenge verifies.
// Deploy WITH JWT verification (the default) — the platform rejects requests
// without a valid session before this code runs:
//
//     supabase functions deploy confirm-withdrawal
//
// Request:  POST { "pseudoId": "<uuid>", "signatureB64": "<base64>" }
//           Authorization: Bearer <participant session JWT>
//   signatureB64 = SHA256withRSA (RSASSA-PKCS1-v1_5) over the UTF-8 bytes of the
//   challenge string returned by request_withdrawal_challenge(pseudoId).
//
// After erasing the data it deletes the participant's Supabase account, and the
// signed receipt (format v2) records that — keep the signing string in sync with
// LoadingViewModel.verifyReceiptSignature.
// -----------------------------------------------------------------------------

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  let body: { pseudoId?: string; signatureB64?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid json" }, 400);
  }

  const pseudoId = body.pseudoId;
  const signatureB64 = body.signatureB64;
  if (!pseudoId || !signatureB64) return json({ error: "missing pseudoId or signatureB64" }, 400);

  const admin = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );

  // 0) Who is calling: the account behind the session JWT.
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: caller, error: callerErr } = await admin.auth.getUser(jwt);
  if (callerErr || !caller?.user) return json({ error: "sign in required" }, 401);
  const authUserId = caller.user.id;

  // 1) Look up the outstanding challenge.
  const { data: challengeRow, error: challengeErr } = await admin
    .from("withdrawal_challenges")
    .select("challenge, expires_at")
    .eq("pseudo_id", pseudoId)
    .maybeSingle();

  if (challengeErr) return json({ error: "lookup failed" }, 500);
  if (!challengeRow) return json({ error: "no challenge — request one first" }, 401);
  if (new Date(challengeRow.expires_at).getTime() < Date.now()) {
    return json({ error: "challenge expired" }, 401);
  }

  // 2) Look up the participant's public key.
  const { data: participant, error: pErr } = await admin
    .from("participants")
    .select("public_key_b64, auth_user_id")
    .eq("pseudo_id", pseudoId)
    .maybeSingle();

  if (pErr) return json({ error: "lookup failed" }, 500);
  if (!participant?.public_key_b64) return json({ error: "unknown participant" }, 401);
  if (participant.auth_user_id !== authUserId) return json({ error: "not your participant" }, 403);

  // 3) Verify the signature over the challenge.
  let valid = false;
  try {
    valid = await verifySignature(participant.public_key_b64, challengeRow.challenge, signatureB64);
  } catch (_e) {
    valid = false;
  }
  if (!valid) return json({ error: "signature verification failed" }, 401);

  // 4) Erase. Returns the per-table counts it deleted.
  const { data: counts, error: delErr } = await admin.rpc("withdraw_participant", {
    p_pseudo_id: pseudoId,
  });
  if (delErr) return json({ error: "deletion failed" }, 500);

  // 4b) The participant's account. Its data is already gone, so a failure here
  //     is reported in the receipt (accountDeleted = 0), not fatal; the daily
  //     purge_orphan_accounts() removes accounts without a participant anyway.
  const { error: userErr } = await admin.auth.admin.deleteUser(authUserId);
  const accountDeleted = userErr ? 0 : 1;

  // 5) Issue a deletion receipt: log an audit row (pseudo_id hashed so the log
  //    cannot re-identify), sign it, and return it to the device as proof.
  const receiptId = crypto.randomUUID();
  const deletedAt = new Date().toISOString();
  const pseudoIdHash = await sha256Hex(pseudoId);
  const studyId = counts?.studyId ?? null;
  const recordsDeleted = counts?.recordsDeleted ?? 0;
  const consentDeleted = counts?.consentDeleted ?? 0;
  const participantDeleted = counts?.participantDeleted ?? 0;

  // Tamper-evident signature. The signed string is a fixed, versioned field
  // order the app reconstructs identically to verify (see LoadingViewModel).
  const signingString =
    `v2|${receiptId}|${pseudoIdHash}|${studyId ?? ""}|${deletedAt}` +
    `|${recordsDeleted}|${consentDeleted}|${participantDeleted}|${accountDeleted}`;
  const receiptSignatureB64 = await signReceipt(signingString);

  const { error: auditErr } = await admin.from("deletion_receipts").insert({
    receipt_id: receiptId,
    pseudo_id_hash: pseudoIdHash,
    study_id: studyId,
    deleted_at: deletedAt,
    records_deleted: recordsDeleted,
    consent_deleted: consentDeleted,
    participant_deleted: participantDeleted,
    account_deleted: accountDeleted,
    signature_b64: receiptSignatureB64,
  });
  if (auditErr) console.error("deletion_receipts insert failed:", auditErr.message);

  return json({
    ok: true,
    receipt: {
      version: "v2",
      receiptId,
      pseudoIdHash,
      studyId,
      deletedAt,
      recordsDeleted,
      consentDeleted,
      participantDeleted,
      accountDeleted,
      signatureB64: receiptSignatureB64,
    },
  }, 200);
});

// -----------------------------------------------------------------------------

function json(payload: unknown, status: number): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function base64ToBytes(b64: string): Uint8Array {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function bytesToBase64(bytes: ArrayBuffer): string {
  const b = new Uint8Array(bytes);
  let binary = "";
  for (let i = 0; i < b.length; i++) binary += String.fromCharCode(b[i]);
  return btoa(binary);
}

// Signs the receipt string with the RSA deletion-signing key (RSASSA-PKCS1-v1_5
// / SHA-256). The private key is a PKCS#8 DER, base64-encoded, in the
// DELETION_SIGNING_KEY_PKCS8_B64 secret. Returns "" if no key is configured, so
// the app simply shows an unverified receipt.
async function signReceipt(signingString: string): Promise<string> {
  const pkcs8B64 = Deno.env.get("DELETION_SIGNING_KEY_PKCS8_B64");
  if (!pkcs8B64) return "";
  const key = await crypto.subtle.importKey(
    "pkcs8",
    base64ToBytes(pkcs8B64),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingString),
  );
  return bytesToBase64(signature);
}

async function verifySignature(
  publicKeyB64: string,
  challenge: string,
  signatureB64: string,
): Promise<boolean> {
  // participants.public_key_b64 is DER X.509 SubjectPublicKeyInfo (SPKI).
  const key = await crypto.subtle.importKey(
    "spki",
    base64ToBytes(publicKeyB64),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  return await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    base64ToBytes(signatureB64),
    new TextEncoder().encode(challenge),
  );
}
