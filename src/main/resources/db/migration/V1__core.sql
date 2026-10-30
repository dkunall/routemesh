create extension if not exists postgis;

create table hub (
    id          uuid primary key,
    code        text not null unique,
    name        text not null,
    location    geography(Point, 4326) not null,
    created_at  timestamptz not null
);

create table vehicle (
    id           uuid primary key,
    hub_id       uuid not null references hub (id),
    code         text not null unique,
    capacity_kg  int not null check (capacity_kg > 0),
    shift_start  time not null,
    shift_end    time not null,
    active       boolean not null default true,
    check (shift_end > shift_start)
);

create table driver (
    id          uuid primary key,
    hub_id      uuid not null references hub (id),
    code        text not null unique,
    name        text not null,
    created_at  timestamptz not null
);

-- A driver's phone. Only the SHA-256 of its token is kept. applied_seq is the device's event cursor: events
-- from this device are applied strictly in device_seq order (see DeviceSequence).
create table device (
    id            uuid primary key,
    driver_id     uuid not null references driver (id),
    token_sha256  text not null unique,
    active        boolean not null default true,
    applied_seq   bigint not null default 0,
    created_at    timestamptz not null
);

create table route (
    id              uuid primary key,
    hub_id          uuid not null references hub (id),
    service_date    date not null,
    vehicle_id      uuid not null references vehicle (id),
    driver_id       uuid references driver (id),
    status          text not null check (status in ('PLANNED', 'DISPATCHED', 'COMPLETED')),
    km              double precision not null,
    planned_return  timestamptz,
    version         bigint not null default 0,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    unique (vehicle_id, service_date)
);
create unique index route_one_active_per_driver on route (driver_id) where status = 'DISPATCHED';

create table shipment (
    id               uuid primary key,
    hub_id           uuid not null references hub (id),
    shipper_subject  text not null,
    reference        text not null,
    kind             text not null check (kind in ('DELIVERY', 'RETURN')),
    drop_point       geography(Point, 4326) not null,
    address          text not null,
    recipient_name   text not null,
    recipient_email  text not null,
    recipient_phone  text not null,
    service_date     date not null,
    window_start     timestamptz not null,
    window_end       timestamptz not null,
    weight_kg        int not null check (weight_kg > 0),
    status           text not null,
    attempts         int not null default 0,
    route_id         uuid references route (id),
    tracking_token   text not null unique,
    otp_hash         text,
    otp_failures     int not null default 0,
    version          bigint not null default 0,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    unique (shipper_subject, reference),
    check (window_end > window_start)
);
create index shipment_plannable on shipment (hub_id, service_date) where status in ('CREATED', 'FAILED_ATTEMPT');
create index shipment_route on shipment (route_id);

-- Stop order of a route. planned_eta comes from the plan; predicted_eta is refreshed from live pings.
create table route_stop (
    route_id       uuid not null references route (id),
    seq            int not null,
    shipment_id    uuid not null unique references shipment (id),
    planned_eta    timestamptz not null,
    predicted_eta  timestamptz,
    arrived_at     timestamptz,
    primary key (route_id, seq) deferrable initially deferred
);

-- Stored (downsampled) track. Plain table with a BRIN index on time; retention job deletes old rows.
create table location_ping (
    driver_id  uuid not null,
    at         timestamptz not null,
    position   geography(Point, 4326) not null,
    primary key (driver_id, at)
);
create index location_ping_at on location_ping using brin (at);

-- Device events. UNIQUE (device_id, device_seq) makes an offline replay harmless.
create table tracking_event (
    id           bigserial primary key,
    device_id    uuid not null references device (id),
    device_seq   bigint not null check (device_seq > 0),
    shipment_id  uuid not null references shipment (id),
    status       text not null,
    reason       text,
    position     geography(Point, 4326),
    device_at    timestamptz,
    received_at  timestamptz not null,
    state        text not null check (state in ('PENDING', 'APPLIED', 'REJECTED')),
    outcome      text,
    applied_at   timestamptz,
    unique (device_id, device_seq)
);
create index tracking_event_pending on tracking_event (device_id, device_seq) where state = 'PENDING';

create table pod (
    shipment_id   uuid primary key references shipment (id),
    device_id     uuid not null references device (id),
    otp_verified  boolean not null,
    photo         bytea not null,
    photo_type    text not null,
    photo_sha256  text not null,
    position      geography(Point, 4326) not null,
    distance_m    double precision not null,
    delivered_at  timestamptz not null
);

create table geofence (
    id         uuid primary key,
    kind       text not null check (kind in ('HUB', 'DROP')),
    ref_id     uuid not null,
    center     geography(Point, 4326) not null,
    radius_m   double precision not null check (radius_m > 0),
    active     boolean not null default true,
    unique (kind, ref_id)
);
create index geofence_center on geofence using gist (center) where active;

create table geofence_presence (
    driver_id   uuid not null,
    fence_id    uuid not null references geofence (id),
    entered_at  timestamptz not null,
    primary key (driver_id, fence_id)
);

-- Learned effective speeds per H3 cell and local hour of day.
create table segment_speed (
    cell        bigint not null,
    hour        smallint not null check (hour between 0 and 23),
    kmh         double precision not null,
    samples     bigint not null,
    updated_at  timestamptz not null,
    primary key (cell, hour)
);

-- Read model behind the public tracking page; written only by TrackingProjector and the ETA refresher.
create table tracking_view (
    shipment_id     uuid primary key,
    tracking_token  text not null unique,
    reference       text not null,
    status          text not null,
    window_start    timestamptz not null,
    window_end      timestamptz not null,
    eta             timestamptz,
    attempts        int not null,
    driver_name     text,
    last_event_at   timestamptz not null,
    delivered_at    timestamptz,
    expires_at      timestamptz,
    updated_at      timestamptz not null
);

create table alert (
    id           bigserial primary key,
    kind         text not null,
    shipment_id  uuid,
    route_id     uuid,
    message      text not null,
    created_at   timestamptz not null,
    unique (kind, shipment_id)
);

create table outbox (
    id            bigserial primary key,
    event_id      uuid not null unique,
    topic         text not null,
    msg_key       text not null,
    type          text not null,
    payload       jsonb not null,
    created_at    timestamptz not null,
    published_at  timestamptz
);
create index outbox_unpublished on outbox (id) where published_at is null;

create table processed_event (
    consumer   text not null,
    event_id   uuid not null,
    at         timestamptz not null,
    primary key (consumer, event_id)
);

create table idempotency_record (
    scope            text not null,
    subject          text not null,
    key              text not null,
    request_hash     text not null,
    status           text not null check (status in ('IN_PROGRESS', 'DONE')),
    response_status  int,
    response_body    text,
    created_at       timestamptz not null,
    primary key (scope, subject, key)
);
