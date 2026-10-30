package com.routemesh.shipment.adapter;

import com.routemesh.shipment.application.ShipmentService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class JdbcHubLookup implements ShipmentService.HubLookup {

    private final JdbcClient db;

    JdbcHubLookup(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean exists(UUID hubId) {
        return db.sql("select count(*) from hub where id = ?").param(hubId).query(Integer.class).single() > 0;
    }
}
