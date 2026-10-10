package com.stockmarketanalyzer.backend.wealth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@Schema(
        name = "WealthEntryCloneRequest",
        description =
                "Copies an entry and everything under it into a fresh snapshot. The shape of the "
                        + "subtree and the row names are carried over unchanged; the date applies to every "
                        + "copied row, and quantities and units are edited through the overrides.")
public record WealthEntryCloneRequest(
        @Schema(
                        description = "Snapshot date for every copied row (ISO-8601). Defaults to today.",
                        example = "2026-09-30")
                LocalDate entryDate,
        @Schema(
                        description =
                                "Per-row edits, keyed by the id of the source row. Every id must belong to the "
                                        + "subtree being cloned; rows left out are copied as they stand.")
                @Valid
                List<WealthEntryCloneOverride> overrides) {}
