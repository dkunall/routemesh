package com.routemesh.shipment.domain;

import static com.routemesh.shipment.domain.ShipmentKind.DELIVERY;
import static com.routemesh.shipment.domain.ShipmentKind.RETURN;
import static com.routemesh.shipment.domain.ShipmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ShipmentStatusTest {

    @Test
    void deliveryHappyPath() {
        var path = new ShipmentStatus[] {CREATED, ASSIGNED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED};
        for (int i = 1; i < path.length; i++) {
            assertThat(path[i - 1].canMoveTo(DELIVERY, path[i])).as(path[i - 1] + "->" + path[i]).isTrue();
        }
    }

    @Test
    void returnHappyPathCollectsThenEndsAtHub() {
        var path = new ShipmentStatus[] {CREATED, ASSIGNED, OUT_FOR_DELIVERY, PICKED_UP, DELIVERED};
        for (int i = 1; i < path.length; i++) {
            assertThat(path[i - 1].canMoveTo(RETURN, path[i])).as(path[i - 1] + "->" + path[i]).isTrue();
        }
        assertThat(ASSIGNED.canMoveTo(RETURN, PICKED_UP)).isFalse();
    }

    @Test
    void failedAttemptsRetryOrGoBack() {
        assertThat(OUT_FOR_DELIVERY.canMoveTo(DELIVERY, FAILED_ATTEMPT)).isTrue();
        assertThat(FAILED_ATTEMPT.canMoveTo(DELIVERY, OUT_FOR_DELIVERY)).isTrue();
        assertThat(FAILED_ATTEMPT.canMoveTo(DELIVERY, RTO)).isTrue();
        assertThat(RTO.canMoveTo(DELIVERY, RETURNED)).isTrue();
        assertThat(FAILED_ATTEMPT.canMoveTo(RETURN, RTO)).isFalse();
        assertThat(FAILED_ATTEMPT.canMoveTo(RETURN, CANCELLED)).isTrue();
    }

    @Test
    void cannotSkipOrCancelOnceGoodsMoved() {
        assertThat(CREATED.canMoveTo(DELIVERY, DELIVERED)).isFalse();
        assertThat(ASSIGNED.canMoveTo(DELIVERY, OUT_FOR_DELIVERY)).isFalse();
        assertThat(PICKED_UP.canMoveTo(DELIVERY, CANCELLED)).isFalse();
        assertThat(OUT_FOR_DELIVERY.canMoveTo(DELIVERY, CANCELLED)).isFalse();
        assertThatThrownBy(() -> PICKED_UP.check(DELIVERY, CREATED))
                .isInstanceOf(ShipmentStatus.IllegalTransition.class)
                .isInstanceOf(com.routemesh.shared.domain.DomainConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"DELIVERED", "RETURNED", "CANCELLED"})
    void terminalStatesHaveNoWayOut(ShipmentStatus s) {
        assertThat(s.terminal()).isTrue();
        for (var kind : ShipmentKind.values()) {
            for (var next : ShipmentStatus.values()) {
                assertThat(s.canMoveTo(kind, next)).as(kind + " " + s + "->" + next).isFalse();
            }
        }
    }

    @Test
    void everyNonTerminalStateCanReachATerminalOne() {
        for (var kind : ShipmentKind.values()) {
            for (var start : ShipmentStatus.values()) {
                if (start.terminal() || !reachable(kind, CREATED).contains(start)) {
                    continue;
                }
                assertThat(reachable(kind, start).stream().anyMatch(ShipmentStatus::terminal))
                        .as(kind + " " + start).isTrue();
            }
        }
    }

    @Test
    void awaitingVisitDependsOnKind() {
        assertThat(PICKED_UP.awaitingVisit(DELIVERY)).isTrue();
        assertThat(PICKED_UP.awaitingVisit(RETURN)).isFalse();
        assertThat(IN_TRANSIT.awaitingVisit(DELIVERY)).isTrue();
        assertThat(ASSIGNED.awaitingVisit(RETURN)).isTrue();
        assertThat(CREATED.awaitingVisit(DELIVERY)).isFalse();
        assertThat(DELIVERED.awaitingVisit(DELIVERY)).isFalse();
    }

    @Test
    void onlyUnroutedStatesArePlannable() {
        var plannable = EnumSet.noneOf(ShipmentStatus.class);
        for (var s : ShipmentStatus.values()) {
            if (s.plannable()) {
                plannable.add(s);
            }
        }
        assertThat(plannable).containsExactlyInAnyOrder(CREATED, FAILED_ATTEMPT);
    }

    @Test
    void otpIsBoundToTheShipment() {
        var otp = new Otp("a-test-key-that-is-long-enough");
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var code = Otp.newCode();
        assertThat(code).matches("\\d{6}");
        var hash = otp.hash(a, code);
        assertThat(hash).doesNotContain(code);
        assertThat(otp.matches(a, code, hash)).isTrue();
        assertThat(otp.matches(b, code, hash)).isFalse();
        assertThat(otp.matches(a, code.equals("000000") ? "000001" : "000000", hash)).isFalse();
        assertThat(otp.matches(a, "12345", hash)).isFalse();
        assertThat(otp.matches(a, code, null)).isFalse();
        assertThatThrownBy(() -> new Otp("short")).isInstanceOf(IllegalStateException.class);
    }

    private static EnumSet<ShipmentStatus> reachable(ShipmentKind kind, ShipmentStatus from) {
        var seen = EnumSet.of(from);
        var queue = new ArrayDeque<ShipmentStatus>();
        queue.add(from);
        while (!queue.isEmpty()) {
            var s = queue.poll();
            for (var n : ShipmentStatus.values()) {
                if (s.canMoveTo(kind, n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen;
    }
}
