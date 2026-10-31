package com.routemesh.routing.application;

import com.routemesh.fleet.application.FleetStore;
import com.routemesh.fleet.domain.FleetVehicle;
import com.routemesh.fleet.domain.Hub;
import com.routemesh.geo.application.DriverIndex;
import com.routemesh.geo.domain.GeoPoint;
import com.routemesh.routing.domain.Job;
import com.routemesh.routing.domain.Plan;
import com.routemesh.routing.domain.Problem;
import com.routemesh.routing.domain.Solver;
import com.routemesh.routing.domain.TravelModel;
import com.routemesh.routing.domain.Vehicle;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.error.ApiException;
import com.routemesh.shared.security.Caller;
import com.routemesh.shipment.application.ShipmentLifecycle;
import com.routemesh.shipment.application.ShipmentStore;
import com.routemesh.shipment.domain.Shipment;
import com.routemesh.shipment.domain.ShipmentKind;
import com.routemesh.shipment.domain.ShipmentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.routemesh.shipment.domain.ShipmentStatus.*;

/**
 * Turns a hub's shipments for a day into vehicle routes, and fits late orders into existing routes. A PLANNED
 * route (vehicle still at the hub) can take deliveries and returns; a DISPATCHED one only returns, evaluated from
 * the driver's last known position and the current time.
 */
@Service
public class RoutingService {

    public record PlanResult(LocalDate serviceDate, List<RouteSummary> routes, List<UUID> unassigned, double totalKm,
                             long vehiclesUsed, double baselineKm, long baselineVehicles, long solveMillis,
                             boolean budgetExhausted) {
    }

    public record RouteSummary(UUID routeId, UUID vehicleId, int stops, double km) {
    }

    public record StopView(int seq, UUID shipmentId, String reference, ShipmentKind kind, ShipmentStatus status,
                           String address, String recipient, String phone, Instant windowStart, Instant windowEnd,
                           Instant plannedEta, Instant predictedEta, Instant arrivedAt) {
    }

    public record RouteView(UUID id, UUID hubId, LocalDate serviceDate, UUID vehicleId, UUID driverId,
                            RouteRow.Status status, double km, Instant plannedReturn, long version,
                            List<StopView> stops) {
    }

    public record DispatchResult(UUID routeId, double addedKm, int candidatesEvaluated, int nearbyDrivers,
                                 boolean onRoad, boolean windowsRelaxed, RouteView route) {
    }

    /** A feasible way to add one shipment to one route, computed without locks. */
    record Insertion(RouteRow route, List<UUID> sequence, List<Instant> etas, double km, Instant returnAt,
                     double addedKm, boolean relaxed) {
    }

    private static final Duration FRESH = Duration.ofMinutes(10);

