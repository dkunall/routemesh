package com.routemesh.fleet.domain;

import java.util.UUID;

public record Driver(UUID id, UUID hubId, String code, String name) {

    /** What a customer sees: the first name only. */
    public String publicName() {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }
}
