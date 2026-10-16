package com.lanftp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Streaming, same-origin browser access to the same granted tree and immutable FTP/SMB policy. */
public final class WebServer implements Closeable {
  private final FtpServer.Vfs fs;
  private final AccessPolicy policy;
  private final String user, password;
  private final ServerSocket listener;
  private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
  private final ExecutorService workers =
      new ThreadPoolExecutor(0, 12, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
  private final AtomicLong requests = new AtomicLong();
  private volatile boolean running = true;
  private volatile String lastResult = "尚无浏览器请求";

  public WebServer(FtpServer.Vfs fs, String user, String password, int port, AccessPolicy policy)
      throws IOException {
    this.fs = fs;
    this.user = user;
    this.password = password;
    this.policy = policy;
    listener = new ServerSocket();
    listener.setReuseAddress(true);
    listener.bind(new InetSocketAddress(port));
  }

  public int port() {
    return listener.getLocalPort();
  }

  public String diagnostics() {
    return "网页端口=" + port() + " 请求=" + requests.get() + " · " + lastResult;
  }

  public void start() {
    new Thread(
            () -> {
              while (running)
                try {
                  Socket s = listener.accept();
                  s.setSoTimeout(120000);
                  clients.add(s);
                  try {
                    workers.execute(() -> serve(s));
                  } catch (RejectedExecutionException e) {
                    clients.remove(s);
                    s.close();
                  }
                } catch (IOException e) {
                  if (running) close();
                }
            },
            "web-accept")
        .start();
  }

  public void close() {
    running = false;
    try {
      listener.close();
    } catch (IOException ignored) {
    }
    for (Socket s : clients)
      try {
        s.close();
      } catch (IOException ignored) {
      }
    workers.shutdownNow();
  }

  private static final class HttpError extends IOException {
    final int status;

    HttpError(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private static String line(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int previous = -1;
    while (out.size() <= max) {
      int b = in.read();
      if (b < 0) throw new EOFException();
      if (previous == '\r' && b == '\n') {
        byte[] data = out.toByteArray();
        return new String(data, 0, data.length - 1, StandardCharsets.ISO_8859_1);
      }
      if (b == '\n' || (previous == '\r' && b != '\n') || (b < 32 && b != '\r' && b != '\t'))
        throw new HttpError(400, "请求格式无效");
      out.write(b);
      previous = b;
    }
    throw new HttpError(431, "请求头过长");
  }

  private static final class Body extends InputStream {
    final InputStream in;
    final boolean chunked;
    long remaining;
    boolean finished;

    Body(InputStream in, long size, boolean chunked) {
      this.in = in;
      remaining = size;
      this.chunked = chunked;
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) return 0;
      if (finished) return -1;
      if (remaining == 0) {
        if (!chunked) {
          finished = true;
          return -1;
        }
        String size = line(in, 128);
        int semi = size.indexOf(';');
        if (semi >= 0) size = size.substring(0, semi);
        if (!size.matches("[0-9a-fA-F]{1,12}")) throw new HttpError(400, "数据分块无效");
        remaining = Long.parseLong(size, 16);
        if (remaining == 0) {
          int count = 0;
          String trailer;
          while (!(trailer = line(in, 8192)).isEmpty()) {
            count += trailer.length();
            if (count > 16384) throw new HttpError(400, "尾部过长");
          }
          finished = true;
          return -1;
        }
      }
      int n = in.read(b, off, (int) Math.min(len, remaining));
      if (n < 0) throw new EOFException("Incomplete upload");
      remaining -= n;
      if (chunked && remaining == 0 && !line(in, 2).isEmpty()) throw new HttpError(400, "数据分块无效");
      return n;
    }
  }

  private void serve(Socket socket) {
    boolean sent = false;
    try (Socket s = socket;
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
      try {
        String[] request = line(in, 8192).split(" ", -1);
        if (request.length != 3
            || !request[2].equals("HTTP/1.1")
            || !request[1].startsWith("/")
            || request[1].startsWith("//")) throw new HttpError(400, "请求格式无效");
        String method = request[0];
        URI uri;
        try {
          uri = URI.create(request[1]);
        } catch (IllegalArgumentException e) {
          throw new HttpError(400, "地址无效");
        }
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        for (String h; !(h = line(in, 8192)).isEmpty(); ) {
          total += h.length();
          if (total > 32768) throw new HttpError(431, "请求头过长");
          int colon = h.indexOf(':');
          if (colon < 1) throw new HttpError(400, "请求头无效");
          String key = h.substring(0, colon).toLowerCase(Locale.ROOT),
              value = h.substring(colon + 1).trim();
          if (!key.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || headers.put(key, value) != null)
            throw new HttpError(400, "重复或无效请求头");
        }
        String host = headers.get("host");
        if (host == null
            || !host.matches(
                "(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?|localhost(?::[0-9]{1,5})?"))
          throw new HttpError(400, "Host 无效");
        requests.incrementAndGet();
        String route = uri.getPath();
        boolean guest = authenticate(headers.get("authorization"));
        if (route.equals("/account") && guest) throw new HttpError(401, "请使用手机界面中的账号密码登录");
        if (route.equals("/account") && method.equals("GET")) {
          head(out, 302, "text/plain", 0, "Location: /\r\n");
          sent = true;
          out.flush();
          return;
        }
        boolean mutation = method.equals("PUT") || method.equals("POST") || method.equals("DELETE");
        if (mutation) {
          if (!policy.canWrite(guest)) throw new HttpError(403, "当前身份为只读，请登录账号或在手机开启对应写入权限");
          String origin = headers.get("origin");
          if (!"1".equals(headers.get("x-lanftp-request"))
              || (origin != null && !origin.equalsIgnoreCase("http://" + host))
              || "cross-site".equals(headers.get("sec-fetch-site")))
            throw new HttpError(403, "不允许跨站修改请求");
        }
        if (method.equals("GET") && route.equals("/")) {
          byte[] page = WebUi.PAGE.getBytes(StandardCharsets.UTF_8);
          head(out, 200, "text/html; charset=utf-8", page.length, "");
          sent = true;
          out.write(page);
        } else if (method.equals("GET") && route.equals("/api/list")) {
          String path = path(uri);
          if (!fs.stat(path).directory) throw new HttpError(400, "请选择目录");
          StringBuilder json =
              new StringBuilder("{\"path\":")
                  .append(quote(path))
                  .append(",\"guest\":")
                  .append(guest)
                  .append(",\"writable\":")
                  .append(policy.canWrite(guest))
                  .append(",\"entries\":[");
          boolean first = true;
          for (FtpServer.Entry e : fs.list(path)) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"name\":")
                .append(quote(e.name))
                .append(",\"directory\":")
                .append(e.directory)
                .append(",\"size\":")
                .append(e.size)
                .append(",\"modified\":")
                .append(e.modified)
                .append('}');
          }
          json.append("]}");
          byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
          head(out, 200, "application/json; charset=utf-8", data.length, "");
          sent = true;
          out.write(data);
        } else if ((method.equals("GET") || method.equals("HEAD")) && route.equals("/api/file")) {
          String path = path(uri);
          FtpServer.Entry e = fs.stat(path);
          if (e.directory) throw new HttpError(400, "请选择文件");
          long start = 0, end = e.size - 1;
          int status = 200;
          String extra =
              "Accept-Ranges: bytes\r\n"
                  + "Content-Disposition: attachment; filename=\"download\"; filename*=UTF-8''"
                  + encode(e.name)
                  + "\r\n";
          String range = headers.get("range");
          if (range != null) {
            if (!range.matches("bytes=[0-9]*-[0-9]*") || e.size == 0)
              throw new HttpError(416, "下载范围无效");
            try {
              String[] parts = range.substring(6).split("-", -1);
              if (parts[0].isEmpty()) {
                long tail = Long.parseLong(parts[1]);
                if (tail <= 0) throw new NumberFormatException();
                start = Math.max(0, e.size - tail);
              } else {
                start = Long.parseLong(parts[0]);
                if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
              }
              if (start < 0 || start >= e.size || end < start) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
              throw new HttpError(416, "下载范围无效");
            }
            status = 206;
            extra += "Content-Range: bytes " + start + "-" + end + "/" + e.size + "\r\n";
          }
          try (InputStream file = fs.open(path)) {
            long skip = start;
            while (skip > 0) {
              long n = file.skip(skip);
              if (n == 0) {
                if (file.read() < 0) throw new EOFException();
                n = 1;
              }
              skip -= n;
            }
            long size = e.size == 0 ? 0 : end - start + 1;
            head(out, status, "application/octet-stream", size, extra);
            sent = true;
            if (!method.equals("HEAD")) copy(file, out, size);
          }
        } else if (method.equals("PUT") && route.equals("/api/file")) {
          String path = path(uri);
          protectRoot(path);
          boolean exists = false;
          try {
            FtpServer.Entry e = fs.stat(path);
            exists = true;
            if (e.directory) throw new HttpError(409, "同名目录已存在");
          } catch (FileNotFoundException ignored) {
          }
          if (exists && !"1".equals(query(uri).get("overwrite")))
            throw new HttpError(409, "文件已存在，请确认覆盖");
          String transfer = headers.get("transfer-encoding"),
              length = headers.get("content-length");
          if (transfer != null && (!transfer.equalsIgnoreCase("chunked") || length != null))
            throw new HttpError(400, "传输长度无效");
          if (length == null && transfer == null) throw new HttpError(411, "需要数据长度");
          long size = 0;
          if (length != null)
            try {
              if (!length.matches("[0-9]{1,15}")) throw new NumberFormatException();
              size = Long.parseLong(length);
            } catch (NumberFormatException e) {
              throw new HttpError(400, "传输长度无效");
            }
          String expect = headers.get("expect");
          if (expect != null) {
            if (!expect.equalsIgnoreCase("100-continue")) throw new HttpError(417, "不支持的传输请求");
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
          }
          // Serialize browser mutations so a concurrent upload cannot race an overwrite
          // confirmation.
          synchronized (fs) {
            if (!exists)
              try {
                fs.stat(path);
                throw new HttpError(409, "文件已存在");
              } catch (FileNotFoundException ignored) {
              }
            try (OutputStream file = fs.write(path, false)) {
              copy(new Body(in, size, transfer != null), file, Long.MAX_VALUE);
            }
          }
          jsonOk(out);
          sent = true;
        } else if (method.equals("POST") && route.equals("/api/mkdir")) {
          String path = path(uri);
          protectRoot(path);
          fs.mkdir(path);
          jsonOk(out);
          sent = true;
        } else if (method.equals("DELETE") && route.equals("/api/file")) {
          String path = path(uri);
          protectRoot(path);
          FtpServer.Entry e = fs.stat(path);
          if (e.directory && !fs.list(path).isEmpty()) throw new HttpError(409, "仅允许删除空目录");
          fs.delete(path, e.directory);
          jsonOk(out);
          sent = true;
        } else throw new HttpError(404, "地址不存在");
        lastResult = method + " 成功";
        out.flush();
      } catch (Exception e) {
        int status =
            e instanceof HttpError
                ? ((HttpError) e).status
                : e instanceof FileNotFoundException
                    ? 404
                    : e instanceof SecurityException ? 403 : 500;
        lastResult = "HTTP " + status + " · " + e.getClass().getSimpleName();
        if (!sent) {
          String message =
              e instanceof HttpError
                  ? e.getMessage()
                  : status == 404
                      ? "文件不存在"
                      : status == 403 ? "没有访问权限" : "文件操作失败，请检查手机目录授权、可用空间和文档提供器";
          byte[] data = ("{\"error\":" + quote(message) + "}").getBytes(StandardCharsets.UTF_8);
          head(
              out,
              status,
              "application/json; charset=utf-8",
              data.length,
              status == 401
                  ? "WWW-Authenticate: Basic realm=\"LAN Files\", charset=\"UTF-8\"\r\n"
                  : "");
          out.write(data);
          out.flush();
        }
      }
    } catch (IOException ignored) {
    } finally {
      clients.remove(socket);
    }
  }

