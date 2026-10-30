#!/usr/bin/env bash
# Rough API numbers from one machine against the compose stack, not a load test. Prints measured values only.
# The solver, ETA and nearby-driver benches run offline on the JVM; see docs/bench/ and the README.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh
CREATES=${CREATES:-40}
BATCHES=${BATCHES:-30}
PARALLEL=${PARALLEL:-12}
RUN=$(date +%s)
OUT=$(mktemp -d /tmp/routemesh-bench.XXXXXX)
trap 'rm -rf "$OUT"' EXIT
D=$(bearer "$(token "$DISPATCHER")")
S=$(bearer "$(token "bench-$RUN@routemesh.example.test")")
DATE=$(day_ahead 2)
ms() { awk '{printf "%.2f\n", $1 * 1000}'; }

HUB=$(expect 201 "$D" POST /v1/hubs "{\"code\":\"BENCH$RUN\",\"name\":\"Bench hub\",\"lat\":$HUB_LAT,\"lng\":$HUB_LNG}" | jq -r .id)
for i in 1 2 3 4; do
  expect 201 "$D" POST "/v1/hubs/$HUB/vehicles" "{\"code\":\"BV$RUN-$i\",\"capacityKg\":400,\"shiftStart\":\"08:00\",\"shiftEnd\":\"20:00\"}" > /dev/null
done
DRV=$(expect 201 "$D" POST "/v1/hubs/$HUB/drivers" "{\"code\":\"BD$RUN\",\"name\":\"Bench Driver\"}" | jq -r .id)
DEV=$(device "$(expect 201 "$D" POST "/v1/drivers/$DRV/devices" '{}' | jq -r .token)")

shipment_body() {
  read -r lat lng <<< "$(off $HUB_LAT $HUB_LNG "$2" "$3")"
  printf '{"hubId":"%s","reference":"B-%s-%s","kind":"DELIVERY","lat":%s,"lng":%s,"address":"Synthetic","recipientName":"Bench",' \
    "$HUB" "$RUN" "$1" "$lat" "$lng"
  printf '"recipientEmail":"b-%s-%s@routemesh.example.test","recipientPhone":"+910000000000","serviceDate":"%s",' "$RUN" "$1" "$DATE"
  printf '"windowStart":"09:00","windowEnd":"20:00","weightKg":4}'
}

echo "shipment create ($CREATES, sequential, fresh Idempotency-Key each)..."
for ((i = 0; i < CREATES; i++)); do
  body=$(shipment_body "$i" "$(awk -v i=$i 'BEGIN{print (i % 9) - 4}')" "$(awk -v i=$i 'BEGIN{print int(i / 9) - 2}')")
  curl -s -o /dev/null -w '%{time_total}\n' -X POST "$BASE/v1/shipments" -H "$S" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: bench-$RUN-$i" -d "$body" | ms >> "$OUT/create"
done
echo "  p50 $(percentile "$OUT/create" 50) ms, p95 $(percentile "$OUT/create" 95) ms"

echo "plan $CREATES stops over 4 vehicles (one call)..."
t=$(curl -s -o "$OUT/plan.json" -w '%{time_total}' -X POST "$BASE/v1/hubs/$HUB/plans" -H "$D" -H 'Content-Type: application/json' \
  -d "{\"serviceDate\":\"$DATE\"}" | ms)
echo "  $t ms wall; $(jq -r '"\(.routes | length) routes, \(.totalKm) km vs baseline \(.baselineKm) km, solver \(.solveMillis) ms"' "$OUT/plan.json")"

echo "ping batches ($BATCHES batches of 20 fixes, sequential)..."
for ((i = 0; i < BATCHES; i++)); do
  read -r a b <<< "$(off $HUB_LAT $HUB_LNG "$(awk -v i=$i 'BEGIN{print i * 0.2}')" 1)"
  read -r c d <<< "$(off $HUB_LAT $HUB_LNG "$(awk -v i=$i 'BEGIN{print i * 0.2 + 0.2}')" 1)"
  body=$(pings_json "$a" "$b" "$c" "$d" 20 1 $(((BATCHES - i) * 20)))
  curl -s -o /dev/null -w '%{time_total}\n' -X POST "$BASE/v1/drivers/$DRV/pings:batch" -H "$DEV" \
    -H 'Content-Type: application/json' -d "$body" | ms >> "$OUT/ping"
done
echo "  p50 $(percentile "$OUT/ping" 50) ms, p95 $(percentile "$OUT/ping" 95) ms (accept only; the pipeline runs behind Kafka)"

echo "$PARALLEL parallel insertions on one route, all with the same expectedVersion..."
ROUTE=$(jq -r '.routes[0].routeId' "$OUT/plan.json")
VER=$(expect 200 "$D" GET "/v1/routes/$ROUTE" | jq .version)
for ((i = 0; i < PARALLEL; i++)); do
  expect 201 "$S" POST /v1/shipments "$(shipment_body "race$i" 0.5 0.5)" -H "Idempotency-Key: bench-$RUN-race$i" | jq -r .id
done > "$OUT/race-ids"
xargs -P "$PARALLEL" -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/v1/routes/$ROUTE/insertions" -H "$D" \
  -H 'Content-Type: application/json' -d "{\"shipmentId\":\"{}\",\"expectedVersion\":$VER}" < "$OUT/race-ids" > "$OUT/race"
echo "  status codes: $(sort "$OUT/race" | uniq -c | awk '{printf "%s x%s  ", $2, $1}')"
echo "  route version $VER -> $(expect 200 "$D" GET "/v1/routes/$ROUTE" | jq .version)"
