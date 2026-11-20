package com.admin.config;

import com.admin.common.utils.WebSocketServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketInterceptor webSocketInterceptor;
    private final PanelOriginPolicy originPolicy;

    public WebSocketConfig(WebSocketInterceptor webSocketInterceptor, PanelOriginPolicy originPolicy) {
        this.webSocketInterceptor = webSocketInterceptor;
        this.originPolicy = originPolicy;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry webSocketHandlerRegistry) {
        webSocketHandlerRegistry
                .addHandler(myHandler(), "/system-info")
                .setAllowedOrigins(originPolicy.allowedOrigins().toArray(String[]::new))
                .addInterceptors(webSocketInterceptor);
    }


    @Bean
    public WebSocketHandler myHandler() {
        return new WebSocketServer();
    }

}
