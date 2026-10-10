package com.stockmarketanalyzer.backend.common.csv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/**
 * An uploaded CSV file, decoded and split into rows.
 *
 * <p>Everything that can go wrong with the file <em>as a file</em> is decided here and thrown as
 * {@link CsvFormatException} before a single row is looked at, so a mis-clicked spreadsheet is a
 * 400 with an explanation rather than a hundred and thirty-six unreadable rows.
 *
 * <p>The delimiter is sniffed from the header line rather than configured. Both dialects turn up
 * in practice -- an English spreadsheet writes commas and dots, a Hungarian one writes semicolons
 * and commas -- and asking the user which one their file is would be asking them something their
 * spreadsheet never told them either. The delimiter also settles the decimal separator, because
 * a file cannot use the comma for both.
 *
 * @param delimiter the field separator this file uses
 * @param decimalSeparator {@code ','} in a semicolon-delimited file, otherwise {@code '.'}
 * @param rows the data rows, header and blank lines excluded
 */
public record CsvFile(char delimiter, char decimalSeparator, List<CsvRow> rows) {

    /** A byte-order mark, which spreadsheets put in front of a UTF-8 file and nothing else wants. */
    private static final String BOM = "\uFEFF";

    /** Well beyond any hand-maintained sheet, and low enough that one upload cannot exhaust a heap. */
    private static final int MAX_ROWS = 10_000;

    /** How much of the file is scanned for the NUL bytes that give a binary file away. */
    private static final int BINARY_SNIFF_BYTES = 8192;

    /**
     * Reads an upload, or explains why it cannot be read.
     *
     * @param requiredColumns columns the header must carry, named as the sample writes them
     * @throws CsvFormatException when the upload is not a CSV file this importer can use
     */
    public static CsvFile read(MultipartFile file, List<String> requiredColumns) {
        if (file == null || file.isEmpty()) {
            throw new CsvFormatException("No file was uploaded, or the file is empty.");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new CsvFormatException(
                    "'%s' is not a CSV file. Save it as CSV and upload a file ending in .csv.".formatted(name));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new CsvFormatException("The upload could not be read: " + ex.getMessage());
        }
        rejectBinary(bytes);
        return parse(decode(bytes), requiredColumns);
    }

    /**
     * Splits already-decoded text. Package-private so the parsing rules can be exercised without
     * building a multipart request.
     */
    static CsvFile parse(String content, List<String> requiredColumns) {
        String text = content.startsWith(BOM) ? content.substring(1) : content;
        char delimiter = sniffDelimiter(text);
        char decimalSeparator = delimiter == ';' ? ',' : '.';

        List<List<String>> records = tokenise(text, delimiter);
        int headerIndex = 0;
        while (headerIndex < records.size() && isBlank(records.get(headerIndex))) {
            headerIndex++;
        }
        if (headerIndex == records.size()) {
            throw new CsvFormatException("The file has no header row. The first line must name the columns.");
        }

        List<String> header =
                records.get(headerIndex).stream().map(CsvFile::normaliseColumn).toList();
        List<String> missing = requiredColumns.stream()
                .filter(column -> !header.contains(normaliseColumn(column)))
                .toList();
        if (!missing.isEmpty()) {
            throw new CsvFormatException(
                    "The header is missing %s. Expected columns: %s. Download the sample file to see the format."
                            .formatted(String.join(", ", missing), String.join(", ", requiredColumns)));
        }

        List<CsvRow> rows = new ArrayList<>();
        for (int index = headerIndex + 1; index < records.size(); index++) {
            List<String> record = records.get(index);
            if (isBlank(record)) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new CsvFormatException(
                        "The file has more than %d data rows. Split it and upload it in parts.".formatted(MAX_ROWS));
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                values.put(header.get(column), column < record.size() ? record.get(column) : "");
            }
            rows.add(new CsvRow(index + 1, values, decimalSeparator));
        }
        if (rows.isEmpty()) {
            throw new CsvFormatException("The file has a header row but no data rows.");
        }
        return new CsvFile(delimiter, decimalSeparator, List.copyOf(rows));
    }

    /**
     * Column names are compared with spacing, underscores and case removed, so {@code sourceUnit},
     * {@code source_unit} and {@code Source Unit} are the same column. A header that survived a
     * round trip through a spreadsheet rarely comes back spelled the way it left.
     */
    static String normaliseColumn(String column) {
        return column.replace(BOM, "")
                .replaceAll("[\\s_-]", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * A spreadsheet or a PDF renamed to .csv is the likeliest mistake after a wrong delimiter, and
     * it would otherwise surface as an unhelpful "missing columns" complaint about binary noise.
     */
    private static void rejectBinary(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            throw new CsvFormatException(
                    "That looks like a spreadsheet or a zip archive, not a CSV file. "
                            + "Open it and save it as CSV first.");
        }
        if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            throw new CsvFormatException("That is a PDF, not a CSV file.");
        }
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == 0) {
                throw new CsvFormatException("That is a binary file, not a text CSV file.");
            }
        }
    }

    /**
     * UTF-8 first, and Latin-1 as the fallback because it decodes any byte sequence rather than
     * failing. Excel on a Hungarian Windows still writes CP1250, and every field these importers
     * read -- units, tickers, numbers, dates -- is ASCII in both, so the fallback only affects how
     * an accented character in an ignored column looks in a log line.
     */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** Whichever of {@code ;} and {@code ,} the header line uses more. A tie means comma. */
    private static char sniffDelimiter(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        return count(header, ';') > count(header, ',') ? ';' : ',';
    }

    private static int count(String text, char character) {
        int total = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == character) {
                total++;
            }
        }
        return total;
    }

    /**
     * RFC 4180 splitting: double quotes protect delimiters and line breaks, and a doubled quote
     * inside a quoted field is one literal quote.
     */
    private static List<List<String>> tokenise(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == delimiter) {
                record.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    /** A line of nothing but separators -- what a spreadsheet leaves behind under the last row. */
    private static boolean isBlank(List<String> record) {
        return record.stream().allMatch(String::isBlank);
    }
}