    private final RouteStore routes;
    private final ShipmentStore shipments;
    private final ShipmentLifecycle lifecycle;
    private final FleetStore fleet;
    private final DriverIndex drivers;
    private final RouteMeshProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public RoutingService(RouteStore routes, ShipmentStore shipments, ShipmentLifecycle lifecycle, FleetStore fleet,
                          DriverIndex drivers, RouteMeshProperties props, TransactionTemplate tx, Clock clock,
                          MeterRegistry meters) {
        this.routes = routes;
        this.shipments = shipments;
        this.lifecycle = lifecycle;
        this.fleet = fleet;
        this.drivers = drivers;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public TravelModel travel() {
        return new TravelModel(props.planning().roadFactor(), props.planning().planningKmh());
    }

    // --- full plan ---------------------------------------------------------------------------------------------

    /**
     * Plans every unplanned shipment of the day onto vehicles that have no route yet. Existing routes are left as
     * they are (late orders go in with {@link #insert}). Planning is serialised per hub by a row lock.
     */
    @Transactional
    public PlanResult plan(Caller caller, UUID hubId, LocalDate date) {
        caller.requireDispatcher();
        var hub = fleet.hub(hubId).orElseThrow(() -> ApiException.notFound("hub"));
        routes.lockHub(hubId);
        var taken = routes.vehiclesWithRoute(hubId, date);
        var vehicles = fleet.activeVehicles(hubId).stream().filter(v -> !taken.contains(v.id())).toList();
        var ids = shipments.plannable(hubId, date).stream().map(Shipment::id).toList();
        var locked = shipments.lockAll(ids).stream().filter(s -> s.status().plannable()).toList();
        if (vehicles.isEmpty() || locked.isEmpty()) {
            return new PlanResult(date, List.of(), locked.stream().map(Shipment::id).toList(), 0, 0, 0, 0, 0, false);
        }
        var byId = locked.stream().collect(Collectors.toMap(s -> s.id().toString(), s -> s));
        var problem = new Problem(hub.location(), vehicles.stream().map(v -> atDepot(v)).toList(),
                locked.stream().map(s -> job(s, date, Double.NaN)).toList(), travel());
        long t0 = System.nanoTime();
        var plan = Solver.solve(problem, props.planning().budget());
        long solveMs = (System.nanoTime() - t0) / 1_000_000;
        var baseline = Solver.nearestNeighbour(problem);
        meters.timer("routemesh.plan.solve").record(Duration.ofMillis(solveMs));

        var now = clock.instant();
        var summaries = new ArrayList<RouteSummary>();
        for (var rp : plan.routes()) {
            if (rp.stops().isEmpty()) {
                continue;
            }
            var route = new RouteRow(UUID.randomUUID(), hubId, date, UUID.fromString(rp.vehicleId()), null,
                    RouteRow.Status.PLANNED, round(rp.km()), at(date, rp.returnAt()), 0);
            routes.insert(route, now);
            var seq = new ArrayList<UUID>();
            var etas = new ArrayList<Instant>();
            for (var stop : rp.stops()) {
                var s = byId.get(stop.jobId());
                seq.add(s.id());
                etas.add(at(date, stop.arrival()));
            }
            routes.replaceStops(route.id(), seq, etas);
            for (var id : seq) {
                lifecycle.move(byId.get(id.toString()).withRoute(route.id()), ASSIGNED, "planned", caller.subject());
            }
            summaries.add(new RouteSummary(route.id(), route.vehicleId(), seq.size(), route.km()));
        }
        return new PlanResult(date, summaries, plan.unassigned().stream().map(UUID::fromString).toList(),
                round(plan.totalKm()), plan.vehiclesUsed(), round(baseline.totalKm()), baseline.vehiclesUsed(), solveMs,
                plan.budgetExhausted());
    }

    // --- incremental insert ------------------------------------------------------------------------------------

    /** Adds one shipment to a named route. {@code expectedVersion} guards against a concurrent change (409). */
    @Transactional
    public RouteView insert(Caller caller, UUID routeId, UUID shipmentId, long expectedVersion) {
        caller.requireDispatcher();
        var route = routes.find(routeId).orElseThrow(() -> ApiException.notFound("route"));
        var s = shipments.lock(shipmentId).orElseThrow(() -> ApiException.notFound("shipment"));
        checkInsertable(route, s);
        var ins = evaluate(route, s).orElseThrow(() -> ApiException.unprocessable("no-feasible-insertion",
                "the shipment does not fit this route's windows, capacity or shift"));
        apply(ins, expectedVersion, s, caller.subject());
        return view(routeId);
    }

    /**
     * Picks the route where the shipment adds the fewest km. Candidates: the day's PLANNED routes, plus for a
     * return the DISPATCHED routes of drivers within {@code candidateRing} H3 rings of the pickup. If none fits,
     * the shipment opens a new route on an idle vehicle ({@code candidatesEvaluated} then counts the misses).
     */
    public DispatchResult autoDispatch(Caller caller, UUID shipmentId) {
        caller.requireDispatcher();
        for (int attempt = 0; ; attempt++) {
            var s = shipments.find(shipmentId).orElseThrow(() -> ApiException.notFound("shipment"));
            if (!s.status().plannable()) {
                throw ApiException.conflict("not-plannable", "shipment is " + s.status());
            }
            var candidates = new LinkedHashMap<UUID, RouteRow>();
            routes.forDate(s.hubId(), s.serviceDate(), RouteRow.Status.PLANNED).forEach(r -> candidates.put(r.id(), r));
            int nearby = 0;
            if (s.kind() == ShipmentKind.RETURN) {
                var near = drivers.near(s.drop(), props.planning().candidateRing(), clock.instant().minus(FRESH));
                nearby = near.size();
                for (var d : near) {
                    routes.activeForDriver(d.driverId())
                            .filter(r -> r.hubId().equals(s.hubId()) && r.serviceDate().equals(s.serviceDate()))
                            .ifPresent(r -> candidates.put(r.id(), r));
                }
            }
            var best = candidates.values().stream().map(r -> evaluate(r, s)).flatMap(Optional::stream)
                    .min(Comparator.comparingDouble(Insertion::addedKm));
            if (best.isEmpty()) {
                int evaluated = candidates.size();
                int nearbyDrivers = nearby;
                var opened = tx.execute(t -> openRoute(caller, shipmentId, evaluated, nearbyDrivers));
                return opened.orElseThrow(() -> new ApiException(422, "no-feasible-route",
                        "no route can take this shipment and no idle vehicle can serve it",
                        Map.of("candidatesEvaluated", evaluated, "nearbyDrivers", nearbyDrivers)));
            }
            var chosen = best.get();
            int evaluated = candidates.size();
            int nearbyDrivers = nearby;
            try {
                return tx.execute(t -> {
                    var locked = shipments.lock(shipmentId).orElseThrow();
                    var fresh = routes.find(chosen.route().id()).orElseThrow();
                    checkInsertable(fresh, locked);
                    var ins = evaluate(fresh, locked).orElseThrow(() -> new OptimisticLockingFailureException("moved"));
                    apply(ins, fresh.version(), locked, caller.subject());
                    return new DispatchResult(fresh.id(), round(ins.addedKm()), evaluated, nearbyDrivers,
                            fresh.status() == RouteRow.Status.DISPATCHED, ins.relaxed(), view(fresh.id()));
                });
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= 2) {
                    throw e;
                }
            }
        }
    }

