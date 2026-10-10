package com.stockmarketanalyzer.backend.common.csv;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Hands a CSV string to the browser as a file to save, so the eight endpoints that do this do not
 * each spell out the same content type and Content-Disposition header.
 *
 * <p>The two methods differ in one byte. An <em>export</em> is opened in a spreadsheet far more
 * often than a sample is, and Excel still assumes the system code page for a file with no
 * byte-order mark -- on a Hungarian Windows that turns an expense called "Ebéd" into "EbÃ©d". The
 * BOM costs nothing on the way back in, because {@link CsvFile} strips it. A <em>sample</em> is
 * pure ASCII and is as likely to be opened in a text editor, some of which show the mark, so it is
 * sent exactly as it is written in the source.
 */
public final class CsvDownload {

    /** What a spreadsheet needs in front of a UTF-8 file to recognise it as one. */
    private static final String BYTE_ORDER_MARK = "\uFEFF";

    private CsvDownload() {}

    /** A fixed example file: the header row and a row or two showing what the columns look like. */
    public static ResponseEntity<byte[]> sample(String fileName, String csv) {
        return attachment(fileName, csv);
    }

    /** The caller's own rows, written for a spreadsheet to open. */
    public static ResponseEntity<byte[]> export(String fileName, String csv) {
        return attachment(fileName, BYTE_ORDER_MARK + csv);
    }

    private static ResponseEntity<byte[]> attachment(String fileName, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
