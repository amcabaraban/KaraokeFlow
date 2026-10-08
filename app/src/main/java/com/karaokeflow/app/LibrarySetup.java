package com.karaokeflow.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Batch SAF metadata queries; only explicitly granted folders are traversed. */
public final class LibrarySetup {
    public static final class Entry {
        public final Uri uri;
        public final String name, id;
        public final boolean directory;
        Entry(Uri uri, String name, String id, boolean directory) {
            this.uri = uri; this.name = name; this.id = id; this.directory = directory;
        }
    }
    public static List<Entry> children(Context context, Uri tree, String id) throws IOException {
        List<Entry> result = new ArrayList<>();
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = context.getContentResolver().query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot read folder.");
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                String child = cursor.getString(0), name = cursor.getString(1);
                if (child != null && name != null) result.add(new Entry(
                        DocumentsContract.buildDocumentUriUsingTree(tree, child), name, child,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
            }
        } catch (SecurityException | IllegalArgumentException error) {
            throw new IOException("Folder access was lost. Choose the folder again.", error);
        }
        result.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return result;
    }
    public static Uri subtree(Uri grant, Entry entry) {
        return entry.uri;
    }
    public static List<SongCatalog.Song> media(Context context, Uri tree, String extension, boolean refresh) throws IOException {
        File cache = new File(context.getCacheDir(), "media-" + extension + "-"
                + Integer.toHexString(tree.toString().hashCode()) + ".json");
        if (refresh) cache.delete();
        if (cache.isFile()) {
            try (BufferedReader input = new BufferedReader(new FileReader(cache))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = input.readLine()) != null) text.append(line);
                JSONObject saved = new JSONObject(text.toString());
                if (tree.toString().equals(saved.getString("tree"))) {
                    JSONArray rows = saved.getJSONArray("songs");
                    List<SongCatalog.Song> songs = new ArrayList<>();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        songs.add(new SongCatalog.Song(row.getInt("id"), row.getString("title"), "",
                                row.getString("uri")));
                    }
                    return songs;
                }
            } catch (Exception ignored) { }
        }
        List<SongCatalog.Song> songs = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(DocumentsContract.isDocumentUri(context, tree)
                ? DocumentsContract.getDocumentId(tree) : DocumentsContract.getTreeDocumentId(tree));
        Pattern numbering = Pattern.compile("^([0-9]{6})(?:[ _-]+(.*))?$");
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!visited.add(id)) continue;
            for (Entry entry : children(context, tree, id)) {
                if (entry.directory) { pending.add(entry.id); continue; }
                if (!entry.name.toLowerCase(Locale.ROOT).endsWith("." + extension)) continue;
                String stem = entry.name.substring(0, entry.name.lastIndexOf('.'));
                Matcher match = numbering.matcher(stem);
                if (!match.matches()) continue;
                int number = Integer.parseInt(match.group(1));
                if (!numbers.add(number)) throw new IOException("Duplicate media number " + match.group(1)
                        + ". Give every song and concert a unique six-digit number.");
                String title = match.group(2);
                if (title == null || title.isEmpty()) title = stem;
                songs.add(new SongCatalog.Song(number, title, "", entry.uri.toString()));
            }
        }
        songs.sort(Comparator.comparingInt(song -> song.id));
        try {
            JSONObject saved = new JSONObject();
            saved.put("tree", tree.toString());
            JSONArray rows = new JSONArray();
            for (SongCatalog.Song song : songs) {
                JSONObject row = new JSONObject();
                row.put("id", song.id); row.put("title", song.title); row.put("uri", song.file);
                rows.put(row);
            }
            saved.put("songs", rows);
            File temporary = File.createTempFile("media-", ".partial", context.getCacheDir());
            try {
                try (Writer output = new OutputStreamWriter(new FileOutputStream(temporary), java.nio.charset.StandardCharsets.UTF_8)) {
                    output.write(saved.toString());
                }
                java.nio.file.Files.move(temporary.toPath(), cache.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { temporary.delete(); }
        } catch (Exception ignored) { }
        return songs;
    }
}
