#!/usr/bin/env bash
# =============================================================================
# keygen.sh — generate the two RSA keypairs the backend needs
# =============================================================================
#
#   1. PI keypair              — decrypts study data in the PI web frontend.
#   2. Receipt-signing keypair — signs withdrawal deletion receipts (Edge Fn).
#
# Run from Git Bash / any shell with openssl:   bash keygen.sh [output-dir]
# Refuses to overwrite existing keys. Keep every *private* key OUT of git and
# backed up offline.
# =============================================================================
set -euo pipefail

OUT="${1:-./keys}"
mkdir -p "$OUT"

guard() {
  if [ -f "$1" ]; then
    echo "ERROR: $1 already exists — refusing to overwrite. Move it away first." >&2
    exit 1
  fi
}

b64() { # portable one-line base64 (GNU uses -w0, macOS/LibreSSL uses -A)
  base64 -w0 "$1" 2>/dev/null || base64 -A "$1" 2>/dev/null || openssl base64 -A -in "$1"
}

# --- 1) PI keypair -----------------------------------------------------------
guard "$OUT/pi_private.pem"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT/pi_private.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$OUT/pi_private.pem" -out "$OUT/pi_private_pkcs8.pem"
openssl rsa -in "$OUT/pi_private.pem" -pubout -out "$OUT/pi_public.pem" 2>/dev/null

# --- 2) Receipt-signing keypair ----------------------------------------------
guard "$OUT/receipt_private.pem"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT/receipt_private.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$OUT/receipt_private.pem" -outform DER -out "$OUT/receipt_private_pkcs8.der"
openssl rsa -in "$OUT/receipt_private.pem" -pubout -outform DER -out "$OUT/receipt_public_spki.der" 2>/dev/null

RECEIPT_PRIV_B64=$(b64 "$OUT/receipt_private_pkcs8.der")
RECEIPT_PUB_B64=$(b64 "$OUT/receipt_public_spki.der")

# --- Where everything goes ----------------------------------------------------
cat <<EOF

Keys written to: $OUT

PI keypair
  pi_private.pem / pi_private_pkcs8.pem
      -> PI machine ONLY (pkcs8 is what the web frontend loads). Offline backup.
  pi_public.pem
      -> read automatically by seed_study.sh when seeding your study

Receipt-signing keypair
  receipt_private.pem
      -> offline backup ONLY; the server never stores the PEM
  private key (PKCS#8 DER, base64) -> Edge Function secret:

      supabase secrets set DELETION_SIGNING_KEY_PKCS8_B64=$RECEIPT_PRIV_B64

  public key (SPKI DER, base64)    -> app config (MbPulseConfig):

      deletionReceiptPublicKeyB64 = "$RECEIPT_PUB_B64"

NEVER commit anything in $OUT to version control.
EOF
