package com.stockmarketanalyzer.backend.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parsing rules, exercised without a web request. This is where the awkward cases live --
 * the two spreadsheet dialects, the headers that came back from Excel spelled differently, the
 * numbers that mean different things depending on who exported them -- and getting any of them
 * wrong is silent: the import reports a happy row count and stores the wrong figure.
 */
class CsvFileTest {

    private static final List<String> COLUMNS = List.of("sourceUnit", "destinationUnit", "rate", "rateDate");

    @Test
    void readsACommaDelimitedFileWithDotDecimals() {
        CsvFile csv = CsvFile.parse(
                """
                sourceUnit,destinationUnit,rate,rateDate
                USD,HUF,352.4,2026-09-01
                """,
                COLUMNS);

        assertThat(csv.delimiter()).isEqualTo(',');
        assertThat(csv.decimalSeparator()).isEqualTo('.');
        assertThat(csv.rows()).hasSize(1);

        CsvRow row = csv.rows().get(0);
        assertThat(row.requireText("sourceUnit")).isEqualTo("USD");
        assertThat(row.requirePositive("rate")).isEqualByComparingTo("352.4");
        assertThat(row.dateOr("rateDate", LocalDate.now())).isEqualTo(LocalDate.of(2026, 9, 1));
        // The header is line 1, so the first data row is line 2.
        assertThat(row.lineNumber()).isEqualTo(2);
    }

    @Test
    void readsASemicolonDelimitedFileWithCommaDecimals() {
        CsvFile csv = CsvFile.parse(
                """
                sourceUnit;destinationUnit;rate;rateDate
                USD;HUF;352,4;2026.09.01.
                EUR;HUF;1 234,56;2026. 9. 1.
                """,
                COLUMNS);

        assertThat(csv.delimiter()).isEqualTo(';');
        assertThat(csv.rows().get(0).requirePositive("rate")).isEqualByComparingTo("352.4");
        // Grouped with a space, dated the way a Hungarian locale writes it -- both survive.
        assertThat(csv.rows().get(1).requirePositive("rate")).isEqualByComparingTo("1234.56");
        assertThat(csv.rows().get(1).dateOr("rateDate", LocalDate.now())).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void readsADotDecimalEvenInASemicolonFile() {
        CsvFile csv = CsvFile.parse(
                """
                sourceUnit;destinationUnit;rate;rateDate
                USD;HUF;1234.56;2026-09-01
                GBP;HUF;1.234;2026-09-01
                """,
                COLUMNS);

        // The dot cannot be thousands grouping here: ".56" is not a three-digit group. Reading
        // it as one would store 123456 -- a hundred-fold error nobody would notice.
        assertThat(csv.rows().get(0).requirePositive("rate")).isEqualByComparingTo("1234.56");
        // Three digits after it, though, is exactly what grouping looks like, so it is grouping.
        assertThat(csv.rows().get(1).requirePositive("rate")).isEqualByComparingTo("1234");
    }

    @Test
    void matchesHeadersWhateverTheyCameBackFromASpreadsheetAs() {
        // A leading byte-order mark is what a spreadsheet puts in front of a UTF-8 file; left
        // in place it would make the first column name unmatchable.
        CsvFile csv = CsvFile.parse(
                "\uFEFF"
                        + """
                        Source Unit,destination_unit,RATE,Rate Date
                        USD,HUF,352.4,2026-09-01
                        """,
                COLUMNS);

        assertThat(csv.rows().get(0).requireText("destinationUnit")).isEqualTo("HUF");
        assertThat(csv.rows().get(0).requirePositive("rate")).isEqualByComparingTo("352.4");
    }

    @Test
    void honoursQuotingAndIgnoresBlankLines() {
        CsvFile csv = CsvFile.parse(
                """
                symbol,note,amount

                AAPL,"a note, with a comma",42.75

                MSFT,"two
                lines",5
                """,
                List.of("symbol", "amount"));

        assertThat(csv.rows()).hasSize(2);
        assertThat(csv.rows().get(0).requireText("note")).isEqualTo("a note, with a comma");
        assertThat(csv.rows().get(1).requireText("note")).isEqualTo("two\nlines");
        assertThat(csv.rows().get(1).requirePositive("amount")).isEqualByComparingTo("5");
    }

    @Test
    void treatsAMissingColumnAsSomethingWrongWithTheWholeFile() {
        assertThatThrownBy(() -> CsvFile.parse(
                        """
                        sourceUnit,destinationUnit,rate
                        USD,HUF,352.4
                        """,
                        COLUMNS))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("rateDate");
    }

    @Test
    void refusesAFileWithNoDataRows() {
        assertThatThrownBy(() -> CsvFile.parse("sourceUnit,destinationUnit,rate,rateDate\n", COLUMNS))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("no data rows");
    }

    @Test
    void reportsBadCellsPerRowRatherThanFailingTheFile() {
        CsvFile csv = CsvFile.parse(
                """
                sourceUnit,destinationUnit,rate,rateDate
                USD,HUF,not-a-number,2026-09-01
                USD,HUF,-1,2026-09-01
                USD,HUF,352.4,05/09/2026
                USD,HUF,352.4,
                """,
                COLUMNS);

        assertThatThrownBy(() -> csv.rows().get(0).requirePositive("rate"))
                .isInstanceOf(CsvRowException.class)
                .hasMessageContaining("line 2");
        assertThatThrownBy(() -> csv.rows().get(1).requirePositive("rate"))
                .isInstanceOf(CsvRowException.class)
                .hasMessageContaining("greater than zero");
        // Day-first is refused rather than guessed at: it is two different days.
        assertThatThrownBy(() -> csv.rows().get(2).dateOr("rateDate", LocalDate.now()))
                .isInstanceOf(CsvRowException.class);
        // A blank optional date is not an error -- it means "today", as the API does.
        LocalDate fallback = LocalDate.of(2030, 1, 1);
        assertThat(csv.rows().get(3).dateOr("rateDate", fallback)).isEqualTo(fallback);
    }
}
