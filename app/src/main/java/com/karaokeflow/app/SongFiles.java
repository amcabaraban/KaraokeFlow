package com.karaokeflow.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Accesses only user-granted SAF documents. No proprietary content is bundled. */
public final class SongFiles {
    public static final long MAX_SF2_BYTES = 256L * 1024 * 1024;
    private final Context context;
    private final ContentResolver resolver;
    private String selectedTree;
    private Uri tree;
    private Node root;
    private final Map<String, List<Node>> directories = new HashMap<>();
    private Map<Integer, Match> index;

    public SongFiles(Context context) {
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
    }

    /** Resolves catalog paths first; only an exact six-digit basename is used as fallback. */
    public synchronized Uri findMidi(Uri treeUri, SongCatalog.Song song) throws IOException {
        checkCancelled();
        selectTree(treeUri);
        for (List<String> candidate : candidates(song.file)) {
            Node node = resolve(candidate);
            if (node != null && !node.directory && isMidi(node.name)) {
                String basename = node.name.substring(0, node.name.lastIndexOf('.'));
                if (basename.matches("[0-9]{6}") && Integer.parseInt(basename) != song.id) {
                    throw new IOException("Catalog path for song #" + song.songNumber()
                            + " points to a different permanent song number: " + node.name + ".");
                }
                return node.uri;
            }
        }
        if (index == null) buildIndex();
        Match match = index.get(song.id);
        if (match == null) {
            throw new IOException("MIDI for song #" + song.songNumber()
                    + " was not found in the selected folder. Check the folder and catalog path.");
        }
        if (match.duplicate != null) {
            throw new IOException("Song #" + song.songNumber() + " has multiple MIDI files: "
                    + match.path + " and " + match.duplicate
                    + ". Choose a narrower folder or use a unique catalog path.");
        }
        return match.uri;
    }

