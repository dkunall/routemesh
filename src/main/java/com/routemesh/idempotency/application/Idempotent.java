package com.routemesh.idempotency.application;

/** The result of an idempotent call, and whether it was replayed from an earlier identical request. */
public record Idempotent<T>(T value, boolean replayed) {
}
