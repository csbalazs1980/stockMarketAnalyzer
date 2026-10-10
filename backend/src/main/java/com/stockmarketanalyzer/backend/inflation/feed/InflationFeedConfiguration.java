package com.stockmarketanalyzer.backend.inflation.feed;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The inflation feed's own HTTP client, built from plain Spring Framework types for the same reason
 * as {@code RateFeedConfiguration}: Boot 4's per-feature auto-configuration modules make an injected
 * {@code RestClient.Builder} something that may or may not exist. Qualified, so it and the rate
 * feed's client cannot be confused -- they have different base URLs and timeouts.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InflationFeedProperties.class)
public class InflationFeedConfiguration {

    @Bean("inflationFeedRestClient")
    public RestClient inflationFeedRestClient(InflationFeedProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
