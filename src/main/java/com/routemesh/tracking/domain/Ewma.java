package com.routemesh.tracking.domain;

/** Exponentially weighted moving average with a sample count, so thin data can be ignored. */
public record Ewma(double value, long samples) {

    public static final Ewma EMPTY = new Ewma(Double.NaN, 0);

    public Ewma add(double sample, double alpha) {
        if (samples == 0) {
            return new Ewma(sample, 1);
        }
        return new Ewma(alpha * sample + (1 - alpha) * value, samples + 1);
    }
}
