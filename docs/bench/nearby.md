# Nearby-driver lookup

H3 res 9 (edge 0.201 km), k = 6 rings, exact radius 1.89 km, 2000 queries per row, drivers uniform in a 15 km disk, seed 11. JVM 21.0.13+9-LTS.

| drivers | method | us per query (median) | us (p95) | distance checks per query (mean) | found per query (mean) | recall vs scan |
|---:|---|---:|---:|---:|---:|---:|
| 1000 | scan all drivers | 57.9 | 67.7 | 1000 | 15.76 | - |
| 1000 | H3 k-ring buckets | 8.5 | 12.2 | 19 | 15.71 | 0.9972 |
| 10000 | scan all drivers | 580.2 | 664.9 | 10000 | 157.06 | - |
| 10000 | H3 k-ring buckets | 28.3 | 38.5 | 194 | 156.61 | 0.9971 |
| 50000 | scan all drivers | 2937.9 | 3364.4 | 50000 | 788.01 | - |
| 50000 | H3 k-ring buckets | 104.4 | 138.2 | 971 | 785.70 | 0.9971 |
