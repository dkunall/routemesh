#!/usr/bin/env bash
# End-to-end walk through a delivery day against the compose stack (scripts/up.sh first).
# Everything is synthetic: one hub in central Bengaluru, parcels scattered within 8 km, made-up recipients.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh
command -v jq > /dev/null || { echo "needs jq" >&2; exit 1; }

DT=$(token "$DISPATCHER")
SHOP=$(token "shop-$RANDOM@routemesh.example.test")
D=$(bearer "$DT")
S=$(bearer "$SHOP")
DATE=$(day_ahead 1)
RUN=$(date +%s)

echo "== access control"
check "no token on the dispatcher API" "$(call "" POST /v1/hubs '{}' | tail -1)" 401
check "a shipper cannot create hubs" "$(call "$S" POST /v1/hubs '{"code":"X","name":"X","lat":12.9,"lng":77.5}' | tail -1)" 403

echo "== fleet: one hub, three vans (150 kg), three drivers with device tokens"
HUB=$(expect 201 "$D" POST /v1/hubs "$(jq -nc --arg c "BLR$RUN" --argjson a $HUB_LAT --argjson b $HUB_LNG \
  '{code: $c, name: "Demo hub", lat: $a, lng: $b}')" | jq -r .id)
for i in 1 2 3; do
  expect 201 "$D" POST "/v1/hubs/$HUB/vehicles" "$(jq -nc --arg c "VAN$RUN-$i" \
    '{code: $c, capacityKg: 150, shiftStart: "08:00", shiftEnd: "20:00"}')" > /dev/null
done
declare -a DRIVER DEVTOK
for i in 1 2 3; do
  DRIVER[$i]=$(expect 201 "$D" POST "/v1/hubs/$HUB/drivers" "$(jq -nc --arg c "DRV$RUN-$i" --arg n "Driver $i Demo" \
    '{code: $c, name: $n}')" | jq -r .id)
  # the device token is kept in memory only, never printed
  DEVTOK[$i]=$(expect 201 "$D" POST "/v1/drivers/${DRIVER[$i]}/devices" '{}' | jq -r .token)
done
echo "  hub $HUB, drivers ${DRIVER[*]}"

echo "== 24 shipments for $DATE (seeded positions within 8 km)"
declare -A DROP TOKEN EMAIL
IDS=()
mapfile -t POINTS < <(awk -v seed="$RUN" -v a=$HUB_LAT -v b=$HUB_LNG 'BEGIN { srand(seed % 100000);
  for (i = 0; i < 24; i++) { d = 8 * sqrt(rand()); t = rand() * 6.2831853;
    printf "%.6f %.6f %d\n", a + d * cos(t) / 110.574, b + d * sin(t) / (111.32 * cos(a * 3.14159265 / 180)), 2 + int(rand() * 10) } }')
for i in "${!POINTS[@]}"; do
  read -r lat lng kg <<< "${POINTS[$i]}"
  email="recipient-$RUN-$i@routemesh.example.test"
  body=$(jq -nc --arg h "$HUB" --arg r "RM-$RUN-$i" --argjson a "$lat" --argjson b "$lng" --arg d "$DATE" \
    --arg e "$email" --argjson k "$kg" '{hubId: $h, reference: $r, kind: "DELIVERY", lat: $a, lng: $b,
    address: "Synthetic address", recipientName: "Demo Recipient", recipientEmail: $e, recipientPhone: "+910000000000",
    serviceDate: $d, windowStart: "09:00", windowEnd: "20:00", weightKg: $k}')
  r=$(expect 201 "$S" POST /v1/shipments "$body" -H "Idempotency-Key: demo-$RUN-$i")
  id=$(jq -r .id <<< "$r")
  IDS+=("$id"); DROP[$id]="$lat $lng"; TOKEN[$id]=$(jq -r .trackingToken <<< "$r"); EMAIL[$id]=$email
  [[ $i == 0 ]] && FIRST_BODY=$body