    /**
     * Fallback when no existing route can take the shipment: a new PLANNED route on a vehicle of the hub that has
     * no route that day. Runs under the hub lock, like {@link #plan}, so two callers cannot take the same van.
     */
    private Optional<DispatchResult> openRoute(Caller caller, UUID shipmentId, int evaluated, int nearbyDrivers) {
        var s = shipments.lock(shipmentId).orElseThrow(() -> ApiException.notFound("shipment"));
        if (!s.status().plannable()) {
            throw ApiException.conflict("not-plannable", "shipment is " + s.status());
        }
        var hub = fleet.hub(s.hubId()).orElseThrow(() -> ApiException.notFound("hub"));
        routes.lockHub(s.hubId());
        var date = s.serviceDate();
        var taken = routes.vehiclesWithRoute(s.hubId(), date);
        var idle = fleet.activeVehicles(s.hubId()).stream().filter(v -> !taken.contains(v.id())).toList();
        if (idle.isEmpty()) {
            return Optional.empty();
        }
        var problem = new Problem(hub.location(), idle.stream().map(v -> atDepot(v)).toList(),
                List.of(job(s, date, Double.NaN)), travel());
        var rp = Solver.solve(problem, props.planning().budget()).routes().stream()
                .filter(r -> !r.stops().isEmpty()).findFirst();
        if (rp.isEmpty()) {
            return Optional.empty();
        }
        var plan = rp.get();
        var route = new RouteRow(UUID.randomUUID(), s.hubId(), date, UUID.fromString(plan.vehicleId()), null,
                RouteRow.Status.PLANNED, round(plan.km()), at(date, plan.returnAt()), 0);
        routes.insert(route, clock.instant());
        routes.replaceStops(route.id(), List.of(s.id()), List.of(at(date, plan.stops().get(0).arrival())));
        lifecycle.move(s.withRoute(route.id()), ASSIGNED, "auto-dispatch: new route", caller.subject());
        meters.counter("routemesh.route.opened").increment();
        return Optional.of(new DispatchResult(route.id(), round(plan.km()), evaluated, nearbyDrivers, false, false,
                view(route.id())));
    }

    private void checkInsertable(RouteRow route, Shipment s) {
        if (!s.status().plannable()) {
            throw ApiException.conflict("not-plannable", "shipment is " + s.status());
        }
        if (!s.hubId().equals(route.hubId()) || !s.serviceDate().equals(route.serviceDate())) {
            throw ApiException.unprocessable("wrong-route", "the route is for another hub or day");
        }
        if (route.status() == RouteRow.Status.COMPLETED) {
            throw ApiException.conflict("route-completed", "the route is completed");
        }
        if (route.status() == RouteRow.Status.DISPATCHED && s.kind() == ShipmentKind.DELIVERY) {
            throw ApiException.unprocessable("parcel-at-hub", "a dispatched vehicle cannot take a parcel still at the hub");
        }
    }

