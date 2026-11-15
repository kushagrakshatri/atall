#!/usr/bin/env bash
# Deploys the Atall connector to a Supabase project:
#   1. database migrations  (Management API by default: no DB password needed)
#   2. function secrets     (Management API; values never appear on a command line or in output)
#   3. the `atall` function (Supabase CLI, --use-api so no Docker; JWT verification off)
#
# Usage (from anywhere):
#   PROJECT_REF=ggmvzyyuksewkgbokrhe SUPABASE_ACCESS_TOKEN=… scripts/deploy.sh            # deploy
#   PROJECT_REF=… SUPABASE_ACCESS_TOKEN=… scripts/deploy.sh --check                        # read-only preflight
#   PROJECT_REF=… SUPABASE_ACCESS_TOKEN=… scripts/deploy.sh --cli                          # migrations via `supabase db push`
#   … scripts/deploy.sh --smoke                                                            # deploy, then run smoke.mjs
#   … scripts/deploy.sh --curl                                                             # function via the Management API multipart deploy (no CLI)
#
# Secrets: ATALL_OWNER_CODE and ATALL_ADMIN_TOKEN are taken from the environment if set; otherwise
# they're generated once and kept in .atall-secrets.local (mode 600, git-ignored) and reused. C1 adds
# ATALL_RELAY_SIGNING_KEY (P-256 PKCS8, base64), generated the same way the first time; rotating it means
# every paired phone must re-pair (it pins the relay public key at pairing).
# Optional pass-through if set: ATALL_CONSENT_URL, ATALL_ALLOWED_ORIGINS, ATALL_DEV_PAGE,
# ATALL_SEED_TASK, ATALL_PUBLIC_URL. SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY are injected by Supabase.
#
# Supabase CLI: uses `supabase` on PATH if present, else `npx -y supabase@latest` (Node 20+).

set -euo pipefail

MODE=deploy
MIGRATIONS=api
SMOKE=0
FN_DEPLOY=cli
for arg in "$@"; do
  case "$arg" in
    --curl) FN_DEPLOY=curl ;;
    --check) MODE=check ;;
    --cli) MIGRATIONS=cli ;;
    --smoke) SMOKE=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API="${SUPABASE_API_URL:-https://api.supabase.com}"
FN=atall

die() { echo "error: $*" >&2; exit 1; }
say() { echo "==> $*"; }

[[ -n "${PROJECT_REF:-}" ]] || die "set PROJECT_REF (e.g. ggmvzyyuksewkgbokrhe)"
[[ "$PROJECT_REF" =~ ^[a-z]{20}$ ]] || die "PROJECT_REF must be 20 lowercase letters"
[[ -n "${SUPABASE_ACCESS_TOKEN:-}" ]] || die "set SUPABASE_ACCESS_TOKEN (a Supabase personal access token)"
command -v curl >/dev/null || die "curl is required"
command -v python3 >/dev/null || die "python3 is required"

BASE_URL="https://${PROJECT_REF}.supabase.co/functions/v1/${FN}"

# Management API call. The token reaches curl as a config line on stdin (-K -), never argv or output.
#   api METHOD PATH [BODY_FILE] [extra curl args...]  → body on stdout; fails on HTTP >= 400
api() {
  local method="$1" path="$2" body="${3:-}"; shift 3 2>/dev/null || shift $#
  local args=(-sS --fail-with-body -X "$method" "${API}${path}" -H "Accept: application/json")
  if [[ -n "$body" ]]; then args+=(-H "Content-Type: application/json" --data-binary "@${body}"); fi
  printf 'header = "Authorization: Bearer %s"\n' "$SUPABASE_ACCESS_TOKEN" | curl -K - "${args[@]}" "$@"
}

sb() {
  if command -v supabase >/dev/null; then supabase "$@"; else npx -y supabase@latest "$@"; fi
}

json_get() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# ---------------------------------------------------------------------------
# Preflight (read-only)
# ---------------------------------------------------------------------------
say "Project ${PROJECT_REF}"
api GET "/v1/projects/${PROJECT_REF}" | json_get '"   name=%s region=%s status=%s postgres=%s" % (d.get("name"), d.get("region"), d.get("status"), (d.get("database") or {}).get("version"))' \
  || die "cannot read the project with this access token"

