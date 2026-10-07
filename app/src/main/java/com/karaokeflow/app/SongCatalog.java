package com.karaokeflow.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PushbackReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** User supplied catalog data only; song numbers are never regenerated from row order. */
public final class SongCatalog {
    private SongCatalog() {}

    public static final class Song {
        public final int id;
        public final String title;
        public final String artist;
        public final String file;
        private final String number;

        public Song(int id, String title, String artist, String file) {
            if (id < 0 || id > 999999) {
                throw new IllegalArgumentException("Song number must be between 000000 and 999999.");
            }
            this.id = id;
            this.number = String.format(Locale.ROOT, "%06d", id);
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.file = file == null ? "" : file;
        }

        public String songNumber() {
            return number;
        }
    }

    /**
     * Parse a complete UTF-8 CSV before the caller replaces its current catalog.
     * The caller owns and closes the stream. Supports the original 0/2/3/5 layout
     * and explicitly named columns, including reordered headers.
     */
    public static List<Song> parse(InputStream input) throws IOException {
        if (input == null) throw new IOException("The catalog could not be opened.");
        CsvReader csv = new CsvReader(input);
        ArrayList<Song> songs = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Columns columns = null;
        Record record;
        while ((record = csv.next()) != null) {
            checkCancelled();
            if (record.values.size() == 1 && record.values.get(0).trim().isEmpty()) continue;
            if (columns == null) {
                Columns header = Columns.fromHeader(record);
                if (header != null) {
                    columns = header;
                    continue;
                }
                columns = Columns.legacy();
            }
            if (record.values.size() <= columns.requiredIndex()) {
                throw error(record.line, "Expected at least " + (columns.requiredIndex() + 1)
                        + " columns, found " + record.values.size() + ".");
            }
            String number = record.values.get(columns.id).trim();
            if (!number.matches("[0-9]{1,6}")) {
                throw error(record.line, "Invalid permanent song number '" + abbreviate(number)
                        + "'; use up to six digits.");
            }
            int id = Integer.parseInt(number);
            if (!ids.add(id)) {
                throw error(record.line, "Duplicate permanent song number "
                        + String.format(Locale.ROOT, "%06d", id) + ".");
            }
            songs.add(new Song(id, columns.value(record, columns.title),
                    columns.value(record, columns.artist), columns.value(record, columns.file)));
        }
        if (songs.isEmpty()) throw new IOException("The catalog contains no song records.");
        return songs;
    }

