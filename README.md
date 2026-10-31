# RouteMesh

A dispatch and live-tracking backend for one delivery hub's day. It plans van routes that respect time windows and capacity, slots in late orders without re-planning everything, follows drivers on the road, keeps the ETA a customer sees honest, and closes each stop with a proof of delivery or a recorded exception.

It is a side project to work through the parts of last-mile delivery that come *after* a parcel has a carrier: routing, tracking and the doorstep. It doesn't choose carriers or allocate between them. My other repo, [shipflow](https://github.com/dkunall/shipflow), does that, and the two share no code.

Java 21, Spring Boot 3.5, PostgreSQL 16 + PostGIS, Redis, Kafka (KRaft), H3. Everything runs on synthetic data and uses no map or routing APIs.

## What it does

- **Day planning (VRP with time windows).** `POST /v1/hubs/{id}/plans` builds routes for every unplanned shipment for a date. It builds two starting plans (cheapest insertion and nearest neighbour), improves each with 2-opt and relocate moves inside a time budget (default 1 s), and keeps the shorter. Each van has a capacity and a shift, and each stop has a time window and a service time. The response includes a nearest-neighbour baseline computed on the same input, so the gain is shown next to every plan rather than claimed once.
- **Late orders.** `POST /v1/routes/{id}/insertions` inserts a stop at its cheapest feasible position. `POST /v1/dispatch:auto` looks for nearby drivers with H3 rings and tries the shipment on each candidate route, including routes already on the road (a driver can't be sent back for stops already done). If nothing fits, it opens a new route on a van that has none that day, and only then answers `422 no-feasible-route`. Route edits carry `expectedVersion`, and a stale version gets `409 version-conflict`, so two dispatchers can't overwrite each other.
- **Live tracking.** Devices send location fixes in batches. The batches go to Kafka (`routemesh.pings`, keyed by driver). A consumer then:
  - drops duplicate, stale and physically impossible fixes
  - updates the driver's H3 cell in Redis
  - learns travel speed per (H3 cell, hour of day) as an EWMA, falling back to a coarser area
  - fires hub and stop geofences
  - recomputes the ETA of the stops still ahead
  - raises one `SLA_RISK` alert when a stop can no longer make its window
- **Offline-safe driver events.** Scans and status changes carry a per-device sequence number. An event that arrives early waits for the missing ones (`WAITING_FOR_GAP`, released when they arrive or after a timeout). A resent batch comes back as `DUPLICATE`, and an illegal transition is `REJECTED` with a reason, without touching the rest of the batch.
- **Proof of delivery.** When a parcel goes out for delivery, the recipient gets a 6-digit code by mail (stored only as an HMAC). The POD needs the code, a position within 150 m of the address (checked in PostGIS) and a JPEG/PNG photo. It is idempotent: the same proof replays, and a different one gets `409`. Five wrong codes lock the code.
- **Exceptions.**
  - A failed attempt has to be made at the address. It mails the customer, and the parcel rolls to the next day.
  - Three failed attempts send it back to the hub (RTO, with a mail to ops).
  - Returns are collected with a scan and handed in at the hub.
- **Public tracking page.** `GET /v1/track/{token}` shows status, ETA, the driver's first name and a coarse driver area (an H3 cell centre, only while the parcel is out for delivery). It shows no internal ids or contact details, and the link expires after delivery. Customers can reschedule from the link (rate-limited, and only before the parcel is planned) and ask for the code to be resent.

## Layout

The code is hexagonal per module (`domain` → `application` → `adapter`), and ArchUnit tests enforce it: domain code has no Spring or JDBC, no module reaches into another module's adapters, and there are no package cycles.

```
fleet         hubs, vans, drivers, device tokens
shipment      shipments, state machine, idempotent create, reschedule
routing       solver (insertion, 2-opt, relocate, NN baseline), plans, insertions, auto-dispatch
tracking      device events and sequencing, ping pipeline, ETA, geofences, SLA risk, public page
pod           proof of delivery
geo           H3 cell index, Redis driver index, haversine
notification  mails from shipment events
outbox        transactional outbox -> Kafka (routemesh.shipment-events)
idempotency   Idempotency-Key storage for POST /v1/shipments
```

Auth comes in three kinds:
- Dispatchers and shippers use JWTs from an OAuth2 issuer. Dispatchers are a configured list of subjects.
- Devices use opaque per-device tokens (`X-Device-Token`, stored as SHA-256).
- The public page uses unguessable tracking tokens.

## Run it

You need Docker with Compose v2, plus `curl`, `jq` and `awk` for the scripts.

```
./scripts/up.sh        # builds the image, writes fresh secrets to ~/.config/routemesh/compose.env (mode 600), waits for health
./scripts/demo.sh      # walks a delivery day end to end, checking each step
./scripts/bench.sh     # a few rough API timings and a concurrent-insert race
./scripts/down.sh      # stops everything and removes the volumes
```

Ports (all bound to 127.0.0.1):
- 8970: the API
- 8971: a mock OAuth2 server ([mock-oauth2-server](https://github.com/navikt/mock-oauth2-server))
- 8972: Mailpit, where you can read the code and notification mails

The demo:
1. creates a hub in central Bengaluru with three vans and three drivers
2. creates 24 shipments for tomorrow, plus one idempotent replay
3. plans the day and prints the plan next to the baseline
4. dispatches a route
5. sends scans out of order and then resends them
6. drives the van out of the hub fence
7. reads the delivery code from Mailpit and tries it too far away, with the wrong code, and then correctly
8. records a failed attempt, first away from the address and then at it
9. auto-dispatches and inserts late orders, including a stale-version insert
10. reschedules from the tracking link

It drives tomorrow's route right now, so ETAs on the page are relative to the current time.

Tests: `mvn verify` runs:
- 39 unit tests and 7 ArchUnit rules
- 14 integration tests on Testcontainers (PostGIS, Redis, Kafka, Mailpit, mock OAuth2): delivery flow, device sync, routing concurrency, and exceptions

Four of the integration tests are concurrency or idempotency checks:
- concurrent plans never assign a parcel twice
- parallel inserts on the same route version let exactly one through
- concurrent shipment creates with one Idempotency-Key make one shipment
- the same event batch from eight concurrent retries applies once

## Numbers

All of these come from synthetic instances on one 8-core box with JDK 21. The bench code is in `src/main/java/com/routemesh/bench` and `routing/bench`, and the full tables are in [`docs/bench`](docs/bench).

**Planner vs nearest-neighbour baseline.** 10 seeds per row, 150 kg vans, a 15 km radius, and travel as straight-line distance × 1.35 at 22 km/h.

| jobs | layout | NN km | planner km | change |
|---:|---|---:|---:|---:|
| 100 | random | 889.0 | 639.3 | −28.1% |
| 100 | clustered | 568.5 | 361.6 | −36.4% |
| 100 | mixed | 810.5 | 569.1 | −29.8% |
| 200 | random | 1436.7 | 1057.8 | −26.4% |
| 200 | clustered | 889.1 | 618.1 | −30.5% |
| 200 | mixed | 1270.4 | 938.9 | −26.1% |

- Cheapest insertion alone gets −7% to −23%. Most of the remaining gain comes from local search.
- Every instance assigned all jobs.
- The whole planner finishes in 3–11 ms (median) at these sizes, so the 1 s budget is generous.
- The objective is km only, with no fixed cost per van. The planner sometimes uses more vans than cheapest insertion alone (10.3 vs 9.8 on 100 random jobs).
- The second start exists because of a real miss. An early version used only the insertion start, and on the CI demo day it planned 112.73 km against the baseline's 109.36. `SolverTest` reproduces that shape (24 parcels, three vans, 200 seeds; seed 14 failed before the fix).
- Nearest neighbour is a weak baseline. I haven't compared against an exact solver or OR-Tools, so I can't say how far these routes are from optimal.

**Late orders: incremental insert vs full re-solve.** 10 orders added one at a time.

| jobs | insert, median | re-solve, median | final km, insert vs re-solve |
|---:|---:|---:|---:|
| 100 | 0.72 ms | 3.30 ms | 637.7 vs 625.9 |
| 200 | 1.97 ms | 10.65 ms | 1003.3 vs 986.9 |

Inserting is 4.6–5.4× faster and costs 1.7–1.9% more km. That's why the planner is meant to run once for the day and inserts handle the rest.

**ETA: learned per-cell speeds vs a static speed.**
- 4000 training trips and 1000 held-out trips.
- The synthetic city has slow central cells and rush hours.

| model | MAE off-peak | MAE rush |
|---|---:|---:|
| static (planner model, 22 km/h) | 6.11 min | 9.59 min |
| one fitted speed (17.0 km/h) | 5.26 min | 10.31 min |
| learned (H3 cell × hour EWMA) | 3.24 min | 6.29 min |

- The mean held-out trip was 27.6 min.
- Only 43% of lookups hit a learned cell. The rest fell back to the area speed.
- The caveat matters here. I generated the city with exactly the kind of structure this model can learn (per-area speeds and hourly peaks). Real traffic would be noisier, so read this as "the pipeline works and the fallback is sane", not as a forecast of accuracy.

**Nearby drivers: H3 k-ring vs scanning everyone.** Res 9, k = 6 (1.89 km radius), 2000 queries per row.

| drivers | scan, median | k-ring, median | distance checks per query |
|---:|---:|---:|---:|
| 1,000 | 57.9 µs | 8.5 µs | 1000 vs 19 |
| 10,000 | 580 µs | 28.3 µs | 10000 vs 194 |
| 50,000 | 2938 µs | 104 µs | 50000 vs 971 |

- Recall is 0.997. A hexagon ring doesn't cover the whole circle, so a few drivers near the radius are missed. For dispatch that's fine, but it isn't exact.
- These numbers are in memory. The service keeps the cells in Redis and pays a round trip per lookup.

**API (scripts/bench.sh, local compose, sequential).** One run:
- shipment create: p50 6.0 ms, p95 10.3 ms
- a 20-fix ping batch accepted: p50 9.5 ms, p95 11.3 ms (processing happens after, behind Kafka)
- 12 parallel inserts with the same `expectedVersion`: one 200 and eleven 409s

## Limitations

- **No road network.** Distance is haversine × 1.35 and time uses a fixed planning speed. Routes are plausible, not drivable turn by turn. A real deployment would replace the `TravelModel` record with a port backed by OSRM/Valhalla or a precomputed matrix.
- **The solver is a heuristic.** There's no optimality gap, no multi-depot or pickup-and-delivery pairing, and no driver breaks. Time windows are hard, and when nothing fits, a shipment is left unassigned rather than late. `windowsRelaxed` in auto-dispatch is the only soft path.
- **One region, one instance.** The schema is multi-hub, but nothing has been tested for sharding, multi-instance consumers beyond Kafka partitioning, or time zones other than Asia/Kolkata.
- **ETA learning is basic.** It uses an EWMA per cell and hour with no day-of-week effects, no incident handling and no uncertainty band. Numbers on synthetic data say little about real roads.
- **The GPS filter is simple.** It checks age, ordering, duplicates and implied speed. There's no map matching, and spoofing isn't detected beyond the impossible-speed rule.
- **Proof of delivery is checked, not verified.** The service checks the photo type and size, not its content. The code goes by mail only (no SMS), and the mail is local (Mailpit).
- **Auth is a demo setup.** It uses a mock OAuth2 server, dispatchers come from configuration, and there's no shipper tenancy beyond "you see your own shipments". Device tokens are stored as SHA-256, but there's no rotation endpoint and no device attestation.
- **Retention is a simple hourly job** that deletes old pings and expired tracking pages. POD photos stay in Postgres, which is fine for a demo and wrong at scale (use object storage with its own retention).

## CI

GitHub Actions runs two jobs:
- **test:** `mvn verify` (all tests, Testcontainers on the runner), plus a short run of each offline bench.
- **image:** builds the image, scans it with Trivy (HIGH/CRITICAL, unfixed ignored, two documented ignores in `.trivyignore`), then brings up the compose stack and runs `demo.sh` and `bench.sh` against it.
