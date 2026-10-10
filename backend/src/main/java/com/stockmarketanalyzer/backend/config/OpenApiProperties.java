package com.stockmarketanalyzer.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code app.openapi.*} keys in application.yaml. Keeping the document
 * metadata in configuration means the published spec can differ per environment without
 * a code change.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.openapi")
public class OpenApiProperties {

    /** Title shown at the top of Swagger UI. */
    private String title = "Stock Market Analyzer API";

    /** Version of the published contract (not the application build number). */
    private String version = "v1";

    /** Short description rendered under the title. */
    private String description = "REST API for stock instruments and their daily OHLCV price history.";

    /** Contact name published in the spec. */
    private String contactName = "Stock Market Analyzer";

    /** Contact email published in the spec. May be left blank. */
    private String contactEmail = "";

    /** Licence name published in the spec. */
    private String licenseName = "Apache-2.0";

    /** Licence URL published in the spec. */
    private String licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0";
}