APPLIED_JSON="$(api GET "/v1/projects/${PROJECT_REF}/database/migrations")"
say "Applied migrations: $(printf '%s' "$APPLIED_JSON" | json_get '", ".join("%s %s" % (m.get("version"), m.get("name") or "") for m in d) or "(none)"')"

say "Functions: $(api GET "/v1/projects/${PROJECT_REF}/functions" | json_get '", ".join("%s v%s %s verify_jwt=%s" % (f.get("slug"), f.get("version"), f.get("status"), f.get("verify_jwt")) for f in d) or "(none)"')"
say "Secret names: $(api GET "/v1/projects/${PROJECT_REF}/secrets" | json_get '", ".join(sorted(s["name"] for s in d if not s["name"].startswith("SUPABASE_"))) or "(none)"')"
say "Supabase CLI: $(sb --version 2>/dev/null | tail -1)"

PENDING=()
for f in "$DIR"/supabase/migrations/*.sql; do
  b="$(basename "$f" .sql)"; v="${b%%_*}"; n="${b#*_}"
  if printf '%s' "$APPLIED_JSON" | V="$v" N="$n" B="$b" python3 -c 'import json,os,sys; d=json.load(sys.stdin); sys.exit(0 if any(m.get("version")==os.environ["V"] or m.get("name") in (os.environ["N"], os.environ["B"]) for m in d) else 1)'; then
    echo "   migration $b: already applied"
  else
    echo "   migration $b: pending"; PENDING+=("$f")
  fi
done

if [[ "$MODE" == check ]]; then
  say "Preflight only (--check): nothing changed. Function URL would be ${BASE_URL}/mcp"
  exit 0
fi

# ---------------------------------------------------------------------------
# 1. Migrations
# ---------------------------------------------------------------------------
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
if [[ "$MIGRATIONS" == cli ]]; then
  say "Migrations via supabase db push (CLI)"
  # With a scoped access token the CLI can push without a DB password; otherwise it reads SUPABASE_DB_PASSWORD from the env.
  SUPABASE_YES=1 sb db push --workdir "$DIR" --remote="$PROJECT_REF" --include-all --yes
else
  for f in "${PENDING[@]+"${PENDING[@]}"}"; do
    b="$(basename "$f" .sql)"
    say "Applying migration $b via Management API"
    F="$f" B="$b" python3 -c 'import json,os; print(json.dumps({"query": open(os.environ["F"]).read(), "name": os.environ["B"]}))' > "$TMP/migration.json"
    api POST "/v1/projects/${PROJECT_REF}/database/migrations" "$TMP/migration.json" -H "Idempotency-Key: atall-${b}" >/dev/null
  done
  # Make PostgREST see the new functions right away.
  printf '%s' '{"query":"NOTIFY pgrst, '"'"'reload schema'"'"';"}' > "$TMP/notify.json"
  api POST "/v1/projects/${PROJECT_REF}/database/query" "$TMP/notify.json" >/dev/null || echo "   (schema reload notify failed; PostgREST reloads on DDL anyway)"
fi

# ---------------------------------------------------------------------------
# 2. Secrets
# ---------------------------------------------------------------------------
SECRETS_FILE="$DIR/.atall-secrets.local"
if [[ -z "${ATALL_OWNER_CODE:-}" || -z "${ATALL_ADMIN_TOKEN:-}" ]]; then
  if [[ -f "$SECRETS_FILE" ]]; then
    # shellcheck disable=SC1090
    set -a; . "$SECRETS_FILE"; set +a
  else
    command -v openssl >/dev/null || die "openssl is required to generate secrets (or set ATALL_OWNER_CODE / ATALL_ADMIN_TOKEN)"
    umask 077
    { echo "ATALL_OWNER_CODE=${ATALL_OWNER_CODE:-$(openssl rand -base64 18 | tr -d '/+=' )}"
      echo "ATALL_ADMIN_TOKEN=${ATALL_ADMIN_TOKEN:-$(openssl rand -base64 33 | tr -d '/+=' )}"; } > "$SECRETS_FILE"
    chmod 600 "$SECRETS_FILE"
    set -a; . "$SECRETS_FILE"; set +a
    say "Generated ATALL_OWNER_CODE and ATALL_ADMIN_TOKEN into $(basename "$SECRETS_FILE") (mode 600; move them to your password manager)"
  fi
fi
if [[ -z "${ATALL_RELAY_SIGNING_KEY:-}" && -f "$SECRETS_FILE" ]] && grep -q '^ATALL_RELAY_SIGNING_KEY=' "$SECRETS_FILE"; then
  ATALL_RELAY_SIGNING_KEY="$(grep '^ATALL_RELAY_SIGNING_KEY=' "$SECRETS_FILE" | head -1 | cut -d= -f2-)"; export ATALL_RELAY_SIGNING_KEY
fi
if [[ -z "${ATALL_RELAY_SIGNING_KEY:-}" ]]; then
  command -v openssl >/dev/null || die "openssl is required to generate ATALL_RELAY_SIGNING_KEY"
  umask 077
  echo "ATALL_RELAY_SIGNING_KEY=$(openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')" >> "$SECRETS_FILE"
  chmod 600 "$SECRETS_FILE"
  set -a; . "$SECRETS_FILE"; set +a
  say "Generated ATALL_RELAY_SIGNING_KEY into $(basename "$SECRETS_FILE")"
fi
[[ ${#ATALL_ADMIN_TOKEN} -ge 16 ]] || die "ATALL_ADMIN_TOKEN must be at least 16 characters"
say "Setting function secrets: ATALL_OWNER_CODE ATALL_ADMIN_TOKEN ATALL_RELAY_SIGNING_KEY${ATALL_CONSENT_URL:+ ATALL_CONSENT_URL}${ATALL_ALLOWED_ORIGINS:+ ATALL_ALLOWED_ORIGINS}${ATALL_DEV_PAGE:+ ATALL_DEV_PAGE}${ATALL_SEED_TASK:+ ATALL_SEED_TASK}${ATALL_PUBLIC_URL:+ ATALL_PUBLIC_URL}"
python3 - > "$TMP/secrets.json" <<'PY'
import json, os
names = ["ATALL_OWNER_CODE", "ATALL_ADMIN_TOKEN", "ATALL_RELAY_SIGNING_KEY", "ATALL_CONSENT_URL", "ATALL_ALLOWED_ORIGINS", "ATALL_DEV_PAGE", "ATALL_SEED_TASK", "ATALL_PUBLIC_URL"]
print(json.dumps([{"name": n, "value": os.environ[n]} for n in names if os.environ.get(n)]))
PY
api POST "/v1/projects/${PROJECT_REF}/secrets" "$TMP/secrets.json" >/dev/null

# ---------------------------------------------------------------------------
# 3. Function
# ---------------------------------------------------------------------------
say "Deploying function ${FN} (no JWT verification; server-side bundle)"
if [[ "$FN_DEPLOY" == curl ]]; then
  # Management API multipart deploy: metadata + every file under the function dir, verify_jwt=false.
  ( cd "$DIR/supabase/functions/$FN"
    files=(); while IFS= read -r f; do files+=(-F "file=@${f};filename=${f}"); done < <(find . -name '*.ts' -o -name 'deno.json' | sed 's|^\./||' | sort)
    api POST "/v1/projects/${PROJECT_REF}/functions/deploy?slug=${FN}" "" \
      -F 'metadata={"entrypoint_path":"index.ts","name":"'"$FN"'","verify_jwt":false,"import_map_path":"deno.json"};type=application/json' "${files[@]}" \
      | json_get '"   deployed %s v%s %s verify_jwt=%s" % (d.get("slug"), d.get("version"), d.get("status"), d.get("verify_jwt"))' )
else
  SUPABASE_YES=1 sb functions deploy "$FN" --workdir "$DIR" --remote="$PROJECT_REF" --no-verify-jwt --use-api --yes
fi

say "Done."
echo "   MCP connector URL:   ${BASE_URL}/mcp"
echo "   Owner console:       ${BASE_URL}/dev  (served as text/plain on *.supabase.co; see README for the static dev.html)"
echo "   Smoke test:          ATALL_URL=${BASE_URL} ATALL_OWNER_CODE=… ATALL_ADMIN_TOKEN=… node scripts/smoke.mjs"

if [[ "$SMOKE" == 1 ]]; then
  say "Running smoke test"
  ATALL_URL="$BASE_URL" node "$DIR/scripts/smoke.mjs"
fi
