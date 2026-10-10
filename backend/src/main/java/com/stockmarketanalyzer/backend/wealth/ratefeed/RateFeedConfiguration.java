package com.stockmarketanalyzer.backend.wealth.ratefeed;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the one HTTP client this application makes outbound calls with.
 *
 * <p>It is assembled from plain Spring Framework types rather than from Boot's auto-configured
 * {@code RestClient.Builder}, for the same reason Liquibase needed its own module: Boot 4 split
 * the old monolithic autoconfigure jar into per-feature modules, and depending on a builder that
 * may or may not have been contributed is exactly the failure mode that cost a day when
 * {@code spring-boot-liquibase} was missing. {@link RestClient#builder()} needs nothing but
 * spring-web, which the web starter guarantees.
 *
 * <p>Both timeouts are set explicitly. The default is no read timeout at all, so a feed that
 * accepts the connection and then says nothing would hang the request thread until the browser
 * gave up -- with no error anybody could act on.
 *
 * <p>The bean is qualified rather than left as the only {@code RestClient}, so a second outbound
 * integration later cannot silently inherit this one's timeouts and base URL.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateFeedProperties.class)
public class RateFeedConfiguration {

    @Bean("rateFeedRestClient")
    public RestClient rateFeedRestClient(RateFeedProperties properties) {
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
