package com.lanftp;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class LocalFileSystem implements FtpServer.Vfs {
  private final Path root;

  LocalFileSystem(Path root) throws IOException {
    this.root = root.toRealPath();
  }

  private Path resolve(String p) throws IOException {
    Path f = root.resolve(p.substring(1)).normalize().toRealPath();
    if (!f.startsWith(root)) throw new IOException("outside root");
    return f;
  }

  private Path destination(String p) throws IOException {
    if (!p.equals(FtpServer.normalize("/", p)) || p.equals("/"))
      throw new IOException("Invalid path");
    int split = p.lastIndexOf('/');
    Path parent = resolve(split == 0 ? "/" : p.substring(0, split));
    Path path = parent.resolve(p.substring(split + 1));
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      resolve(p);
    }
    return path;
  }

  private FtpServer.Entry entry(Path p) throws IOException {
    return new FtpServer.Entry(
        p.getFileName().toString(),
        Files.isDirectory(p),
        Files.isDirectory(p) ? 0 : Files.size(p),
        Files.getLastModifiedTime(p).toMillis());
  }

  public FtpServer.Entry stat(String p) throws IOException {
    try {
      return entry(resolve(p));
    } catch (NoSuchFileException e) {
      throw new FileNotFoundException(p);
    }
  }

  public List<FtpServer.Entry> list(String p) throws IOException {
    List<FtpServer.Entry> r = new ArrayList<>();
    try (DirectoryStream<Path> s = Files.newDirectoryStream(resolve(p))) {
      for (Path f : s) r.add(entry(f));
    }
    return r;
  }

  public InputStream open(String p) throws IOException {
    return Files.newInputStream(resolve(p));
  }

  public OutputStream write(String p, boolean append) throws IOException {
    return Files.newOutputStream(
        destination(p),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
  }

  public void mkdir(String p) throws IOException {
    Files.createDirectory(destination(p));
  }

  public void delete(String p, boolean dir) throws IOException {
    Path f = destination(p);
    if (Files.isDirectory(f) != dir) throw new IOException("Wrong type");
    Files.delete(f);
  }

  public void rename(String a, String b) throws IOException {
    Files.move(destination(a), destination(b));
  }

  public void resize(String p, long length) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(destination(p).toFile(), "rw")) {
      f.setLength(length);
    }
  }

  public void writeAt(String p, long offset, byte[] bytes) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(destination(p).toFile(), "rw")) {
      f.seek(offset);
      f.write(bytes);
    }
  }

  public void setModified(String p, long time) throws IOException {
    Files.setLastModifiedTime(resolve(p), java.nio.file.attribute.FileTime.fromMillis(time));
  }

  public long[] space() throws IOException {
    FileStore store = Files.getFileStore(root);
    return new long[] {store.getTotalSpace(), store.getUsableSpace()};
  }
}
