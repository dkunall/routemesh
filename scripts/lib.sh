# Shared helpers for demo.sh and bench.sh. Source, don't run.
BASE=${BASE:-http://127.0.0.1:8970}
OAUTH=${OAUTH:-http://127.0.0.1:8971}
MAILPIT=${MAILPIT:-http://127.0.0.1:8972}
DISPATCHER=${DISPATCHER:-dispatch@routemesh.example.test}
OPS=${OPS:-ops@routemesh.example.test}
HUB_LAT=12.9716
HUB_LNG=77.5946

# The app validates the issuer as http://oauth:8080/routemesh (its in-network name), and mock-oauth2-server
# derives the issuer from the Host header. The client_id becomes the token subject.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    -d scope=routemesh "$OAUTH/routemesh/token" | jq -r .access_token
}

# call AUTH_HEADER METHOD PATH [BODY] [EXTRA_CURL_ARGS...]; prints the body, then the status on its own line.
# AUTH_HEADER is a full header ("Authorization: Bearer ..." or "X-Device-Token: ...") or empty.
call() {
  local auth=$1 method=$2 path=$3 body=${4:-}
  shift 4 2> /dev/null || shift $#
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" ${auth:+-H "$auth"} \
    -H 'Content-Type: application/json' ${body:+-d "$body"} "$@"
}

# expect STATUS AUTH METHOD PATH [BODY] [EXTRA...]; prints the body or fails loudly (never prints the auth header).
expect() {
  local want=$1 out status
  shift
  out=$(call "$@")
  status=${out##*$'\n'}
  if [[ $status != "$want" ]]; then
    echo "FAIL: $2 $3 -> $status (wanted $want): ${out%$'\n'*}" >&2
    exit 1
  fi
  printf '%s' "${out%$'\n'*}"
}

bearer() { printf 'Authorization: Bearer %s' "$1"; }
device() { printf 'X-Device-Token: %s' "$1"; }

check() {
  if [[ $2 == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', wanted '$3'" >&2; exit 1; fi
}

# until_eq SECONDS WANT CMD...; reruns CMD until it prints WANT, or fails after SECONDS.
until_eq() {
  local limit=$1 want=$2 got=""
  shift 2
  for ((i = 0; i < limit; i++)); do
    got=$("$@" 2> /dev/null || true)
    [[ $got == "$want" ]] && { echo "$got"; return 0; }
    sleep 1
  done
  echo "timed out after ${limit}s; last value '$got', wanted '$want'" >&2
  return 1
}

# mails TO SUBJECT_PREFIX; number of mails to TO whose subject starts with SUBJECT_PREFIX.
mails() {
  curl -sf -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:\"$1\"" \
    | jq --arg s "$2" '[.messages[] | select(.Subject | startswith($s))] | length'
}

# otp_for EMAIL; the six-digit code from the newest "Your delivery code" mail.
otp_for() {
  local id
  id=$(curl -sf -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:\"$1\"" \
    | jq -r '[.messages[] | select(.Subject | startswith("Your delivery code"))][0].ID')
  curl -sf "$MAILPIT/api/v1/message/$id" | jq -r .Text | grep -oE 'door: [0-9]{6}' | head -1 | cut -c7-
}

day_ahead() { TZ=Asia/Kolkata date -d "+$1 days" +%F; }

# off LAT LNG KM_EAST KM_NORTH; prints "lat lng".
off() { awk -v a="$1" -v b="$2" -v e="$3" -v n="$4" 'BEGIN { printf "%.6f %.6f", a + n / 110.574, b + e / (111.32 * cos(a * 3.14159265 / 180)) }'; }

# toward LAT LNG TO_LAT TO_LNG KM: the point KM along the straight line to TO (or TO itself if it is closer).
toward() { awk -v a="$1" -v b="$2" -v c="$3" -v d="$4" -v k="$5" 'BEGIN {
  dy = (c - a) * 110.574; dx = (d - b) * 111.32 * cos(a * 3.14159265 / 180); dist = sqrt(dx * dx + dy * dy);
  f = (dist > k) ? k / dist : 1; printf "%.6f %.6f", a + (c - a) * f, b + (d - b) * f }'; }

# pings_json FROM_LAT FROM_LNG TO_LAT TO_LNG N EVERY_SEC [END_OFFSET_SEC]; N fixes on a line, the last one at now - END_OFFSET.
pings_json() {
  local now
  now=$(date -u +%s)
  awk -v a="$1" -v b="$2" -v c="$3" -v d="$4" -v n="$5" -v s="$6" -v e="${7:-0}" -v now="$now" 'BEGIN {
    printf "{\"pings\":[";
    for (i = 0; i < n; i++) {
      f = (n == 1) ? 1 : i / (n - 1); t = now - e - (n - 1 - i) * s;
      cmd = "date -u -d @" t " +%Y-%m-%dT%H:%M:%SZ"; cmd | getline ts; close(cmd);
      printf "%s{\"lat\":%.6f,\"lng\":%.6f,\"at\":\"%s\"}", (i ? "," : ""), a + (c - a) * f, b + (d - b) * f, ts;
    }
    printf "]}";
  }'
}

percentile() {
  sort -n "$1" | awk -v p="$2" '{a[NR]=$1} END {i=int((p/100)*NR+0.999); if (i<1) i=1; print a[i]}'
}

# A tiny synthetic JPEG-looking payload for proof of delivery (magic bytes plus noise).
photo() { { printf '\xff\xd8\xff'; head -c 253 /dev/urandom; } | base64 -w0; }
