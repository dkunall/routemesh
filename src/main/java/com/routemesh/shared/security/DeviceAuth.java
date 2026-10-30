package com.routemesh.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routemesh.shared.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Driver endpoints take {@code X-Device-Token}. Only the SHA-256 of a token is stored, so a database dump does
 * not hand out working device credentials. Revoking a device stops its token at the next request.
 */
public class DeviceAuth extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Token";

    private final DeviceTokens tokens;
    private final ObjectMapper json;

    public DeviceAuth(DeviceTokens tokens, ObjectMapper json) {
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = req.getHeader(HEADER);
        var device = token == null || token.isBlank() || token.length() > 200
                ? java.util.Optional.<Device>empty() : tokens.findActive(sha256(token));
        if (device.isEmpty()) {
            ProblemWriter.write(res, json, HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "A valid " + HEADER + " header is required");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new DeviceAuthentication(device.get()));
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class DeviceAuthentication extends AbstractAuthenticationToken {

        private final Device device;

        DeviceAuthentication(Device device) {
            super(AuthorityUtils.createAuthorityList("ROLE_DEVICE"));
            this.device = device;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Device getPrincipal() {
            return device;
        }
    }
}
