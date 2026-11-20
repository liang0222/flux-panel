package com.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared browser-origin allowlist for HTTP and WebSocket. Same-origin requests use Spring's checks. */
@Component
public class PanelOriginPolicy {
    private final List<String> allowedOrigins;

    public PanelOriginPolicy(@Value("${PANEL_ALLOWED_ORIGINS:}") String configuredOrigins) {
        Set<String> origins = new LinkedHashSet<>(List.of("https://appassets.androidplatform.net"));
        for (String entry : configuredOrigins.split(",")) {
            if (!entry.isBlank()) {
                origins.add(normalizeOrigin(entry.strip()));
            }
        }
        allowedOrigins = List.copyOf(origins);
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    private static String normalizeOrigin(String value) {
        // WKWebView file URLs can use opaque origins. A deployment must opt in explicitly.
        if ("null".equals(value)) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getPort() != 0 && uri.getPort() <= 65535) {
                int port = uri.getPort();
                boolean defaultPort = ("http".equals(scheme) && port == 80)
                        || ("https".equals(scheme) && port == 443);
                return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                        + (port == -1 || defaultPort ? "" : ":" + port);
            }
        } catch (IllegalArgumentException ignored) {
            // Use the same configuration error without echoing potentially sensitive input.
        }
        throw new IllegalArgumentException("PANEL_ALLOWED_ORIGINS requires comma-separated exact HTTP(S) origins");
    }
}
