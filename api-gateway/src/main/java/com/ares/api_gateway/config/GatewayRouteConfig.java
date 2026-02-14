package com.ares.api_gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRouteConfig {
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {

        return builder.routes()

                // USER SERVICE ROUTE
                .route("user-service", r -> r
                        .path("/api/v1/users/**")
                        .uri("lb://USER-SERVICE")
                )

                .build();
    }
}
