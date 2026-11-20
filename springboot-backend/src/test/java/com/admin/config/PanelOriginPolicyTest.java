package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PanelOriginPolicyTest {
    @Test
    void normalizesConfiguredOriginsAndRetainsAndroidOrigin() {
        PanelOriginPolicy policy = new PanelOriginPolicy(" HTTPS://Panel.Example:443/ , http://localhost:3000, null ");
        assertEquals(List.of("https://appassets.androidplatform.net", "https://panel.example",
                "http://localhost:3000", "null"), policy.allowedOrigins());
        assertThrows(IllegalArgumentException.class, () -> new PanelOriginPolicy("*"));
        assertThrows(IllegalArgumentException.class, () -> new PanelOriginPolicy("https://panel.example/path"));
    }

    @Test
    void allowsSameOriginWithPortAndNonBrowserClients() throws Exception {
        CorsFilter filter = new WebMvcConfig().corsFilter(new PanelOriginPolicy(""));
        for (String origin : new String[]{null, "http://panel.example:6366"}) {
            MockHttpServletRequest request = request("POST", origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertSame(request, chain.getRequest());
            assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Test
    void allowsAuthorizationHeaderForConfiguredMobileOrigins() throws Exception {
        CorsFilter filter = new WebMvcConfig().corsFilter(new PanelOriginPolicy("null"));
        for (String origin : List.of("https://appassets.androidplatform.net", "null")) {
            MockHttpServletRequest request = request("OPTIONS", origin);
            request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
            request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(200, response.getStatus());
            assertEquals(origin, response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            assertEquals("Authorization, Content-Type", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
            assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }

    @Test
    void defaultDoesNotGrantOpaqueOrigin() throws Exception {
        CorsFilter filter = new WebMvcConfig().corsFilter(new PanelOriginPolicy(""));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "null"), response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private static MockHttpServletRequest request(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/user/login");
        request.setServerName("panel.example");
        request.setServerPort(6366);
        if (origin != null) {
            request.addHeader(HttpHeaders.ORIGIN, origin);
        }
        return request;
    }
}
