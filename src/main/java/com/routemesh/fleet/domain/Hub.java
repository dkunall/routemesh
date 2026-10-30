package com.routemesh.fleet.domain;

import com.routemesh.geo.domain.GeoPoint;

import java.util.UUID;

public record Hub(UUID id, String code, String name, GeoPoint location) {
}
