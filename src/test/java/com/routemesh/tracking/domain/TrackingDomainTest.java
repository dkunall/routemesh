package com.routemesh.tracking.domain;

import com.routemesh.geo.domain.Ping;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.routemesh.geo.domain.CellIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.geo.domain.Haversine;
import com.routemesh.routing.domain.TravelModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TrackingDomainTest {

    private static final Instant T0 = Instant.parse("2026-10-30T04:30:00Z");
    private static final GeoPoint A = new GeoPoint(12.9716, 77.5946);

    // --- DeviceSequence

    @Test
    void readyReturnsOnlyTheContiguousRun() {
        assertThat(DeviceSequence.ready(3, new TreeSet<>(List.of(4L, 5L, 7L, 8L)))).containsExactly(4L, 5L);
        assertThat(DeviceSequence.ready(3, new TreeSet<>(List.of(5L, 6L)))).isEmpty();
        assertThat(DeviceSequence.ready(0, new TreeSet<>(List.of(1L)))).containsExactly(1L);
        assertThat(DeviceSequence.ready(5, new TreeSet<>(List.of(2L, 3L, 6L)))).containsExactly(6L);
    }

    @Test
    void anyArrivalOrderAppliesEverythingInOrderOnce() {
        var rnd = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            var seqs = new ArrayList<Long>();
            for (long s = 1; s <= 30; s++) {
                seqs.add(s);
            }
            Collections.shuffle(seqs, rnd);
            long applied = 0;
            var waiting = new TreeSet<Long>();
            var order = new ArrayList<Long>();
            for (long s : seqs) {
                if (s <= applied) {
                    continue;
                }
                waiting.add(s);
                for (long r : DeviceSequence.ready(applied, waiting)) {
                    order.add(r);
                    waiting.remove(r);
                    applied = r;
                }
            }
            assertThat(order).hasSize(30).isSorted();
            assertThat(waiting).isEmpty();
        }
    }

    @Test
    void skippingAGapUnblocksTheRest() {
        var waiting = new TreeSet<>(List.of(9L, 10L));
        long cursor = DeviceSequence.skipTo(waiting.first());
        assertThat(cursor).isEqualTo(8);
        assertThat(DeviceSequence.ready(cursor, waiting)).containsExactly(9L, 10L);
    }

    // --- PingFilter

    private final PingFilter filter = new PingFilter(130, 8, Duration.ofHours(6));

    private static Ping ping(int sec, GeoPoint p) {
        return new Ping("d1", T0.plusSeconds(sec), p);
    }

    private static GeoPoint east(double km) {
        return new GeoPoint(A.lat(), A.lng() + km / (111.32 * Math.cos(Math.toRadians(A.lat()))));
    }

    @Test
    void sortsDropsDuplicatesStaleAndJumps() {
        var batch = List.of(
                ping(20, east(0.10)),
                ping(0, east(0)),
                ping(10, east(0.05)),
                ping(10, east(0.05)),            // duplicate timestamp
                ping(30, east(40)),               // 40 km in 10 s: GPS jump
                ping(40, east(0.20)),
                new Ping("d1", T0.minus(Duration.ofHours(7)), A)); // too old
        var r = filter.apply(batch, null, T0.plusSeconds(60));
        assertThat(r.accepted()).extracting(Ping::at)
                .containsExactly(T0, T0.plusSeconds(10), T0.plusSeconds(20), T0.plusSeconds(40));
        assertThat(r.duplicates()).isEqualTo(1);
        assertThat(r.outliers()).isEqualTo(1);
        assertThat(r.stale()).isEqualTo(1);
        assertThat(r.last().at()).isEqualTo(T0.plusSeconds(40));
    }

    @Test
    void pingsNotAfterThePreviousBatchAreStale() {
        var prev = ping(100, east(0));
        var r = filter.apply(List.of(ping(90, east(0.01)), ping(100, east(0.01)), ping(110, east(0.05))), prev,
                T0.plusSeconds(200));
        assertThat(r.stale()).isEqualTo(2);
        assertThat(r.accepted()).hasSize(1);
    }

    @Test
    void storedTrackIsSimplifiedButKeepsEnds() {
        var batch = new ArrayList<Ping>();
        for (int i = 0; i <= 60; i++) {
            batch.add(ping(i * 5, east(i * 0.04)));   // straight line, 29 km/h
        }
        var r = filter.apply(batch, null, T0.plusSeconds(400));
        assertThat(r.accepted()).hasSize(61);
        assertThat(r.stored()).hasSizeLessThan(5);
        assertThat(r.stored().getFirst()).isEqualTo(batch.getFirst());
        assertThat(r.stored().getLast()).isEqualTo(batch.getLast());
    }

    // --- ETA

    /** Square cells of ~1 km: enough to test the estimator without native H3. */
    private static final CellIndex GRID = new CellIndex() {
        @Override
        public long cell(GeoPoint p, int resolution) {
            return (long) Math.floor(p.lng() * 100) * 100_000 + (long) Math.floor(p.lat() * 100);
        }

        @Override
        public List<Long> ring(long origin, int k) {
            return List.of(origin);
        }

        @Override
        public GeoPoint center(long cell) {
            return A;
        }

        @Override
        public double edgeKm(int resolution) {
            return 0.5;
        }
    };

    private final StaticEta fallback = new StaticEta(new TravelModel(1.35, 22));

    @Test
    void learnedUsesCellSpeedThenAreaThenFallback() {
        var to = east(5);
        double km = Haversine.km(A, to);
        SegmentSpeeds cellsAt30 = speeds(30, Double.NaN);
        SegmentSpeeds areaAt15 = speeds(Double.NaN, 15);
        SegmentSpeeds none = speeds(Double.NaN, Double.NaN);
        assertThat(new LearnedEta(GRID, cellsAt30, fallback, 9).minutes(A, to, 10)).isCloseTo(km / 30 * 60, within(0.01));
        assertThat(new LearnedEta(GRID, areaAt15, fallback, 9).minutes(A, to, 10)).isCloseTo(km / 15 * 60, within(0.01));
        assertThat(new LearnedEta(GRID, none, fallback, 9).minutes(A, to, 10))
                .isCloseTo(fallback.minutes(A, to, 10), within(0.01));
        assertThat(new LearnedEta(GRID, cellsAt30, fallback, 9).minutes(A, A, 10)).isZero();
    }

    @Test
    void learnedNeverAssumesACrawl() {
        var to = east(1);
        double minutes = new LearnedEta(GRID, speeds(0.1, Double.NaN), fallback, 9).minutes(A, to, 10);
        assertThat(minutes).isCloseTo(Haversine.km(A, to) / 3 * 60, within(0.01));
    }

    @Test
    void ewmaStartsAtFirstSampleAndCounts() {
        var e = Ewma.EMPTY.add(20, 0.2);
        assertThat(e.value()).isEqualTo(20);
        e = e.add(30, 0.2);
        assertThat(e.value()).isCloseTo(22, within(1e-9));
        assertThat(e.samples()).isEqualTo(2);
    }

    @Test
    void slaRiskUsesTheMargin() {
        var end = T0.plus(Duration.ofHours(1));
        assertThat(SlaRisk.atRisk(end.minus(Duration.ofMinutes(11)), end, Duration.ofMinutes(10))).isFalse();
        assertThat(SlaRisk.atRisk(end.minus(Duration.ofMinutes(9)), end, Duration.ofMinutes(10))).isTrue();
    }

    private static SegmentSpeeds speeds(double cell, double area) {
        return new SegmentSpeeds() {
            @Override
            public double kmh(long c, int hourOfDay) {
                return cell;
            }

            @Override
            public double areaKmh(int hourOfDay) {
                return area;
            }
        };
    }
}
