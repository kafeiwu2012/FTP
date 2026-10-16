package com.lanftp;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

/** All access, including mutations, resolves inside the user-granted document tree. */
final class SafFileSystem implements FtpServer.Vfs {
  private final ContentResolver resolver;
  private final Uri tree;
  private final String rootId;
  private static final String[] COLUMNS = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED
  };

  SafFileSystem(ContentResolver r, Uri t) {
    resolver = r;
    tree = t;
    rootId = DocumentsContract.getTreeDocumentId(t);
  }

  private Uri doc(String id) {
    return DocumentsContract.buildDocumentUriUsingTree(tree, id);
  }

  private static FtpServer.Entry entry(Cursor c) {
    return new FtpServer.Entry(
        c.getString(1),
        DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)),
        c.isNull(3) ? 0 : c.getLong(3),
        c.isNull(4) ? 0 : c.getLong(4));
  }

  private String resolve(String path) throws IOException {
    if (!FtpServer.normalize("/", path).equals(path)) throw new IOException("Invalid path");
    String id = rootId;
    for (String part : path.substring(1).split("/")) {
      if (part.isEmpty()) continue;
      String found = null;
      try (Cursor c =
          resolver.query(
              DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
              COLUMNS,
              null,
              null,
              null)) {
        if (c == null) throw new IOException("Cannot read folder");
        while (c.moveToNext())
          if (part.equals(c.getString(1))) {
            found = c.getString(0);
            break;
          }
      } catch (RuntimeException e) {
        throw new IOException("Folder unavailable", e);
      }
      if (found == null) throw new FileNotFoundException(path);
      id = found;
    }
    return id;
  }

  private static String parent(String path) throws IOException {
    if (path.equals("/") || !FtpServer.normalize("/", path).equals(path))
      throw new IOException("Cannot modify root");
    int split = path.lastIndexOf('/');
    return split == 0 ? "/" : path.substring(0, split);
  }

  private static String name(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  public FtpServer.Entry stat(String path) throws IOException {
    try (Cursor c = resolver.query(doc(resolve(path)), COLUMNS, null, null, null)) {
      if (c == null || !c.moveToFirst()) throw new FileNotFoundException(path);
      return entry(c);
    } catch (RuntimeException e) {
      throw new IOException("File unavailable", e);
    }
  }

  public List<FtpServer.Entry> list(String path) throws IOException {
    List<FtpServer.Entry> result = new ArrayList<>();
    try (Cursor c =
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, resolve(path)),
            COLUMNS,
            null,
            null,
            null)) {
      if (c == null) throw new IOException("Cannot list folder");
      while (c.moveToNext()) result.add(entry(c));
    } catch (RuntimeException e) {
      throw new IOException("Folder unavailable", e);
    }
    result.sort(
        (a, b) ->
            a.directory == b.directory
                ? a.name.compareToIgnoreCase(b.name)
                : (a.directory ? -1 : 1));
    return result;
  }

  public InputStream open(String path) throws IOException {
    InputStream in = resolver.openInputStream(doc(resolve(path)));
    if (in == null) throw new FileNotFoundException(path);
    return in;
  }

  private synchronized Uri ensureFile(String path) throws IOException {
    String p = parent(path);
    try {
      if (stat(path).directory) throw new IOException("Not a file");
      return doc(resolve(path));
    } catch (FileNotFoundException e) {
      Uri u =
          DocumentsContract.createDocument(
              resolver, doc(resolve(p)), "application/octet-stream", name(path));
      if (u == null) throw new IOException("Create denied");
      return u;
    }
  }

  public OutputStream write(String path, boolean append) throws IOException {
    OutputStream out = resolver.openOutputStream(ensureFile(path), append ? "wa" : "wt");
    if (out == null) throw new IOException("Write denied");
    return out;
  }

  public void mkdir(String path) throws IOException {
    String p = parent(path);
    try {
      stat(path);
      throw new IOException("Already exists");
    } catch (FileNotFoundException expected) {
    }
    if (DocumentsContract.createDocument(
            resolver, doc(resolve(p)), DocumentsContract.Document.MIME_TYPE_DIR, name(path))
        == null) throw new IOException("Mkdir denied");
  }

  public void delete(String path, boolean directory) throws IOException {
    parent(path);
    if (stat(path).directory != directory) throw new IOException("Wrong object type");
    if (directory && !list(path).isEmpty()) throw new IOException("Directory not empty");
    if (!DocumentsContract.deleteDocument(resolver, doc(resolve(path))))
      throw new IOException("Delete denied");
  }

  public void rename(String source, String dest) throws IOException {
    if (!parent(source).equals(parent(dest)))
      throw new IOException("Cross-directory move not supported by this provider adapter");
    if (source.equals(dest)) return;
    try {
      stat(dest);
      throw new IOException("Destination exists");
    } catch (FileNotFoundException expected) {
    }
    if (DocumentsContract.renameDocument(resolver, doc(resolve(source)), name(dest)) == null)
      throw new IOException("Rename denied");
  }

  public void writeAt(String path, long offset, byte[] bytes) throws IOException {
    if (offset < 0) throw new IOException("Invalid offset");
    Uri u = ensureFile(path);
    ParcelFileDescriptor fd = resolver.openFileDescriptor(u, "rw");
    if (fd == null) throw new IOException("Open denied");
    try (FileOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        FileChannel channel = stream.getChannel()) {
      ByteBuffer b = ByteBuffer.wrap(bytes);
      while (b.hasRemaining()) {
        int n = channel.write(b, offset + b.position());
        if (n <= 0) throw new IOException("Write made no progress");
      }
    }
  }

  public boolean canCreate() throws IOException {
    try (Cursor c =
        resolver.query(
            doc(rootId),
            new String[] {DocumentsContract.Document.COLUMN_FLAGS},
            null,
            null,
            null)) {
      return c != null
          && c.moveToFirst()
          && (c.getInt(0) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
    }
  }

  public long[] space() throws IOException {
    if (!tree.getAuthority().equals("com.android.externalstorage.documents")
        || !rootId.startsWith("primary:")) throw new IOException("Provider capacity unavailable");
    android.os.StatFs stats =
        new android.os.StatFs(
            android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
    return new long[] {stats.getTotalBytes(), stats.getAvailableBytes()};
  }

  public void setModified(String path, long millis) throws IOException {
    parent(path);
    if (millis < 0) throw new IOException("Invalid timestamp");
    // Only the descriptor opened through the user-granted SAF tree is used.
    // Providers may reject metadata changes; return that failure to the client.
    try (ParcelFileDescriptor fd = resolver.openFileDescriptor(doc(resolve(path)), "rw")) {
      if (fd == null) throw new IOException("Open denied");
      java.nio.file.Files.setLastModifiedTime(
          java.nio.file.Paths.get("/proc/self/fd/" + fd.getFd()),
          java.nio.file.attribute.FileTime.fromMillis(millis));
    } catch (UnsupportedOperationException | SecurityException e) {
      throw new IOException("Provider timestamp change unsupported", e);
    }
  }

  public void resize(String path, long length) throws IOException {
    if (length < 0) throw new IOException("Invalid size");
    Uri u = ensureFile(path);
    ParcelFileDescriptor fd = resolver.openFileDescriptor(u, "rw");
    if (fd == null) throw new IOException("Open denied");
    try (FileOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        FileChannel channel = stream.getChannel()) {
      if (length <= channel.size()) channel.truncate(length);
      else {
        channel.position(length - 1);
        channel.write(ByteBuffer.wrap(new byte[1]));
      }
    }
  }
}
