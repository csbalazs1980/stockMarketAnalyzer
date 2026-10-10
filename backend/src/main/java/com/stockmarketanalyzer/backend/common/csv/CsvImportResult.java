package com.stockmarketanalyzer.backend.common.csv;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an import did, in counts. {@code processedRows + failedRows + duplicateRows} always
 * equals {@code totalRows}, so the caller can render "132/136 rows processed" and still explain
 * where the other four went.
 *
 * <p>Failed and duplicate are deliberately separate: a row that could not be read is a mistake
 * to fix, whereas a row that was already stored is the expected outcome of re-uploading a file
 * and is not a problem at all.
 */
@Schema(name = "CsvImportResult", description = "Row counts for one CSV upload.")
public record CsvImportResult(
        @Schema(description = "Data rows in the file, blank lines and the header excluded.", example = "136")
                int totalRows,
        @Schema(description = "Rows that were stored.", example = "132") int processedRows,
        @Schema(description = "Rows that could not be read and were skipped.", example = "3") int failedRows,
        @Schema(description = "Rows that were already stored and were skipped.", example = "1") int duplicateRows) {}
