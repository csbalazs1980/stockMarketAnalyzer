package com.stockmarketanalyzer.backend.admin;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client used to talk to Keycloak's Admin REST API.
 *
 * <p>Its own client rather than a share of the login proxy's, for the same reason that one is
 * not the rate feed's: the timeouts belong to the conversation, not to the process. A slow
 * answer while an administrator is looking at a user list is a different problem from a slow
 * answer while somebody is trying to sign in, and neither should be able to hold the other's
 * connections.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfiguration {

    @Bean("adminRestClient")
    public RestClient adminRestClient(AdminProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