  private boolean authenticate(String authorization) throws IOException {
    if (authorization == null) {
      if (policy.anonymous) return true;
      throw new HttpError(401, "请输入手机界面中的账号和密码");
    }
    if (!authorization.regionMatches(true, 0, "Basic ", 0, 6)) throw new HttpError(401, "身份验证失败");
    String supplied;
    try {
      supplied =
          new String(
              Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new HttpError(401, "身份验证失败");
    }
    if (!MessageDigest.isEqual(
        supplied.getBytes(StandardCharsets.UTF_8),
        (user + ":" + password).getBytes(StandardCharsets.UTF_8)))
      throw new HttpError(401, "账号或密码错误");
    return false;
  }

  private static void protectRoot(String path) throws IOException {
    if (path.equals("/")) throw new HttpError(403, "不能修改共享根目录");
  }

  private static Map<String, String> query(URI uri) throws IOException {
    Map<String, String> result = new HashMap<>();
    String raw = uri.getRawQuery();
    if (raw == null) return result;
    try {
      for (String pair : raw.split("&")) {
        String[] parts = pair.split("=", 2);
        String key = URLDecoder.decode(parts[0], "UTF-8");
        if (result.put(key, URLDecoder.decode(parts.length == 2 ? parts[1] : "", "UTF-8")) != null)
          throw new HttpError(400, "重复参数");
      }
    } catch (IllegalArgumentException e) {
      throw new HttpError(400, "参数编码无效");
    }
    return result;
  }

  private static String path(URI uri) throws IOException {
    try {
      return FtpServer.normalize("/", query(uri).getOrDefault("path", "/"));
    } catch (IOException e) {
      throw new HttpError(400, "文件路径无效");
    }
  }

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  static String quote(String value) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') b.append('\\').append(c);
      else if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      else b.append(c);
    }
    return b.append('"').toString();
  }

  private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
    byte[] buffer = new byte[65536];
    long total = 0;
    while (total < limit) {
      int n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - total));
      if (n < 0) {
        if (limit != Long.MAX_VALUE) throw new EOFException();
        break;
      }
      out.write(buffer, 0, n);
      total += n;
    }
  }

  private static void jsonOk(OutputStream out) throws IOException {
    byte[] data = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    head(out, 200, "application/json; charset=utf-8", data.length, "");
    out.write(data);
  }

  private static void head(OutputStream out, int status, String type, long size, String extra)
      throws IOException {
    String reason =
        status == 200
            ? "OK"
            : status == 206
                ? "Partial Content"
                : status == 302 ? "Found" : status == 401 ? "Unauthorized" : "Error";
    String headers =
        "HTTP/1.1 "
            + status
            + " "
            + reason
            + "\r\nContent-Type: "
            + type
            + "\r\nContent-Length: "
            + size
            + "\r\n"
            + "Connection: close\r\n"
            + "Cache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline';"
            + " style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none';"
            + " frame-ancestors 'none'\r\n"
            + extra
            + "\r\n";
    out.write(headers.getBytes(StandardCharsets.US_ASCII));
  }
}
