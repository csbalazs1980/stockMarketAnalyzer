package com.stockmarketanalyzer.backend.expense.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The unit half of a recurrence: an expense repeats every {@code interval} of these.
 *
 * <p>Four values and an interval cover everything a household bill does -- {@code 2 × WEEKLY} is
 * fortnightly, {@code 6 × MONTHLY} is twice a year -- which is the same shape iCalendar's
 * {@code RRULE} uses ({@code FREQ} plus {@code INTERVAL}). Presets like "quarterly" belong in the
 * form, not here: adding QUARTERLY as a fifth constant would make {@code 3 × MONTHLY} and
 * {@code 1 × QUARTERLY} two spellings of one thing, and then every sum has to know they are
 * equal.
 *
 * <p>Nothing here knows how long a period <em>is</em>. Normalising a weekly cost into a monthly
 * figure needs an average month, which is a presentation decision -- the frontend makes it, so
 * the stored row stays exactly what the user typed.
 */
@Schema(
        name = "RecurrencePeriod",
        description = "The period an expense repeats over. Combined with an interval: 2 + WEEKLY is fortnightly.")
public enum RecurrencePeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
