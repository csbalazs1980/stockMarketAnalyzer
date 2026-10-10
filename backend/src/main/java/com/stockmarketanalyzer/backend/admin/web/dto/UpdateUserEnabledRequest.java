package com.stockmarketanalyzer.backend.admin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Switches an account on or off.
 *
 * <p>The alternative to deletion, and the reason deletion is not offered: wealth entries and
 * dividends are owned by the Keycloak subject, so removing the user would strand their rows with
 * no owner and no way back. Disabling revokes every sign-in and leaves the data intact.
 */
@Schema(description = "Whether the account may sign in")
public record UpdateUserEnabledRequest(boolean enabled) {}
