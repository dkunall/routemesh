# ETA benchmark (synthetic city)

4000 training trips (216096 speed samples), 1000 held-out trips, seed 7. Trips 1-12 km, departures 07:00-21:59. Mean held-out trip 27.6 min.
H3 res 9 cells with >= 3 samples: 33142 of 57988 (cell, hour) keys; learned lookups that hit a cell 42.9%, fell back to the area speed 57.1%.

| model | hours | trips | MAE min | p90 abs err min | mean bias min |
|---|---|---:|---:|---:|---:|
| static | rush (08-10, 17-20) | 346 | 9.59 | 24.56 | -8.44 |
| static | other | 654 | 6.11 | 10.69 | +5.60 |
| static-fitted | rush (08-10, 17-20) | 346 | 10.31 | 25.95 | -9.60 |
| static-fitted | other | 654 | 5.26 | 9.34 | +4.50 |
| learned | rush (08-10, 17-20) | 346 | 6.29 | 14.94 | +3.45 |
| learned | other | 654 | 3.24 | 7.61 | +2.35 |

Static uses the planner's model (road factor 1.35 at 22 km/h); static-fitted uses one straight-line speed fitted to the training samples (17.0 km/h).