    private static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }

    private static IOException error(long line, String reason) {
        return new IOException("Catalog line " + line + ": " + reason);
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Catalog import cancelled.");
    }

    private static final class Columns {
        int id = -1;
        int title = -1;
        int artist = -1;
        int file = -1;

        static Columns legacy() {
            Columns result = new Columns();
            result.id = 0;
            result.title = 2;
            result.artist = 3;
            result.file = 5;
            return result;
        }

        static Columns fromHeader(Record record) throws IOException {
            // A numeric-looking first value is data, even if its number is invalid.
            // Do not silently discard an out-of-range or malformed first record.
            String first = record.values.get(0).trim();
            if (first.matches("^[0-9].*") || first.matches("^[+-][0-9].*")) return null;
            Columns result = new Columns();
            for (int i = 0; i < record.values.size(); i++) {
                String key = record.values.get(i).trim().toLowerCase(Locale.ROOT)
                        .replace("_", "").replace("-", "").replace(" ", "");
                switch (key) {
                    case "id": case "songid": case "songno": case "songnumber":
                    case "permanentid": case "permanentsongid": case "midiindex":
                    case "extractedid": case "extractedsequence": case "sequence":
                    case "sequencenumber": case "seq": case "index":
                        if (result.id >= 0) throw error(record.line, "Multiple song-number columns in the header.");
                        result.id = i;
                        break;
                    case "title": case "songtitle":
                        if (result.title >= 0) throw error(record.line, "Multiple title columns in the header.");
                        result.title = i;
                        break;
                    case "artist": case "songartist": case "performer": case "singer":
                        if (result.artist >= 0) throw error(record.line, "Multiple artist columns in the header.");
                        result.artist = i;
                        break;
                    // Exact aliases only. original_path/audio_path/etc. are not MIDI locations.
                    case "file": case "filename": case "path": case "filepath":
                    case "midifile": case "midifilename": case "midipath":
                    case "relativepath": case "midirelativepath":
                        if (result.file >= 0) throw error(record.line, "Multiple MIDI-file columns in the header.");
                        result.file = i;
                        break;
                    default:
                        break;
                }
            }
            if (result.id >= 0) {
                if (result.title < 0) throw error(record.line, "A named catalog header needs a title column.");
                return result;
            }
            if (result.title >= 0 || result.artist >= 0 || result.file >= 0) {
                // The established probe has six positional columns; retain that layout
                // for legacy headers whose first-column name is not a known alias.
                if (record.values.size() >= 6) return legacy();
                throw error(record.line, "The catalog header needs a song-number column.");
            }
            // The initial probe's column names were not a stable schema. Its first
            // nonnumeric six-column record may therefore be an unfamiliar header.
            return record.values.size() >= 6 && !first.isEmpty() ? legacy() : null;
        }

        int requiredIndex() {
            return Math.max(Math.max(id, title), Math.max(artist, file));
        }

        String value(Record row, int column) {
            return column < 0 ? "" : row.values.get(column);
        }
    }

    private static final class Record {
        final List<String> values;
        final long line;

        Record(List<String> values, long line) {
            this.values = values;
            this.line = line;
        }
    }

    /** Small RFC-style CSV reader that also accepts LF/CR line endings. */
    private static final class CsvReader {
        private static final int MAX_FIELD_CHARS = 1024 * 1024;
        private static final int MAX_COLUMNS = 4096;
        final PushbackReader reader;
        long line = 1;
        long characters;
        boolean first = true;

        CsvReader(InputStream input) {
            reader = new PushbackReader(new BufferedReader(new InputStreamReader(input,
                    StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT))), 1);
        }

        int read() throws IOException {
            if ((characters++ & 4095) == 0) checkCancelled();
            int value = reader.read();
            if (first) {
                first = false;
                if (value == '\ufeff') value = reader.read();
            }
            if (value == '\r') {
                int next = reader.read();
                if (next != '\n' && next != -1) reader.unread(next);
                value = '\n';
            }
            if (value == '\n') line++;
            return value;
        }

        Record next() throws IOException {
            long start = line;
            ArrayList<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean closedQuote = false;
            boolean seen = false;
            while (true) {
                int value = read();
                if (value == -1) {
                    if (quoted) throw error(start, "Unterminated quoted field.");
                    if (!seen && fields.isEmpty() && field.length() == 0) return null;
                    fields.add(field.toString());
                    return new Record(fields, start);
                }
                seen = true;
                char ch = (char) value;
                if (quoted) {
                    if (ch == '"') {
                        quoted = false;
                        closedQuote = true;
                    } else {
                        field.append(ch);
                    }
                } else if (closedQuote) {
                    if (ch == '"') {
                        field.append('"');
                        quoted = true;
                        closedQuote = false;
                    } else if (ch == ',' || ch == '\n') {
                        fields.add(field.toString());
                        if (ch == '\n') return new Record(fields, start);
                        field.setLength(0);
                        closedQuote = false;
                    } else if (ch != ' ' && ch != '\t') {
                        throw error(line, "Unexpected character after a closing quote.");
                    }
                } else if (ch == ',' || ch == '\n') {
                    fields.add(field.toString());
                    if (ch == '\n') return new Record(fields, start);
                    field.setLength(0);
                } else if (ch == '"') {
                    if (field.length() != 0) throw error(line, "Quote inside an unquoted field.");
                    quoted = true;
                } else {
                    field.append(ch);
                }
                if (field.length() > MAX_FIELD_CHARS) throw error(start, "A field exceeds 1 MiB of text.");
                if (fields.size() >= MAX_COLUMNS) throw error(start, "Too many columns.");
            }
        }
    }
}
