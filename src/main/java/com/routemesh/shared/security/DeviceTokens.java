package com.routemesh.shared.security;

import java.util.Optional;

/** Port: resolves a presented device token (already hashed) to the device it was issued to. */
public interface DeviceTokens {

    Optional<Device> findActive(String tokenSha256Hex);
}
