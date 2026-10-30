package com.routemesh.geo.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class GeoTest {

    @Test
    void haversineMatchesAKnownDistance() {
        // Bengaluru MG Road to Kempegowda airport is about 30 km in a straight line
        double km = Haversine.km(new GeoPoint(12.9756, 77.6050), new GeoPoint(13.1986, 77.7066));
        assertThat(km).isBetween(26.0, 28.0);
    }

    @Test
    void offsetIsTheInverseOfDistance() {
        var a = new GeoPoint(12.97, 77.59);
        for (double bearing = 0; bearing < 360; bearing += 45) {
            assertThat(Haversine.km(a, Haversine.offset(a, 3.5, bearing))).isCloseTo(3.5, offset(1e-6));
        }
    }

    @Test
    void rejectsImpossibleCoordinates() {
        assertThatThrownBy(() -> new GeoPoint(91, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void douglasPeuckerDropsPointsOnAStraightLineAndKeepsTheCorner() {
        var start = new GeoPoint(12.97, 77.59);
        var track = new ArrayList<GeoPoint>();
        for (int i = 0; i <= 10; i++) {
            track.add(Haversine.offset(start, i * 0.1, 90));       // 1 km east
        }
        var corner = track.getLast();
        for (int i = 1; i <= 10; i++) {
            track.add(Haversine.offset(corner, i * 0.1, 0));       // then 1 km north
        }
        List<GeoPoint> kept = DouglasPeucker.simplify(track, p -> p, 5);
        assertThat(kept).containsExactly(track.getFirst(), corner, track.getLast());
    }

    @Test
    void douglasPeuckerKeepsShortTracksWhole() {
        var two = List.of(new GeoPoint(1, 1), new GeoPoint(1, 2));
        assertThat(DouglasPeucker.simplify(two, p -> p, 1000)).isEqualTo(two);
    }
}
