package com.routemesh.shared.security;

import com.routemesh.shared.error.ApiException;

/** A JWT caller. Passed explicitly into application services so authorisation is visible in the code. */
public record Caller(String subject, Role role) {

    public static Caller system(String job) {
        return new Caller("system:" + job, Role.DISPATCHER);
    }

    public boolean isDispatcher() {
        return role == Role.DISPATCHER;
    }

    public void requireDispatcher() {
        if (role != Role.DISPATCHER) {
            throw ApiException.forbidden("forbidden", "This needs the DISPATCHER role");
        }
    }

    /** Shippers see their own shipments only. Dispatchers (and jobs) see everything. */
    public boolean canSee(String ownerSubject) {
        return role == Role.DISPATCHER || subject.equals(ownerSubject);
    }
}