    private void apply(Insertion ins, long expectedVersion, Shipment s, String actor) {
        var r = ins.route();
        if (!routes.bump(r.id(), expectedVersion, round(ins.km()), ins.returnAt(), clock.instant())) {
            throw new OptimisticLockingFailureException("route " + r.id() + " changed");
        }
        routes.replaceStops(r.id(), ins.sequence(), ins.etas());
        lifecycle.move(s.withRoute(r.id()), ASSIGNED, "inserted", actor);
        meters.counter("routemesh.route.insert", "onRoad", String.valueOf(r.status() == RouteRow.Status.DISPATCHED))
                .increment();
    }

    /**
     * Cheapest feasible insertion of {@code extra} into the route's open stops. Closed stops keep their place at
     * the front; on a dispatched route a stop that is OUT_FOR_DELIVERY is pinned as the vehicle's start. If the
     * route is already running late, existing stops' windows are relaxed to the shift end so the new stop is still
     * judged on its own window (reported as {@code relaxed}).
     */
    Optional<Insertion> evaluate(RouteRow route, Shipment extra) {
        var hub = fleet.hub(route.hubId()).orElseThrow();
        var fv = fleet.vehicle(route.vehicleId()).orElseThrow();
        var date = route.serviceDate();
        var stops = routes.stops(route.id());
        var onRoute = shipments.onRoute(route.id()).stream().collect(Collectors.toMap(Shipment::id, s -> s));
        var closed = new ArrayList<RouteStore.Stop>();
        var open = new ArrayList<Shipment>();
        RouteStore.Stop pinned = null;
        for (var st : stops) {
            var s = onRoute.get(st.shipmentId());
            if (s == null || s.id().equals(extra.id())) {
                continue;
            }
            boolean dispatched = route.status() == RouteRow.Status.DISPATCHED;
            if (!open(s)) {
                closed.add(st);
            } else if (dispatched && s.status() == OUT_FOR_DELIVERY && pinned == null) {
                pinned = st;
            } else {
                open.add(s);
            }
        }
        Vehicle vehicle;
        if (route.status() == RouteRow.Status.PLANNED) {
            vehicle = atDepot(fv);
        } else {
            GeoPoint start = drivers.last(route.driverId()).map(p -> p.position()).orElse(hub.location());
            double now = minutes(date, clock.instant());
            if (pinned != null) {
                start = onRoute.get(pinned.shipmentId()).drop();
                now += props.planning().serviceMinutes();
            }
            int carried = onRoute.values().stream()
                    .filter(s -> s.kind() == ShipmentKind.RETURN && s.status() == PICKED_UP)
                    .mapToInt(Shipment::weightKg).sum();
            vehicle = new Vehicle(fv.id().toString(), fv.capacityKg(), start,
                    Math.max(minutes(fv.shiftStart()), now), minutes(fv.shiftEnd()), carried);
        }
        var current = open.stream().map(s -> s.id().toString()).toList();
        var plan = tryInsert(hub, vehicle, open, extra, date, current, Double.NaN);
        boolean relaxed = false;
        if (plan == null) {
            plan = tryInsert(hub, vehicle, open, extra, date, current, vehicle.shiftEnd());
            relaxed = true;
        }
        if (plan == null) {
            return Optional.empty();
        }
        var before = plan.before;
        var after = plan.after;
        if (after.unassigned().contains(extra.id().toString())) {
            return Optional.empty();
        }
        var seq = new ArrayList<UUID>();
        var etas = new ArrayList<Instant>();
        for (var st : closed) {
            seq.add(st.shipmentId());
            etas.add(st.plannedEta());
        }
        if (pinned != null) {
            seq.add(pinned.shipmentId());
            etas.add(pinned.plannedEta());
        }
        var rp = after.routes().getFirst();
        for (var stop : rp.stops()) {
            seq.add(UUID.fromString(stop.jobId()));
            etas.add(at(date, stop.arrival()));
        }
        double addedKm = after.totalKm() - before.totalKm();
        double km = route.status() == RouteRow.Status.PLANNED ? after.totalKm() : route.km() + addedKm;
        return Optional.of(new Insertion(route, seq, etas, km, at(date, rp.returnAt()), addedKm, relaxed));
    }

    private record Attempt(Plan before, Plan after) {
    }