    public InputStream openMidi(Uri uri) throws IOException {
        if (uri == null) throw new IOException("No MIDI file was selected.");
        checkCancelled();
        try {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) throw new IOException("The MIDI file could not be opened.");
            return input;
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MIDI access was lost. Select the MIDI folder again.", e);
        }
    }

    /** Copies one selected SF2 to local cache, preserving any previous copy on failure. */
    public File cacheSoundFont(Uri uri) throws IOException {
        if (uri == null) throw new IOException("Choose an .sf2 SoundFont first.");
        checkCancelled();
        File directory = new File(context.getCacheDir(), "soundfonts");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("The SoundFont cache folder could not be created.");
        }
        File destination = new File(directory, uriKey(uri) + ".sf2");
        // Reuse a complete cached bank across Activity restarts. Refresh files explicitly
        // invalidates this path before the next copy.
        if (isValidCachedSoundFont(destination)) return destination;
        if (destination.exists() && !destination.delete()) {
            throw new IOException("The stale SoundFont cache could not be replaced.");
        }
        File temporary = File.createTempFile("soundfont-", ".partial", directory);
        boolean complete = false;
        try {
            InputStream opened;
            try {
                opened = resolver.openInputStream(uri);
            } catch (SecurityException | IllegalArgumentException e) {
                throw new IOException("SoundFont access was lost. Select the .sf2 again.", e);
            }
            if (opened == null) throw new IOException("The selected SoundFont could not be opened.");
            byte[] header = new byte[12];
            int headerCount = 0;
            long count = 0;
            try (InputStream input = opened; FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (read == 0) continue;
                    count += read;
                    if (count > MAX_SF2_BYTES) throw new IOException("SoundFonts must be 256 MiB or smaller for this beta.");
                    int copy = Math.min(read, header.length - headerCount);
                    if (copy > 0) {
                        System.arraycopy(buffer, 0, header, headerCount, copy);
                        headerCount += copy;
                        if (headerCount == header.length) validateHeader(header);
                    }
                    output.write(buffer, 0, read);
                }
                if (headerCount < header.length) throw new IOException("The selected file is too short to be an SF2 SoundFont.");
                long declared = unsignedInt(header, 4) + 8;
                if (count != declared) throw new IOException("The SoundFont is truncated or its RIFF size is invalid.");
                output.getFD().sync();
            }
            checkCancelled();
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Both files share one directory; Android's rename uses an atomic rename(2).
                if (!temporary.renameTo(destination)) throw new IOException("The SoundFont cache could not be saved.", e);
            }
            complete = true;
            return destination;
        } finally {
            if (!complete) temporary.delete();
        }
    }

    private static boolean isValidCachedSoundFont(File file) {
        if (file == null || !file.isFile() || file.length() < 12 || file.length() > MAX_SF2_BYTES) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] header = new byte[12];
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) return false;
                if (read == 0) continue;
                offset += read;
            }
            validateHeader(header);
            return file.length() == unsignedInt(header, 4) + 8;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void validateHeader(byte[] header) throws IOException {
        if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F'
                || header[8] != 's' || header[9] != 'f' || header[10] != 'b' || header[11] != 'k') {
            throw new IOException("Select a real .sf2 SoundFont (RIFF/sfbk), not an SF3 or another file type.");
        }
        long size = unsignedInt(header, 4) + 8;
        if (size < 12 || size > MAX_SF2_BYTES) throw new IOException("Invalid SF2 size; this beta supports up to 256 MiB.");
    }

    private static long unsignedInt(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }

    /** Drops provider directory metadata so the next lookup sees newly added files. */
    public synchronized void clearFolderCache() {
        selectedTree = null;
        tree = null;
        root = null;
        index = null;
        directories.clear();
    }

    /** Explicitly invalidate a selected provider SoundFont after it was replaced in place. */
    public void invalidateSoundFont(Uri uri) throws IOException {
        if (uri == null) return;
        File directory = new File(context.getCacheDir(), "soundfonts");
        File destination = new File(directory, uriKey(uri) + ".sf2");
        if (destination.exists() && !destination.delete()) {
            throw new IOException("The SoundFont cache could not be cleared.");
        }
    }

    private static String uriKey(Uri uri) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : hash) key.append(String.format(Locale.ROOT, "%02x", value & 255));
            return key.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SoundFont cache hashing is unavailable.", e);
        }
    }

    private void selectTree(Uri uri) throws IOException {
        if (uri == null) throw new IOException("Choose the extracted MIDI folder first.");
        if (uri.toString().equals(selectedTree)) return;
        // Never allow a lookup from a previous folder to survive a new selection.
        selectedTree = null;
        root = null;
        index = null;
        directories.clear();
        try {
            DocumentFile document = DocumentFile.fromTreeUri(context, uri);
            if (document == null || !document.isDirectory()) {
                throw new IOException("The selected MIDI folder is unavailable. Select it again.");
            }
            tree = uri;
            root = new Node(document.getUri(), DocumentsContract.getDocumentId(document.getUri()),
                    document.getName() == null ? "" : document.getName(), true);
            selectedTree = uri.toString();
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MIDI folder access was lost. Select the folder again.", e);
        }
    }

    private List<List<String>> candidates(String catalogPath) throws IOException {
        ArrayList<List<String>> result = new ArrayList<>();
        String path = catalogPath == null ? "" : catalogPath.trim().replace('\\', '/');
        if (path.isEmpty()) return result;
        if (path.indexOf('\0') >= 0) throw new IOException("The catalog MIDI path contains an invalid character.");
        boolean absolute = path.startsWith("/") || path.matches("^[A-Za-z]:/.*")
                || path.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
        ArrayList<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IOException("A catalog MIDI path escapes the selected folder.");
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        if (!absolute && !parts.isEmpty()) result.add(parts);
        // Windows extraction prefixes are discarded only at a known folder anchor.
        // Every resulting URI is still built from descendants of the granted tree.
        for (int i = 0; i < parts.size() - 1; i++) {
            if (parts.get(i).equalsIgnoreCase(root.name)
                    || parts.get(i).equalsIgnoreCase("SongHub_Extracted")) {
                result.add(new ArrayList<>(parts.subList(i + 1, parts.size())));
            }
        }
        return result;
    }

    private Node resolve(List<String> path) throws IOException {
        Node current = root;
        for (String name : path) {
            checkCancelled();
            if (!current.directory) return null;
            Node exact = null;
            Node insensitive = null;
            int exactCount = 0;
            int insensitiveCount = 0;
            for (Node child : children(current)) {
                if (child.name.equals(name)) { exact = child; exactCount++; }
                if (child.name.equalsIgnoreCase(name)) { insensitive = child; insensitiveCount++; }
            }
            if (exactCount > 1 || (exactCount == 0 && insensitiveCount > 1)) {
                throw new IOException("The catalog path is ambiguous at '" + name + "' in the MIDI folder.");
            }
            current = exact != null ? exact : insensitive;
            if (current == null) return null;
        }
        return current;
    }

    private List<Node> children(Node directory) throws IOException {
        List<Node> cached = directories.get(directory.id);
        if (cached != null) return cached;
        checkCancelled();
        ArrayList<Node> result = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.id);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE };
        try (Cursor cursor = resolver.query(childrenUri, columns, null, null, null)) {
            if (cursor == null) throw new IOException("The MIDI folder could not be read.");
            while (cursor.moveToNext()) {
                checkCancelled();
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (id == null || name == null) continue;
                result.add(new Node(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, name,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw new IOException("MIDI folder access was lost. Select the folder again.", e);
        }
        directories.put(directory.id, result);
        return result;
    }

    private void buildIndex() throws IOException {
        Map<Integer, Match> newIndex = new HashMap<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<Walk> pending = new ArrayDeque<>();
        pending.add(new Walk(root, ""));
        while (!pending.isEmpty()) {
            checkCancelled();
            Walk current = pending.removeFirst();
            if (!visited.add(current.node.id)) continue;
            for (Node child : children(current.node)) {
                checkCancelled();
                String relative = current.path + child.name;
                if (child.directory) {
                    pending.add(new Walk(child, relative + "/"));
                } else if (isMidi(child.name)) {
                    int dot = child.name.lastIndexOf('.');
                    String basename = child.name.substring(0, dot);
                    if (!basename.matches("[0-9]{6}")) continue;
                    int id = Integer.parseInt(basename);
                    Match prior = newIndex.get(id);
                    if (prior == null) newIndex.put(id, new Match(child.uri, relative));
                    else if (!prior.uri.equals(child.uri) && prior.duplicate == null) prior.duplicate = relative;
                }
            }
        }
        // An interrupted/failed traversal never leaves a partially valid index behind.
        index = newIndex;
    }

    private static boolean isMidi(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mid") || lower.endsWith(".midi") || lower.endsWith(".kar");
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("File loading cancelled.");
    }

    private static final class Node {
        final Uri uri;
        final String id;
        final String name;
        final boolean directory;
        Node(Uri uri, String id, String name, boolean directory) {
            this.uri = uri; this.id = id; this.name = name; this.directory = directory;
        }
    }

    private static final class Walk {
        final Node node;
        final String path;
        Walk(Node node, String path) { this.node = node; this.path = path; }
    }

    private static final class Match {
        final Uri uri;
        final String path;
        String duplicate;
        Match(Uri uri, String path) { this.uri = uri; this.path = path; }
    }
}
