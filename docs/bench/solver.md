# Solver benchmark

JVM 21.0.13+9-LTS, 8 cores. Travel: haversine x 1.35 at 22 km/h. Seeds 1..10, local-search budget 1000 ms. Capacity 150 kg, 30 vehicles, 15 km radius.

## Full plan: nearest neighbour (baseline) vs cheapest insertion vs cheapest insertion + local search

| jobs | layout | method | km (mean) | vehicles (mean) | unassigned (total) | ms (median) | km vs baseline |
|---:|---|---|---:|---:|---:|---:|---:|
| 100 | RANDOM | nearest-neighbour | 889.0 | 14.2 | 0 | 1.1 | +0.0% |
| 100 | RANDOM | cheapest-insertion | 808.1 | 9.8 | 0 | 1.4 | -9.1% |
| 100 | RANDOM | insertion+2opt+relocate | 650.2 | 9.8 | 0 | 2.5 | -26.9% |
| 100 | CLUSTERED | nearest-neighbour | 568.5 | 11.2 | 0 | 0.8 | +0.0% |
| 100 | CLUSTERED | cheapest-insertion | 436.5 | 9.3 | 0 | 1.2 | -23.2% |
| 100 | CLUSTERED | insertion+2opt+relocate | 361.6 | 9.3 | 0 | 1.9 | -36.4% |
| 100 | MIXED | nearest-neighbour | 810.5 | 13.8 | 0 | 0.8 | +0.0% |
| 100 | MIXED | cheapest-insertion | 726.8 | 10.0 | 0 | 1.2 | -10.3% |
| 100 | MIXED | insertion+2opt+relocate | 572.2 | 10.0 | 0 | 2.2 | -29.4% |
| 200 | RANDOM | nearest-neighbour | 1436.7 | 23.4 | 0 | 2.4 | +0.0% |
| 200 | RANDOM | cheapest-insertion | 1309.0 | 17.9 | 0 | 4.2 | -8.9% |
| 200 | RANDOM | insertion+2opt+relocate | 1097.6 | 18.0 | 0 | 6.4 | -23.6% |
| 200 | CLUSTERED | nearest-neighbour | 889.1 | 20.0 | 0 | 2.2 | +0.0% |
| 200 | CLUSTERED | cheapest-insertion | 760.3 | 18.1 | 0 | 4.1 | -14.5% |
| 200 | CLUSTERED | insertion+2opt+relocate | 618.1 | 18.0 | 0 | 6.7 | -30.5% |
| 200 | MIXED | nearest-neighbour | 1270.4 | 22.9 | 0 | 2.3 | +0.0% |
| 200 | MIXED | cheapest-insertion | 1179.7 | 18.7 | 0 | 4.1 | -7.1% |
| 200 | MIXED | insertion+2opt+relocate | 976.4 | 18.7 | 0 | 6.3 | -23.1% |

## Late orders: incremental insert vs full re-solve (10 orders added one at a time)

| jobs before | method | ms per order (median) | ms per order (p95) | final km (mean) | unassigned (total) |
|---:|---|---:|---:|---:|---:|
| 100 | incremental insert + 2-opt on the touched route | 0.74 | 0.86 | 649.2 | 0 |
| 100 | full re-solve | 2.23 | 2.80 | 632.6 | 0 |
| 200 | incremental insert + 2-opt on the touched route | 1.95 | 2.10 | 1065.6 | 0 |
| 200 | full re-solve | 6.51 | 7.86 | 995.7 | 0 |
