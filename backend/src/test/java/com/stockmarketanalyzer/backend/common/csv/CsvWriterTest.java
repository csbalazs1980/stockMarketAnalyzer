package com.stockmarketanalyzer.backend.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The writing rules, and -- more to the point -- that {@link CsvFile} reads back what
 * {@link CsvWriter} writes. An export is only worth having if the file it produces is one the
 * import accepts, and the ways that can quietly stop being true are all here: a number that comes
 * out of the database as {@code 352.400000} or turns into scientific notation, a name with a comma
 * or a quote in it, a blank date, and the byte-order mark the download puts in front for Excel.
 */
class CsvWriterTest {

    private static final List<String> RATE_COLUMNS = List.of("sourceUnit", "destinationUnit", "rate", "rateDate");

    @Test
    void writesTheHeaderEvenWhenThereIsNothingToExport() {
        assertThat(new CsvWriter(List.of("category")).toCsv()).isEqualTo("category\r\n");
    }

    @Test
    void writesNumbersTheWayTheReaderParsesThem() {
        // NUMERIC(19,6) hands back trailing zeros; stripping them must not reach for exponents.
        assertThat(CsvWriter.text(new BigDecimal("352.400000"))).isEqualTo("352.4");
        assertThat(CsvWriter.text(new BigDecimal("100.000000"))).isEqualTo("100");
        assertThat(CsvWriter.text(new BigDecimal("0.000001"))).isEqualTo("0.000001");
        // No grouping separator, which in this dialect would be read as a decimal point.
        assertThat(CsvWriter.text(new BigDecimal("1234567.5"))).isEqualTo("1234567.5");
    }

    @Test
    void writesDatesEnumsAndBlanks() {
        assertThat(CsvWriter.text(LocalDate.of(2026, 3, 1))).isEqualTo("2026-03-01");
        assertThat(CsvWriter.text(RecurrenceExample.MONTHLY)).isEqualTo("MONTHLY");
        assertThat(CsvWriter.text(null)).isEmpty();
    }

    @Test
    void quotesOnlyWhatWouldOtherwiseBeMisread() {
        assertThat(CsvWriter.escape("plain")).isEqualTo("plain");
        assertThat(CsvWriter.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.escape("two\nlines")).isEqualTo("\"two\nlines\"");
        // The reader trims fields, so spaces at either end only survive inside quotes.
        assertThat(CsvWriter.escape(" padded ")).isEqualTo("\" padded \"");
    }

    @Test
    void refusesARowThatDoesNotFitTheHeader() {
        assertThatThrownBy(() -> new CsvWriter(List.of("a", "b")).row("only-one"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 values")
                .hasMessageContaining("2 columns");
    }

    @Test
    void writesAFileTheImportReadsBack() {
        CsvWriter writer = new CsvWriter(RATE_COLUMNS);
        writer.row("USD", "HUF", new BigDecimal("352.400000"), LocalDate.of(2026, 9, 1));
        writer.row("EUR", "HUF", new BigDecimal("410"), LocalDate.of(2026, 9, 2));

        CsvFile csv = CsvFile.parse(writer.toCsv(), RATE_COLUMNS);

        assertThat(csv.delimiter()).isEqualTo(',');
        assertThat(csv.decimalSeparator()).isEqualTo('.');
        assertThat(csv.rows()).hasSize(2);
        assertThat(csv.rows().get(0).requireText("sourceUnit")).isEqualTo("USD");
        assertThat(csv.rows().get(0).requirePositive("rate")).isEqualByComparingTo("352.4");
        assertThat(csv.rows().get(0).dateOr("rateDate", null)).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void survivesTheAwkwardCellsAnExpenseExportProduces() {
        List<String> columns = List.of("name", "amount", "unit", "startDate", "categories");
        CsvWriter writer = new CsvWriter(columns);
        writer.row("Netflix, family", new BigDecimal("4490.00"), "HUF", null, "Entertainment|Subscription");
        writer.row("Car \"full\" insurance", new BigDecimal("120000"), "HUF", LocalDate.of(2025, 3, 15), "");

        CsvFile csv = CsvFile.parse(writer.toCsv(), columns);

        assertThat(csv.rows()).hasSize(2);
        assertThat(csv.rows().get(0).requireText("name")).isEqualTo("Netflix, family");
        assertThat(csv.rows().get(0).requireText("categories")).isEqualTo("Entertainment|Subscription");
        assertThat(csv.rows().get(0).dateOr("startDate", null)).isNull();
        assertThat(csv.rows().get(1).requireText("name")).isEqualTo("Car \"full\" insurance");
        assertThat(csv.rows().get(1).text("categories")).isNull();
    }

    @Test
    void keepsALineBreakInsideOneCellAsOneRow() {
        CsvWriter writer = new CsvWriter(List.of("category"));
        writer.row("Line\r\nbreak");

        assertThat(CsvFile.parse(writer.toCsv(), List.of("category")).rows()).hasSize(1);
    }

    @Test
    void readsBackAFileCarryingTheByteOrderMarkTheDownloadAdds() {
        CsvWriter writer = new CsvWriter(RATE_COLUMNS);
        writer.row("USD", "HUF", new BigDecimal("352.4"), LocalDate.of(2026, 9, 1));

        assertThat(CsvFile.parse("\uFEFF" + writer.toCsv(), RATE_COLUMNS).rows()).hasSize(1);
    }

    /** Stands in for a domain enum, so this test does not drag a feature package in with it. */
    private enum RecurrenceExample {
        MONTHLY
    }
}
