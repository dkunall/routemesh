package com.routemesh.shipment.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Six-digit delivery codes. Only HMAC(key, shipmentId:code) is stored, so the table alone does not reveal codes;
 * the shipment id in the MAC stops a hash from one shipment being replayed on another.
 */
public final class Otp {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public Otp(String key) {
        if (key == null || key.length() < 16) {
            throw new IllegalStateException("routemesh.security.otp-key must be at least 16 characters");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    public String hash(UUID shipment, String code) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((shipment + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(UUID shipment, String code, String storedHash) {
        if (code == null || storedHash == null || !code.matches("\\d{6}")) {
            return false;
        }
        return MessageDigest.isEqual(hash(shipment, code).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