done
replay=$(curl -s -D - -o /dev/null -X POST "$BASE/v1/shipments" -H "$S" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$RUN-0" -d "$FIRST_BODY" | tr -d '\r' | awk -F': ' 'tolower($1) == "idempotent-replayed" {print $2}')
check "same Idempotency-Key replays instead of creating a second shipment" "$replay" true

echo "== plan the day"
PLAN=$(expect 200 "$D" POST "/v1/hubs/$HUB/plans" "{\"serviceDate\":\"$DATE\"}")
jq -r '"  \(.routes | length) routes, \(.totalKm) km planned vs \(.baselineKm) km nearest-neighbour baseline, "
  + "\(.unassigned | length) unassigned, solved in \(.solveMillis) ms"' <<< "$PLAN"
check "planned km is not worse than the baseline" "$(jq '.totalKm <= .baselineKm' <<< "$PLAN")" true
check "planning again finds nothing left to plan" "$(expect 200 "$D" POST "/v1/hubs/$HUB/plans" "{\"serviceDate\":\"$DATE\"}" | jq '.routes | length')" 0

ROUTE=$(jq -r '.routes[0].routeId' <<< "$PLAN")
VER=$(expect 200 "$D" GET "/v1/routes/$ROUTE" | jq .version)
expect 200 "$D" POST "/v1/routes/$ROUTE/dispatch" "{\"driverId\":\"${DRIVER[1]}\",\"expectedVersion\":$VER}" > /dev/null
DEV=$(device "${DEVTOK[1]}")
MYROUTE=$(expect 200 "$DEV" GET "/v1/drivers/${DRIVER[1]}/route")
mapfile -t STOPS < <(jq -r '.stops[].shipmentId' <<< "$MYROUTE")
echo "  route $ROUTE dispatched to driver 1 with ${#STOPS[@]} stops"
check "another driver's device cannot read it" "$(call "$(device "${DEVTOK[2]}")" GET "/v1/drivers/${DRIVER[1]}/route" | tail -1)" 403
check "a dispatcher token is not a device token" "$(call "$D" GET "/v1/drivers/${DRIVER[1]}/route" | tail -1)" 401

echo "== offline-safe scans: device sequence numbers, sent out of order and twice"
EV=$(jq -nc --args '[$ARGS.positional | to_entries[] | {deviceSeq: (.key + 1), shipmentId: .value, status: "PICKED_UP"}]' "${STOPS[@]}")
N=${#STOPS[@]}
late=$(jq -c '[.[0]]' <<< "$EV"); rest=$(jq -c '.[1:]' <<< "$EV")
r=$(expect 200 "$DEV" POST "/v1/drivers/${DRIVER[1]}/events:batch" "{\"events\":$rest}")
check "events after a missing sequence number wait" "$(jq -r '.results[0].result' <<< "$r")" WAITING_FOR_GAP
r=$(expect 200 "$DEV" POST "/v1/drivers/${DRIVER[1]}/events:batch" "{\"events\":$late}")
check "the missing one arrives and releases the rest" "$(jq -r '.results[0].result' <<< "$r")" APPLIED
until_eq 10 PICKED_UP bash -c "source ./lib.sh; call '$D' GET /v1/shipments/${STOPS[$((N - 1))]} | head -1 | jq -r .status" > /dev/null
r=$(expect 200 "$DEV" POST "/v1/drivers/${DRIVER[1]}/events:batch" "{\"events\":$EV}")
check "resending the whole batch is a no-op" "$(jq -r '[.results[].result] | unique | join(",")' <<< "$r")" DUPLICATE

echo "== live tracking: a fix at the hub, then driving out; the hub geofence marks parcels in transit"
read -r flat flng <<< "${DROP[${STOPS[0]}]}"
expect 202 "$DEV" POST "/v1/drivers/${DRIVER[1]}/pings:batch" "$(pings_json $HUB_LAT $HUB_LNG $HUB_LAT $HUB_LNG 1 1 120)" > /dev/null
read -r olat olng <<< "$(off $HUB_LAT $HUB_LNG 0.1 0)"
expect 202 "$DEV" POST "/v1/drivers/${DRIVER[1]}/pings:batch" "$(pings_json "$olat" "$olng" "$flat" "$flng" 8 12)" > /dev/null
until_eq 20 IN_TRANSIT bash -c "source ./lib.sh; call '$D' GET /v1/shipments/${STOPS[0]} | head -1 | jq -r .status" > /dev/null
echo "  ok   ${#STOPS[@]} parcels IN_TRANSIT after leaving the hub fence"

echo "== stop 1: out for delivery, code by mail, proof of delivery"
S1=${STOPS[0]}
expect 200 "$DEV" POST "/v1/drivers/${DRIVER[1]}/events:batch" \
  "{\"events\":[{\"deviceSeq\":$((N + 1)),\"shipmentId\":\"$S1\",\"status\":\"OUT_FOR_DELIVERY\"}]}" > /dev/null
until_eq 20 1 mails "${EMAIL[$S1]}" "Your delivery code" > /dev/null
until_eq 20 OUT_FOR_DELIVERY bash -c "source ./lib.sh; call '' GET /v1/track/${TOKEN[$S1]} | head -1 | jq -r .status" > /dev/null
PAGE=$(expect 200 "" GET "/v1/track/${TOKEN[$S1]}")
echo "  public page: $(jq -c '{status, eta, driverArea, driverName}' <<< "$PAGE")"
check "the page hides internal ids" "$(jq 'has("id") or has("routeId")' <<< "$PAGE")" false
OTP=$(otp_for "${EMAIL[$S1]}")
WRONG=111111; [[ $OTP == 111111 ]] && WRONG=222222
read -r flat flng <<< "${DROP[$S1]}"
read -r xlat xlng <<< "$(off "$flat" "$flng" 2 0)"
pod() { jq -nc --arg o "$1" --argjson a "$2" --argjson b "$3" --arg p "$(photo)" '{otp: $o, lat: $a, lng: $b, photoBase64: $p, photoType: "image/jpeg"}'; }
check "proof 2 km from the address" "$(call "$DEV" POST "/v1/shipments/$S1/pod" "$(pod "$OTP" "$xlat" "$xlng")" | head -1 | jq -r .code)" too-far-from-address
check "wrong code" "$(call "$DEV" POST "/v1/shipments/$S1/pod" "$(pod $WRONG "$flat" "$flng")" | head -1 | jq -r .code)" wrong-otp
check "right code at the door" "$(expect 200 "$DEV" POST "/v1/shipments/$S1/pod" "$(pod "$OTP" "$flat" "$flng")" | jq -r .status)" DELIVERED
until_eq 20 DELIVERED bash -c "source ./lib.sh; call '' GET /v1/track/${TOKEN[$S1]} | head -1 | jq -r .status" > /dev/null
until_eq 20 1 mails "${EMAIL[$S1]}" "Delivered" > /dev/null
echo "  ok   page and mail say DELIVERED"

echo "== stop 2: nobody home"
S2=${STOPS[1]}
read -r flat flng <<< "${DROP[$S2]}"
read -r xlat xlng <<< "$(off "$flat" "$flng" 3 0)"
r=$(expect 200 "$DEV" POST "/v1/drivers/${DRIVER[1]}/events:batch" "{\"events\":[
  {\"deviceSeq\":$((N + 2)),\"shipmentId\":\"$S2\",\"status\":\"OUT_FOR_DELIVERY\"},
  {\"deviceSeq\":$((N + 3)),\"shipmentId\":\"$S2\",\"status\":\"FAILED_ATTEMPT\",\"reason\":\"no answer\",\"lat\":$xlat,\"lng\":$xlng},
  {\"deviceSeq\":$((N + 4)),\"shipmentId\":\"$S2\",\"status\":\"FAILED_ATTEMPT\",\"reason\":\"no answer\",\"lat\":$flat,\"lng\":$flng}]}")
check "a failed attempt 3 km away is refused" "$(jq -r '.results[1].code' <<< "$r")" not-at-address
check "at the door it is recorded" "$(jq -r '.results[2].result' <<< "$r")" APPLIED
until_eq 20 1 mails "${EMAIL[$S2]}" "We missed you" > /dev/null
echo "  ok   attempt 1 of 3, rolled to the next day, customer mailed"

echo "== late orders"
late_order() {
  read -r lat lng <<< "$(off $HUB_LAT $HUB_LNG "$1" "$2")"
  expect 201 "$S" POST /v1/shipments "$(jq -nc --arg h "$HUB" --arg r "RM-$RUN-late$3" --argjson a "$lat" --argjson b "$lng" \
    --arg d "$DATE" --arg e "late-$RUN-$3@routemesh.example.test" '{hubId: $h, reference: $r, kind: "DELIVERY", lat: $a,
    lng: $b, address: "Synthetic address", recipientName: "Demo Recipient", recipientEmail: $e, recipientPhone: "+910000000000",
    serviceDate: $d, windowStart: "09:00", windowEnd: "20:00", weightKg: 3}')" -H "Idempotency-Key: late-$RUN-$3" | jq -r .id
}
L1=$(late_order 1.5 1.5 1)
r=$(expect 200 "$D" POST /v1/dispatch:auto "{\"shipmentId\":\"$L1\"}")
echo "  auto-dispatch: $(jq -c '{addedKm, candidatesEvaluated, onRoad}' <<< "$r")"
check "auto-dispatching it twice" "$(call "$D" POST /v1/dispatch:auto "{\"shipmentId\":\"$L1\"}" | head -1 | jq -r .code)" not-plannable
R2=$(jq -r '.routes[1].routeId // .routes[0].routeId' <<< "$PLAN")
V2=$(expect 200 "$D" GET "/v1/routes/$R2" | jq .version)
L2=$(late_order -2 1 2); L3=$(late_order -2.2 1.1 3)
expect 200 "$D" POST "/v1/routes/$R2/insertions" "{\"shipmentId\":\"$L2\",\"expectedVersion\":$V2}" > /dev/null
check "a second insert on the same stale version" "$(call "$D" POST "/v1/routes/$R2/insertions" "{\"shipmentId\":\"$L3\",\"expectedVersion\":$V2}" | head -1 | jq -r .code)" version-conflict

echo "== customer reschedules from the tracking link"
L4=$(late_order 4 -3 4)
T4=$(call "$S" GET "/v1/shipments/$L4" | head -1 | jq -r .trackingToken)
check "move it two days later" "$(expect 200 "" POST "/v1/track/$T4/reschedule" "{\"date\":\"$(day_ahead 3)\",\"windowStart\":\"10:00\",\"windowEnd\":\"12:00\"}" | jq -r .serviceDate)" "$(day_ahead 3)"
check "a date in the past" "$(call "" POST "/v1/track/$T4/reschedule" "{\"date\":\"$(day_ahead 0)\",\"windowStart\":\"10:00\",\"windowEnd\":\"12:00\"}" | head -1 | jq -r .code)" bad-date
check "a planned parcel cannot be moved from the link" "$(call "" POST "/v1/track/${TOKEN[${STOPS[2]:-$S2}]}/reschedule" "{\"date\":\"$(day_ahead 3)\",\"windowStart\":\"10:00\",\"windowEnd\":\"12:00\"}" | head -1 | jq -r .code)" not-reschedulable

echo "== timeline (dispatcher view) for stop 2"
call "$D" GET "/v1/shipments/$S2/timeline" | head -1 | jq -r '.[] | "  seq \(.deviceSeq // "-")  \(.status)  \(.state)  \(.outcome // "")"'
echo "demo finished"
