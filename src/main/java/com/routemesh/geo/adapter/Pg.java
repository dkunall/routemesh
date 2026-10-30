package com.routemesh.geo.adapter;

import com.routemesh.geo.domain.GeoPoint;

import java.sql.ResultSet;
import java.sql.SQLException;

/** SQL fragments for PostGIS geography points. Parameters go in as (lng, lat), the PostGIS order. */
public final class Pg {

    /** Placeholder for a point; bind {@code lng} then {@code lat}. */
    public static final String POINT = "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography";

    private Pg() {
    }

    /** Select list fragment: {@code ST_Y(col::geometry) as <alias>_lat, ST_X(col::geometry) as <alias>_lng}. */
    public static String cols(String column, String alias) {
        return "ST_Y(" + column + "::geometry) as " + alias + "_lat, ST_X(" + column + "::geometry) as " + alias + "_lng";
    }

    public static GeoPoint read(ResultSet rs, String alias) throws SQLException {
        double lat = rs.getDouble(alias + "_lat");
        if (rs.wasNull()) {
            return null;
        }
        return new GeoPoint(lat, rs.getDouble(alias + "_lng"));
    }
}
