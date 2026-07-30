package com.example.demo.global.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    private static final int MAX_POOL_SIZE = 50;

    @Bean
    @Qualifier("kakaoRestTemplate")
    public RestTemplate kakaoRestTemplate() {
        return buildRestTemplate(Duration.ofSeconds(5), Duration.ofMillis(100));
    }

    @Bean
    @Qualifier("vworldRestTemplate")
    public RestTemplate vworldRestTemplate() {
        return buildRestTemplate(Duration.ofSeconds(5), Duration.ofMillis(400));
    }

    private RestTemplate buildRestTemplate(Duration connectTimeout, Duration readTimeout) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(MAX_POOL_SIZE);
        connectionManager.setDefaultMaxPerRoute(MAX_POOL_SIZE);
        connectionManager.setDefaultConnectionConfig(
                ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout))
                        .build());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                .setResponseTimeout(Timeout.of(readTimeout))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplate(factory);
    }
}