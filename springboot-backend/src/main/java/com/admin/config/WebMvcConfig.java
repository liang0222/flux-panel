package com.admin.config;

import com.admin.common.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
@EnableWebMvc
public class WebMvcConfig implements WebMvcConfigurer {

    @Bean
    public CorsFilter corsFilter(PanelOriginPolicy originPolicy) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(originPolicy.allowedOrigins());
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.addExposedHeader("Authorization");
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * JWT拦截器
     */
    @Bean
    public JwtInterceptor jwtInterceptor() {
        return new JwtInterceptor();
    }

    /**
     * 添加JWT拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加JWT拦截器，不拦截登录接口
        registry.addInterceptor(jwtInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/flow/**")
                .excludePathPatterns("/api/v1/open_api/**")
                .excludePathPatterns("/api/v1/config/get")
                .excludePathPatterns("/api/v1/user/login")
                .excludePathPatterns("/api/v1/captcha/**");
    }
}
