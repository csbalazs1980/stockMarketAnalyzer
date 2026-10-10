package com.stockmarketanalyzer.backend.auth;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client used to talk to Keycloak's token endpoint.
 *
 * <p>Built by hand from {@link RestClient#builder()} for the same reason as the rate feed's
 * client: Spring Boot 4 split its autoconfigure jar into per-feature modules, and an
 * auto-configured {@code RestClient.Builder} is one more thing that can quietly not be
 * contributed. Both timeouts are explicit -- an identity provider that accepts the connection
 * and then says nothing must not hold a login request open for ever.
 *
 * <p>Named rather than primary so the rate feed's client and this one cannot be confused for
 * one another by type.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    @Bean("authRestClient")
    public RestClient authRestClient(AuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