    private Attempt tryInsert(Hub hub, Vehicle vehicle, List<Shipment> open, Shipment extra, LocalDate date,
                              List<String> current, double relaxTo) {
        var jobs = new ArrayList<Job>();
        open.forEach(s -> jobs.add(job(s, date, relaxTo)));
        jobs.add(job(extra, date, Double.NaN));
        var problem = new Problem(hub.location(), List.of(vehicle), jobs, travel());
        Plan before;
        try {
            before = Solver.evaluate(problem, Map.of(vehicle.id(), current));
        } catch (IllegalArgumentException e) {
            return null;
        }
        var after = Solver.insert(problem, Map.of(vehicle.id(), current), List.of(extra.id().toString()),
                props.planning().insertBudget());
        return new Attempt(before, after);
    }

    private static boolean open(Shipment s) {
        return s.status().awaitingVisit(s.kind());
    }

    // --- dispatch and reads ------------------------------------------------------------------------------------

    @Transactional
    public RouteView dispatch(Caller caller, UUID routeId, UUID driverId, long expectedVersion) {
        caller.requireDispatcher();
        var route = routes.find(routeId).orElseThrow(() -> ApiException.notFound("route"));
        var driver = fleet.driver(driverId).orElseThrow(() -> ApiException.notFound("driver"));
        if (!driver.hubId().equals(route.hubId())) {
            throw ApiException.unprocessable("wrong-hub", "driver belongs to another hub");
        }
        if (route.status() != RouteRow.Status.PLANNED) {
            throw ApiException.conflict("not-planned", "route is " + route.status());
        }
        if (routes.activeForDriver(driverId).isPresent()) {
            throw ApiException.conflict("driver-busy", "driver already has a route on the road");
        }
        if (!routes.dispatch(routeId, expectedVersion, driverId, clock.instant())) {
            throw new OptimisticLockingFailureException("route " + routeId + " changed");
        }
        return view(routeId);
    }

    public RouteView get(Caller caller, UUID routeId) {
        caller.requireDispatcher();
        return view(routeId);
    }

    public RouteView driverRoute(UUID driverId) {
        var route = routes.activeForDriver(driverId)
                .orElseThrow(() -> ApiException.notFound("active route"));
        return view(route.id());
    }

    RouteView view(UUID routeId) {
        var r = routes.find(routeId).orElseThrow(() -> ApiException.notFound("route"));
        var byId = new HashMap<UUID, Shipment>();
        shipments.onRoute(routeId).forEach(s -> byId.put(s.id(), s));
        var stops = new ArrayList<StopView>();
        for (var st : routes.stops(routeId)) {
            var s = byId.get(st.shipmentId());
            if (s == null) {
                continue;
            }
            stops.add(new StopView(st.seq(), s.id(), s.reference(), s.kind(), s.status(), s.address(),
                    firstName(s.recipientName()), Shipment.maskPhone(s.recipientPhone()), s.windowStart(),
                    s.windowEnd(), st.plannedEta(), st.predictedEta(), st.arrivedAt()));
        }
        return new RouteView(r.id(), r.hubId(), r.serviceDate(), r.vehicleId(), r.driverId(), r.status(), r.km(),
                r.plannedReturn(), r.version(), stops);
    }

    // --- time and model conversion -----------------------------------------------------------------------------

    private Vehicle atDepot(FleetVehicle v) {
        return Vehicle.atDepot(v.id().toString(), v.capacityKg(), minutes(v.shiftStart()), minutes(v.shiftEnd()));
    }

    /** @param relaxTo if not NaN, the window's end is pushed out to at least this minute */
    private Job job(Shipment s, LocalDate date, double relaxTo) {
        double earliest = minutes(date, s.windowStart());
        double latest = minutes(date, s.windowEnd());
        if (!Double.isNaN(relaxTo)) {
            latest = Math.max(latest, relaxTo);
        }
        var kind = s.kind() == ShipmentKind.RETURN ? Job.Kind.PICKUP : Job.Kind.DELIVERY;
        return new Job(s.id().toString(), kind, s.drop(), earliest, latest, props.planning().serviceMinutes(),
                s.weightKg());
    }

    private static double minutes(LocalTime t) {
        return t.toSecondOfDay() / 60.0;
    }

    private double minutes(LocalDate date, Instant i) {
        return Duration.between(date.atStartOfDay(props.planning().zone()).toInstant(), i).toSeconds() / 60.0;
    }

    private Instant at(LocalDate date, double minutes) {
        return date.atStartOfDay(props.planning().zone()).toInstant().plusSeconds(Math.round(minutes * 60));
    }

    private static double round(double km) {
        return Math.round(km * 100) / 100.0;
    }

    private static String firstName(String name) {
        int i = name.indexOf(' ');
        return i < 0 ? name : name.substring(0, i);
    }
}
