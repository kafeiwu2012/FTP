package com.lanftp;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Small SMB 2.0.2/2.1 disk server. NTLMv2 only; SMB1 file access and SMB3 encryption are not
 * supported.
 */
public final class SmbServer implements Closeable {
  private static final int DENIED = 0xc0000022,
      INVALID = 0xc000000d,
      UNSUPPORTED = 0xc00000bb,
      NOT_FOUND = 0xc0000034,
      COLLISION = 0xc0000035,
      CLOSED = 0xc0000128;
  private static final int MAX = 65536, MAX_FRAME = 1048576;
  private final FtpServer.Vfs fs;
  private final AccessPolicy policy;
  private final String user, password;
  private final ServerSocket listener;
  private final byte[] guid = new byte[16];
  private final long started = filetime(System.currentTimeMillis());
  private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
  private final ExecutorService workers =
      new ThreadPoolExecutor(0, 8, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
  private final Set<Handle> allHandles = ConcurrentHashMap.newKeySet();
  private volatile boolean running = true;
  private final ArrayDeque<String> events = new ArrayDeque<>();
  private final java.util.concurrent.atomic.AtomicLong connections =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong frames =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong commands =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong directoryQueries =
      new java.util.concurrent.atomic.AtomicLong();
  private final long diagnosticStarted = System.nanoTime();

  private synchronized void record(String event) {
    if (events.size() == 32) events.removeFirst();
    events.addLast(((System.nanoTime() - diagnosticStarted) / 1000000) + "ms " + event);
  }

  public synchronized String diagnostics() {
    return "监听端口="
        + port()
        + " TCP连接="
        + connections.get()
        + " 帧="
        + frames.get()
        + " 命令="
        + commands.get()
        + " 目录请求="
        + directoryQueries.get()
        + "\n"
        + String.join("\n", events);
  }

  private static long fileIndex(String path) {
    // One identity for enumeration and every metadata query, including nested paths.
    long value = 0xcbf29ce484222325L;
    for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
      value ^= b & 255;
      value *= 0x100000001b3L;
    }
    return value == 0 ? 1 : value;
  }

  public SmbServer(FtpServer.Vfs fs, String user, String password, int port, AccessPolicy policy)
      throws IOException {
    this.fs = fs;
    this.policy = policy;
    this.user = user;
    this.password = password;
    new SecureRandom().nextBytes(guid);
    listener = new ServerSocket();
    listener.setReuseAddress(true);
    listener.bind(new InetSocketAddress(port));
    record("SMB 监听已就绪");
  }

  public int port() {
    return listener.getLocalPort();
  }

  public void start() {
    new Thread(
            () -> {
              while (running)
                try {
                  Socket s = listener.accept();
                  long connection = connections.incrementAndGet();
                  record("TCP 连接 #" + connection);
                  s.setSoTimeout(120000);
                  clients.add(s);
                  try {
                    workers.execute(
                        () -> {
                          try {
                            new Session(s).run();
                          } finally {
                            clients.remove(s);
                            try {
                              s.close();
                            } catch (IOException ignored) {
                            }
                          }
                        });
                  } catch (RejectedExecutionException e) {
                    record("连接被拒绝：并发连接已满");
                    clients.remove(s);
                    s.close();
                  }
                } catch (IOException e) {
                  if (running) {
                    record("监听失败：" + e.getClass().getSimpleName());
                    close();
                  }
                }
            },
            "smb-accept")
        .start();
  }

  @Override
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

  private static ByteBuffer buf(byte[] b) {
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static ByteBuffer allocate(int n) {
    return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static int u16(byte[] b, int n) {
    return buf(b).getShort(n) & 65535;
  }

  private static int i32(byte[] b, int n) {
    return buf(b).getInt(n);
  }

  private static long i64(byte[] b, int n) {
    return buf(b).getLong(n);
  }

  private static byte[] slice(byte[] b, int off, int len) throws SmbException {
    if (off < 0 || len < 0 || off > b.length || len > b.length - off)
      throw new SmbException(INVALID);
    return Arrays.copyOfRange(b, off, off + len);
  }

  private static String utf16(byte[] b, int off, int len) throws SmbException {
    if ((len & 1) != 0) throw new SmbException(INVALID);
    return new String(slice(b, off, len), StandardCharsets.UTF_16LE);
  }

  private static byte[] utf16(String s) {
    return s.getBytes(StandardCharsets.UTF_16LE);
  }

  private static long filetime(long t) {
    return (t + 11644473600000L) * 10000;
  }

  private static long allocation(long size) {
    return (size + 4095) & ~4095L;
  }

  private static int attributes(FtpServer.Entry e) {
    return e.directory ? 0x10 : 0x80;
  }

  private static byte[] simple(int n) {
    return allocate(n).putShort((short) n).array();
  }

  private static final class SmbException extends IOException {
    final int status;

    SmbException(int s) {
      status = s;
    }
  }

  private static final class Handle {
    final byte[] id;
    volatile String path;
    final int desired, share;
    boolean deleteOnClose, pipe;
    byte[] rpc = new byte[0];
    List<FtpServer.Entry> listing;
    int cursor;

    Handle(byte[] id, String path, int desired, int share) {
      this.id = id;
      this.path = path;
      this.desired = desired;
      this.share = share;
    }

    boolean canRead() {
      return (desired & 0x92000001) != 0;
    }

    boolean canWrite() {
      return (desired & 0x52000006) != 0;
    }

    boolean canDelete() {
      return (desired & 0x12010000) != 0;
    }

    boolean canAttributes() {
      return (desired & 0x52000100) != 0;
    }
  }

  private final class Reply {
    int status = 0, tree;
    long session;
    byte[] bytes;

    Reply(byte[] b, int t, long s) {
      bytes = b;
      tree = t;
      session = s;
    }
  }

  private final class Session {
    private final Socket socket;
    private final Map<String, Handle> handles = new HashMap<>();
    private final Set<Integer> trees = new HashSet<>();
    private Ntlm ntlm;
    private boolean negotiated, authenticated, guest, signingRequired, rawNtlm, loggedOff;
    private byte[] key;
    private long sessionId = (new SecureRandom().nextLong() & Long.MAX_VALUE) | 1,
        handleCounter = 1;
    private int dialect, failures, lastTree;
    private final java.util.NavigableSet<Long> messageIds = new java.util.TreeSet<>();
    private Handle lastHandle;

    Session(Socket s) {
      socket = s;
    }

    void run() {
      try {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        while (running) {
          int type;
          try {
            type = in.readUnsignedByte();
          } catch (EOFException e) {
            record("客户端断开连接");
            break;
          }
          int length =
              (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
          if (type == 0x85 && length == 0) continue;
          if (type != 0 || length < 64 || length > MAX_FRAME) {
            record("无效帧头 类型=" + type + " 长度=" + length);
            break;
          }
          byte[] frame = new byte[length];
          in.readFully(frame);
          frames.incrementAndGet();
          if (frame[0] == (byte) 0xff && frame[4] == 0x72) {
            record("SMB1 协商探测，回应 SMB2");
            byte[] request = new byte[64];
            request[0] = (byte) 0xfe;
            request[1] = 'S';
            request[2] = 'M';
            request[3] = 'B';
            buf(request).putShort(4, (short) 64);
            byte[] body = negotiateBody(0x02ff);
            writeFrame(out, encode(request, new Reply(body, 0, 0), 0));
            continue;
          }
          List<byte[]> requests = new ArrayList<>();
          List<Reply> responses = new ArrayList<>();
          int pos = 0;
          boolean relatedFailed = false;
          lastTree = 0;
          lastHandle = null;
          while (pos < frame.length) {
            if (frame.length - pos < 64) throw new IOException("Short SMB header");
            int next = i32(frame, pos + 20);
            if (next != 0 && (next < 64 || (next & 7) != 0 || next > frame.length - pos))
              throw new IOException("Invalid compound");
            byte[] request = Arrays.copyOfRange(frame, pos, next == 0 ? frame.length : pos + next);
            if (i32(request, 0) != 0x424d53fe || u16(request, 4) != 64)
              throw new IOException("Invalid SMB header");
            boolean related = (i32(request, 16) & 4) != 0;
            Reply r;
            if (related && relatedFailed) {
              r =
                  new Reply(
                      allocate(9)
                          .putShort((short) 9)
                          .putShort((short) 0)
                          .putInt(0)
                          .put((byte) 0)
                          .array(),
                      lastTree,
                      sessionId);
              r.status = 0xc0000008;
            } else {
              r = dispatch(request);
              relatedFailed = r.status != 0;
            }
            requests.add(request);
            responses.add(r);
            if (next == 0) break;
            pos += next;
            if (requests.size() > 64) throw new IOException("Too many compound commands");
          }
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          for (int i = 0; i < responses.size(); i++) {
            Reply r = responses.get(i);
            int len = 64 + r.bytes.length;
            int padded = i == responses.size() - 1 ? len : (len + 7) & ~7;
            byte[] packet = encode(requests.get(i), r, i == responses.size() - 1 ? 0 : padded);
            if (padded > packet.length) packet = Arrays.copyOf(packet, padded);
            // Each compound member is signed independently, including its alignment padding.
            if (key != null && authenticated) {
              buf(packet).putInt(16, i32(packet, 16) | 8);
              Arrays.fill(packet, 48, 64, (byte) 0);
              System.arraycopy(Ntlm.hmac("HmacSHA256", key, packet), 0, packet, 48, 16);
            }
            output.write(packet);
          }
          writeFrame(out, output.toByteArray());
          if (failures >= 5 || loggedOff) break;
        }
      } catch (IOException | RuntimeException ignored) {
        if (running) record("连接结束：" + ignored.getClass().getSimpleName());
      } finally {
        for (Handle h : new ArrayList<>(handles.values())) closeQuietly(h);
        handles.clear();
      }
    }

    void writeFrame(OutputStream out, byte[] data) throws IOException {
      out.write(
          new byte[] {
            0, (byte) (data.length >> 16), (byte) (data.length >> 8), (byte) data.length
          });
      out.write(data);
      out.flush();
    }

    byte[] encode(byte[] request, Reply reply, int next) {
      byte[] result = new byte[64 + reply.bytes.length];
      ByteBuffer b = buf(result);
      b.putInt(0x424d53fe)
          .putShort((short) 64)
          .putShort((short) 1)
          .putInt(reply.status)
          .putShort((short) u16(request, 12))
          .putShort((short) 32)
          .putInt(1)
          .putInt(next)
          .putLong(i64(request, 24))
          .putInt(i32(request, 32))
          .putInt(reply.tree)
          .putLong(reply.session);
      System.arraycopy(reply.bytes, 0, result, 64, reply.bytes.length);
      return result;
    }

    Reply dispatch(byte[] request) {
      int command = u16(request, 12);
      int tree = i32(request, 36);
      long sid = i64(request, 40);
      boolean related = (i32(request, 16) & 4) != 0;
      if (related) {
        if (tree == -1) tree = lastTree;
        if (sid == -1) sid = sessionId;
      }
      Reply reply = new Reply(new byte[0], tree, sid);
      try {
        if ((i32(request, 16) & 1) != 0) throw new SmbException(INVALID);
        long message = i64(request, 24);
        if (message < 0
            || messageIds.contains(message)
            || (!messageIds.isEmpty() && message < messageIds.last() - 4096))
          throw new SmbException(INVALID);
        messageIds.add(message);
        while (messageIds.size() > 4096) messageIds.pollFirst();
        int[] sizes = {36, 25, 4, 9, 4, 57, 24, 24, 49, 49, 48, 57, 4, 4, 33, 32, 41, 33, 24};
        if (command < sizes.length) {
          int size = sizes[command];
          if (request.length < 64 + size - (size & 1) || u16(request, 64) != size)
            throw new SmbException(INVALID);
        }
        if (command != 0 && command != 1) {
          if (!authenticated || sid != sessionId) throw new SmbException(0xc0000203);
          boolean signed = (i32(request, 16) & 8) != 0;
          if ((signingRequired || signed) && !guest) {
            if (!signed || key == null) throw new SmbException(DENIED);
            byte[] copy = request.clone(), signature = slice(copy, 48, 16);
            Arrays.fill(copy, 48, 64, (byte) 0);
            if (!MessageDigest.isEqual(
                signature, Arrays.copyOf(Ntlm.hmac("HmacSHA256", key, copy), 16)))
              throw new SmbException(DENIED);
          }
        }
        if (command >= 4 && command != 13 && !trees.contains(tree))
          throw new SmbException(0xc00000c9);
        switch (command) {
          case 0:
            if (negotiated || authenticated) throw new SmbException(INVALID);
            if (request.length < 100) throw new SmbException(INVALID);
            int count = u16(request, 66);
            slice(request, 100, count * 2);
            dialect = 0;
            for (int i = 0; i < count; i++) {
              int d = u16(request, 100 + 2 * i);
              if ((d == 0x202 || d == 0x210) && d > dialect) dialect = d;
            }
            if (dialect == 0) throw new SmbException(UNSUPPORTED);
            signingRequired = (u16(request, 68) & 2) != 0;
            negotiated = true;
            reply.tree = 0;
            reply.session = 0;
            reply.bytes = negotiateBody(dialect);
            break;
          case 1:
            setup(request, reply);
            break;
          case 2:
            reply.bytes = simple(4);
            loggedOff = true;
            for (Handle h : new ArrayList<>(handles.values())) closeQuietly(h);
            handles.clear();
            trees.clear();
            break;
          case 3:
            {
              String unc = utf16(request, u16(request, 68), u16(request, 70));
              String[] parts = unc.replace('/', '\\').split("\\\\");
              String share = parts.length == 0 ? "" : parts[parts.length - 1];
              if (!share.equalsIgnoreCase("share") && !share.equalsIgnoreCase("IPC$"))
                throw new SmbException(0xc00000cc);
              tree = share.equalsIgnoreCase("IPC$") ? 2 : 1;
              trees.add(tree);
              lastTree = tree;
              reply.tree = tree;
              reply.bytes =
                  allocate(16)
                      .putShort((short) 16)
                      .put((byte) (tree == 2 ? 2 : 1))
                      .put((byte) 0)
                      .putInt(0)
                      .putInt(0)
                      .putInt(
                          tree == 2
                              ? 0x0012019f
                              : (policy.canWrite(guest) ? 0x001301ff : 0x001200a9))
                      .array();
              break;
            }
          case 4:
            trees.remove(tree);
            for (Handle h : new ArrayList<>(handles.values()))
              if ((h.pipe ? 2 : 1) == tree) {
                closeQuietly(h);
                handles.remove(id(h.id));
              }
            reply.bytes = simple(4);
            break;
          case 5:
            reply.bytes = create(request, tree);
            break;
          case 6:
            {
              Handle h = handle(request, 72);
              FtpServer.Entry e =
                  h.pipe ? new FtpServer.Entry("srvsvc", false, 0, 0) : fs.stat(h.path);
              if (h.deleteOnClose && fs.stat(h.path).directory && !fs.list(h.path).isEmpty())
                throw new SmbException(0xc0000101);
              closeHandle(h);
              handles.remove(id(h.id));
              reply.bytes =
                  allocate(60)
                      .putShort((short) 60)
                      .putShort((short) 1)
                      .putInt(0)
                      .putLong(filetime(e.modified))
                      .putLong(filetime(e.modified))
                      .putLong(filetime(e.modified))
                      .putLong(filetime(e.modified))
                      .putLong(allocation(e.size))
                      .putLong(e.size)
                      .putInt(attributes(e))
                      .array();
              break;
            }
          case 7:
            handle(request, 72);
            reply.bytes = simple(4);
            break;
          case 8:
            reply.bytes = read(request);
            break;
          case 9:
            reply.bytes = write(request);
            break;
          case 10:
            throw new SmbException(UNSUPPORTED); // never claim a lock that has not been acquired
          case 11:
            reply.bytes = ioctl(request);
            break;
          case 12:
            throw new SmbException(UNSUPPORTED);
          case 13:
            reply.bytes = simple(4);
            break;
          case 14:
            reply.bytes = queryDirectory(request);
            break;
          case 15:
            throw new SmbException(UNSUPPORTED);
          case 16:
            reply.bytes = queryInfo(request);
            break;
          case 17:
            reply.bytes = setInfo(request);
            break;
          case 18:
            reply.bytes = simple(24);
            break;
          default:
            throw new SmbException(UNSUPPORTED);
        }
      } catch (SmbException e) {
        reply.status = e.status;
      } catch (FileNotFoundException e) {
        reply.status = NOT_FOUND;
      } catch (SecurityException e) {
        reply.status = DENIED;
      } catch (IOException e) {
        reply.status = DENIED;
      } catch (RuntimeException e) {
        reply.status = INVALID;
      }
      if (reply.status != 0 && reply.bytes.length == 0)
        reply.bytes =
            allocate(9).putShort((short) 9).putShort((short) 0).putInt(0).put((byte) 0).array();
      commands.incrementAndGet();
      if (command == 14) directoryQueries.incrementAndGet();
      {
        int cls =
            request.length > 67 && (command == 14 || command == 16)
                ? request[command == 14 ? 66 : 67] & 255
                : -1;
        record(String.format(Locale.ROOT, "命令=%d 类型=%d 状态=0x%08x", command, cls, reply.status));
      }
      return reply;
    }

    void setup(byte[] request, Reply reply) throws IOException {
      if (!negotiated || authenticated) throw new SmbException(INVALID);
      if (u16(request, 78) > 16384) throw new SmbException(INVALID);
      byte[] security = slice(request, u16(request, 76), u16(request, 78));
      reply.session = sessionId;
      reply.tree = 0;
      if (security.length == 0) {
        if (!policy.anonymous || signingRequired) throw new SmbException(DENIED);
        guest = true;
        authenticated = true;
        reply.bytes = allocate(8).putShort((short) 9).putShort((short) 2).putInt(0).array();
        return;
      }
      byte[] token = Ntlm.raw(security);
      int type = Ntlm.type(token);
      byte[] response;
      if (type == 1) {
        rawNtlm = security.length >= 8 && security[0] == 'N' && security[1] == 'T';
        ntlm = new Ntlm();
        response =
            rawNtlm ? ntlm.challengeToken() : Ntlm.responseToken(ntlm.challengeToken(), false);
        reply.status = 0xc0000016;
      } else if (type == 3 && ntlm != null) {
        String name = Ntlm.username(token);
        guest = AccessPolicy.isGuest(name);
        try {
          if (guest) {
            if (!policy.anonymous || signingRequired) throw new IOException("Guest unavailable");
            key = null;
          } else key = ntlm.authenticate(token, user, password);
        } catch (IOException e) {
          ntlm = null;
          failures++;
          throw new SmbException(0xc000006d);
        }
        authenticated = true;
        ntlm = null;
        response = rawNtlm ? new byte[0] : Ntlm.responseToken(new byte[0], true);
      } else throw new SmbException(INVALID);
      reply.bytes =
          Ntlm.join(
              allocate(8)
                  .putShort((short) 9)
                  .putShort((short) (authenticated && guest ? 1 : 0))
                  .putShort((short) 72)
                  .putShort((short) response.length)
                  .array(),
              response);
    }

    String path(byte[] request, int off, int len) throws IOException {
      if (len > 8192) throw new SmbException(INVALID);
      String name = utf16(request, off, len).replace('\\', '/');
      if (name.indexOf(':') >= 0) throw new SmbException(UNSUPPORTED);
      return FtpServer.normalize("/", name);
    }

    void requireWrite() throws SmbException {
      if (!policy.canWrite(guest)) throw new SmbException(DENIED);
    }

    Handle handle(byte[] request, int off) throws IOException {
      byte[] fid = slice(request, off, 16);
      boolean previous = true;
      for (byte n : fid)
        if (n != (byte) 0xff) {
          previous = false;
          break;
        }
      Handle h = previous && (i32(request, 16) & 4) != 0 ? lastHandle : handles.get(id(fid));
      if (h == null) throw new SmbException(CLOSED);
      return h;
    }

    String id(byte[] b) {
      return Base64.getEncoder().encodeToString(b);
    }

    byte[] create(byte[] request, int tree) throws IOException {
      if (handles.size() >= 256) throw new SmbException(0xc000009a);
      String p = path(request, u16(request, 108), u16(request, 110));
      int desired = i32(request, 88),
          disposition = i32(request, 100),
          options = i32(request, 104),
          sharing = i32(request, 96);
      Handle h =
          new Handle(
              allocate(16).putLong(sessionId).putLong(handleCounter++).array(),
              p,
              desired,
              sharing);
      byte[] responseContexts = maximalAccessContext(request, tree == 2);
      FtpServer.Entry e;
      int action = 1;
      if (tree == 2) {
        if (!p.equalsIgnoreCase("/srvsvc")) throw new SmbException(NOT_FOUND);
        h.pipe = true;
        e = new FtpServer.Entry("srvsvc", false, 0, 0);
      } else {
        if (disposition < 0 || disposition > 5) throw new SmbException(INVALID);
        if ((options & 0x1000) != 0 && (!h.canDelete() || p.equals("/")))
          throw new SmbException(DENIED);
        boolean mutating = (desired & 0x500d0156) != 0 || (options & 0x1000) != 0;
        if (mutating) requireWrite();
        if ((desired & 0x02000000) != 0) {
          desired = (desired & ~0x02000000) | (policy.canWrite(guest) ? 0x001301ff : 0x00120089);
          h = new Handle(h.id, p, desired, sharing);
        }
        boolean exists = true;
        try {
          e = fs.stat(p);
        } catch (FileNotFoundException absent) {
          e = null;
          exists = false;
        }
        if (exists && disposition == 2) throw new SmbException(COLLISION);
        if (!exists && (disposition == 1 || disposition == 4)) throw new SmbException(NOT_FOUND);
        boolean directory = (options & 1) != 0;
        if (exists && directory && !e.directory) throw new SmbException(0xc0000103);
        if (exists && (options & 0x40) != 0 && e.directory) throw new SmbException(0xc00000ba);
        synchronized (allHandles) {
          for (Handle other : allHandles) {
            if (other.pipe || !other.path.equals(p)) continue;
            if ((h.canRead() && (other.share & 1) == 0)
                || (h.canWrite() && (other.share & 2) == 0)
                || (h.canDelete() && (other.share & 4) == 0)
                || (other.canRead() && (sharing & 1) == 0)
                || (other.canWrite() && (sharing & 2) == 0)
                || (other.canDelete() && (sharing & 4) == 0)) throw new SmbException(0xc0000043);
          }
          if (!exists) {
            requireWrite();
            if (directory) fs.mkdir(p);
            else try (OutputStream ignored = fs.write(p, false)) {}
            action = 2;
            e = fs.stat(p);
          } else if (disposition == 0 || disposition == 4 || disposition == 5) {
            requireWrite();
            if (e.directory) throw new SmbException(0xc00000ba);
            fs.resize(p, 0);
            action = disposition == 0 ? 0 : 3;
            e = fs.stat(p);
          }
          allHandles.add(h);
        }
        h.deleteOnClose = (options & 0x1000) != 0;
      }
      handles.put(id(h.id), h);
      lastHandle = h;
      byte[] contexts = responseContexts;
      return Ntlm.join(
          allocate(88)
              .putShort((short) 89)
              .put((byte) 0)
              .put((byte) 0)
              .putInt(action)
              .putLong(filetime(e.modified))
              .putLong(filetime(e.modified))
              .putLong(filetime(e.modified))
              .putLong(filetime(e.modified))
              .putLong(allocation(e.size))
              .putLong(e.size)
              .putInt(attributes(e))
              .putInt(0)
              .put(h.id)
              .putInt(contexts.length == 0 ? 0 : 152)
              .putInt(contexts.length)
              .array(),
          contexts);
    }

    byte[] maximalAccessContext(byte[] request, boolean ipc) throws IOException {
      int offset = i32(request, 112), length = i32(request, 116);
      if (length == 0) return new byte[0];
      byte[] contexts = slice(request, offset, length);
      int pos = 0;
      while (pos < contexts.length) {
        if (contexts.length - pos < 16) throw new SmbException(INVALID);
        int next = i32(contexts, pos),
            nameOffset = u16(contexts, pos + 4),
            nameLength = u16(contexts, pos + 6);
        if (next != 0 && (next < 16 || (next & 7) != 0 || next > contexts.length - pos))
          throw new SmbException(INVALID);
        byte[] ctx = Arrays.copyOfRange(contexts, pos, next == 0 ? contexts.length : pos + next);
        String name = new String(slice(ctx, nameOffset, nameLength), StandardCharsets.US_ASCII);
        if (name.equals("MxAc"))
          return allocate(32)
              .putInt(0)
              .putShort((short) 16)
              .putShort((short) 4)
              .putShort((short) 0)
              .putShort((short) 24)
              .putInt(8)
              .put(new byte[] {'M', 'x', 'A', 'c'})
              .putInt(0)
              .putInt(0)
              .putInt(ipc ? 0x0012019f : (policy.canWrite(guest) ? 0x001301ff : 0x001200a9))
              .array();
        if (next == 0) break;
        pos += next;
      }
      return new byte[0];
    }

    void closeHandle(Handle h) throws IOException {
      if (h.deleteOnClose && !h.pipe) {
        requireWrite();
        fs.delete(h.path, fs.stat(h.path).directory);
      }
      allHandles.remove(h);
    }

    void closeQuietly(Handle h) {
      try {
        closeHandle(h);
      } catch (IOException | SecurityException ignored) {
        allHandles.remove(h);
      }
    }

    byte[] read(byte[] request) throws IOException {
      Handle h = handle(request, 80);
      int count = i32(request, 68);
      long off = i64(request, 72);
      if (count < 0 || count > MAX || off < 0) throw new SmbException(INVALID);
      byte[] data;
      if (h.pipe) {
        data = h.rpc;
        h.rpc = new byte[0];
        if (data.length == 0) throw new SmbException(0xc0000011);
      } else {
        if (!h.canRead()) throw new SmbException(DENIED);
        if (fs.stat(h.path).directory) throw new SmbException(0xc00000ba);
        try (InputStream input = fs.open(h.path)) {
          long left = off;
          while (left > 0) {
            long n = input.skip(left);
            if (n == 0) {
              if (input.read() < 0) throw new SmbException(0xc0000011);
              n = 1;
            }
            left -= n;
          }
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          byte[] bytes = new byte[Math.min(count, 8192)];
          int remaining = count;
          while (remaining > 0) {
            int n = input.read(bytes, 0, Math.min(remaining, bytes.length));
            if (n < 0) break;
            if (n == 0) throw new IOException("Read made no progress");
            output.write(bytes, 0, n);
            remaining -= n;
          }
          data = output.toByteArray();
        }
        if (data.length == 0 && count != 0) throw new SmbException(0xc0000011);
      }
      return Ntlm.join(
          allocate(16)
              .putShort((short) 17)
              .put((byte) 80)
              .put((byte) 0)
              .putInt(data.length)
              .putInt(0)
              .putInt(0)
              .array(),
          data);
    }

    byte[] write(byte[] request) throws IOException {
      Handle h = handle(request, 80);
      int count = i32(request, 68);
      long off = i64(request, 72);
      if (count < 0 || count > MAX || off < 0 || off > Long.MAX_VALUE - count)
        throw new SmbException(INVALID);
      byte[] bytes = slice(request, u16(request, 66), count);
      if (h.pipe) h.rpc = RpcPipe.exchange(bytes);
      else {
        requireWrite();
        if (!h.canWrite()) throw new SmbException(DENIED);
        fs.writeAt(h.path, off, bytes);
      }
      return allocate(16)
          .putShort((short) 17)
          .putShort((short) 0)
          .putInt(count)
          .putInt(0)
          .putInt(0)
          .array();
    }

    byte[] ioctl(byte[] request) throws IOException {
      int code = i32(request, 68);
      byte[] output = new byte[0];
      Handle h = null;
      if (code == 0x0011c017) {
        h = handle(request, 72);
        if (!h.pipe) throw new SmbException(UNSUPPORTED);
        output = RpcPipe.exchange(slice(request, i32(request, 88), i32(request, 92)));
      } else if (code == 0x00110018) {
      } else if (code == 0x000900c4) {
        requireWrite();
        h = handle(request, 72);
        if (!h.canWrite()) throw new SmbException(DENIED);
      } else throw new SmbException(UNSUPPORTED);
      int max = i32(request, 108);
      if (max < output.length) throw new SmbException(0xc0000023);
      return Ntlm.join(
          allocate(48)
              .putShort((short) 49)
              .putShort((short) 0)
              .putInt(code)
              .put(h == null ? slice(request, 72, 16) : h.id)
              .putInt(0)
              .putInt(0)
              .putInt(output.length == 0 ? 0 : 112)
              .putInt(output.length)
              .putInt(1)
              .putInt(0)
              .array(),
          output);
    }

    byte[] queryDirectory(byte[] request) throws IOException {
      Handle h = handle(request, 72);
      if (h.pipe || !fs.stat(h.path).directory) throw new SmbException(0xc0000103);
      int cls = request[66] & 255, flags = request[67] & 255, max = Math.min(MAX, i32(request, 92));
      if (max <= 0) throw new SmbException(INVALID);
      if ((flags & 1) != 0 || (flags & 0x10) != 0) h.listing = null;
      if (h.listing == null) {
        h.listing = new ArrayList<>();
        String pattern = utf16(request, u16(request, 88), u16(request, 90));
        if (pattern.isEmpty()) pattern = "*";
        final String glob = pattern;
        for (FtpServer.Entry e : fs.list(h.path)) if (matches(glob, e.name)) h.listing.add(e);
        h.cursor = 0;
      }
      if (h.cursor >= h.listing.size()) throw new SmbException(0x80000006);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      List<byte[]> records = new ArrayList<>();
      int length = 0;
      while (h.cursor < h.listing.size()) {
        FtpServer.Entry e = h.listing.get(h.cursor);
        byte[] record = directoryEntry(cls, e, h.cursor + 1, FtpServer.normalize(h.path, e.name));
        int padded = (record.length + 7) & ~7;
        if (length + padded > max) {
          if (records.isEmpty()) throw new SmbException(0xc0000023);
          break;
        }
        records.add(Arrays.copyOf(record, padded));
        length += padded;
        h.cursor++;
        if ((flags & 2) != 0) break;
      }
      for (int i = 0; i < records.size(); i++) {
        byte[] record = records.get(i);
        buf(record).putInt(0, i == records.size() - 1 ? 0 : record.length);
        out.write(record);
      }
      byte[] data = out.toByteArray();
      return Ntlm.join(
          allocate(8).putShort((short) 9).putShort((short) 72).putInt(data.length).array(), data);
    }

    byte[] directoryEntry(int cls, FtpServer.Entry e, int index, String path) throws IOException {
      byte[] name = utf16(e.name);
      int prefix;
      switch (cls) {
        case 1:
          prefix = 64;
          break;
        case 2:
          prefix = 68;
          break;
        case 3:
          prefix = 94;
          break;
        case 12:
          prefix = 12;
          break;
        case 37:
          prefix = 104;
          break;
        case 38:
          prefix = 80;
          break;
        case 60:
          prefix = 88;
          break;
        case 63:
          prefix = 114;
          break;
        default:
          throw new SmbException(UNSUPPORTED);
      }
      ByteBuffer b = allocate(prefix + name.length);
      b.putInt(0).putInt(index);
      if (cls == 12) b.putInt(name.length);
      else {
        long t = filetime(e.modified);
        b.putLong(t)
            .putLong(t)
            .putLong(t)
            .putLong(t)
            .putLong(e.size)
            .putLong(allocation(e.size))
            .putInt(attributes(e))
            .putInt(name.length);
        if (cls == 37) b.putLong(96, fileIndex(path));
        if (cls == 38) b.putLong(72, fileIndex(path));
        if (cls == 60 || cls == 63) b.putLong(72, fileIndex(path)).putLong(80, 0);
      }
      b.position(prefix);
      b.put(name);
      return b.array();
    }

    boolean matches(String pattern, String name) {
      if (pattern.length() > 1024) return false;
      if (pattern.equals("*.*")) pattern = "*";
      pattern =
          pattern.toLowerCase(Locale.ROOT).replace('<', '*').replace('>', '?').replace('"', '.');
      name = name.toLowerCase(Locale.ROOT);
      int p = 0, n = 0, star = -1, retry = 0;
      while (n < name.length()) {
        if (p < pattern.length()
            && (pattern.charAt(p) == '?' || pattern.charAt(p) == name.charAt(n))) {
          p++;
          n++;
        } else if (p < pattern.length() && pattern.charAt(p) == '*') {
          star = p++;
          retry = n;
        } else if (star >= 0) {
          p = star + 1;
          n = ++retry;
        } else return false;
      }
      while (p < pattern.length() && pattern.charAt(p) == '*') p++;
      return p == pattern.length();
    }

    byte[] queryInfo(byte[] request) throws IOException {
      Handle h = handle(request, 88);
      if (h.pipe) throw new SmbException(UNSUPPORTED);
      FtpServer.Entry e = fs.stat(h.path);
      int type = request[66] & 255, cls = request[67] & 255;
      byte[] info;
      if (type == 1) info = fileInfo(cls, h, e);
      else if (type == 2) info = fsInfo(cls);
      else throw new SmbException(UNSUPPORTED);
      if (i32(request, 68) < info.length) throw new SmbException(0xc0000023);
      return Ntlm.join(
          allocate(8).putShort((short) 9).putShort((short) 72).putInt(info.length).array(), info);
    }

    byte[] basic(FtpServer.Entry e) {
      long t = filetime(e.modified);
      return allocate(40)
          .putLong(t)
          .putLong(t)
          .putLong(t)
          .putLong(t)
          .putInt(attributes(e))
          .putInt(0)
          .array();
    }

    byte[] standard(FtpServer.Entry e, Handle h) {
      return allocate(24)
          .putLong(allocation(e.size))
          .putLong(e.size)
          .putInt(1)
          .put((byte) (h.deleteOnClose ? 1 : 0))
          .put((byte) (e.directory ? 1 : 0))
          .putShort((short) 0)
          .array();
    }

    byte[] fileInfo(int cls, Handle h, FtpServer.Entry e) throws IOException {
      byte[] name = utf16(h.path.replace('/', '\\'));
      long index = fileIndex(h.path);
      switch (cls) {
        case 4:
          return basic(e);
        case 5:
          return standard(e, h);
        case 6:
          return allocate(8).putLong(index).array();
        case 7:
          return new byte[4];
        case 8:
          return allocate(4).putInt(h.desired).array();
        case 17:
          return new byte[4];
        case 28:
          return allocate(16).putLong(e.size).putShort((short) 0).array();
        case 59:
          return allocate(24).putLong(1).putLong(index).putLong(0).array();
        case 9:
        case 48:
          return Ntlm.join(allocate(4).putInt(name.length).array(), name);
        case 14:
          return new byte[8];
        case 16:
          return new byte[4];
        case 18:
          return Ntlm.join(
              basic(e),
              standard(e, h),
              allocate(36)
                  .putLong(index)
                  .putInt(0)
                  .putInt(h.desired)
                  .putLong(0)
                  .putInt(0)
                  .putInt(0)
                  .putInt(name.length)
                  .array(),
              name);
        case 22:
          if (e.directory) return new byte[0];
          byte[] stream = utf16("::$DATA");
          return Ntlm.join(
              allocate(24)
                  .putInt(0)
                  .putInt(stream.length)
                  .putLong(e.size)
                  .putLong(allocation(e.size))
                  .array(),
              stream);
        case 34:
          long t = filetime(e.modified);
          return allocate(56)
              .putLong(t)
              .putLong(t)
              .putLong(t)
              .putLong(t)
              .putLong(allocation(e.size))
              .putLong(e.size)
              .putInt(attributes(e))
              .putInt(0)
              .array();
        case 35:
          return allocate(8).putInt(attributes(e)).putInt(0).array();
        default:
          throw new SmbException(UNSUPPORTED);
      }
    }

    byte[] fsInfo(int cls) throws IOException {
      switch (cls) {
        case 1:
          byte[] label = utf16("LAN Share");
          return Ntlm.join(
              allocate(18)
                  .putLong(started)
                  .putInt(0x4c414e46)
                  .putInt(label.length)
                  .put((byte) 0)
                  .put((byte) 0)
                  .array(),
              label);
        case 4:
          return allocate(8).putInt(7).putInt(0).array();
        case 5:
          byte[] type = utf16("SAF");
          return Ntlm.join(allocate(12).putInt(6).putInt(255).putInt(type.length).array(), type);
        case 3:
        case 7:
          long[] space = fs.space();
          ByteBuffer b = allocate(cls == 3 ? 24 : 32);
          b.putLong(space[0] / 4096).putLong(space[1] / 4096);
          if (cls == 7) b.putLong(space[1] / 4096);
          return b.putInt(8).putInt(512).array();
        case 11:
          return allocate(28)
              .putInt(512)
              .putInt(4096)
              .putInt(4096)
              .putInt(4096)
              .putInt(0)
              .putInt(0)
              .putInt(0)
              .array();
        default:
          throw new SmbException(UNSUPPORTED);
      }
    }

    byte[] setInfo(byte[] request) throws IOException {
      requireWrite();
      Handle h = handle(request, 80);
      if (h.pipe) throw new SmbException(UNSUPPORTED);
      if ((request[66] & 255) != 1) throw new SmbException(UNSUPPORTED);
      int cls = request[67] & 255;
      byte[] data = slice(request, u16(request, 72), i32(request, 68));
      switch (cls) {
        case 4:
          if (!h.canAttributes()) throw new SmbException(DENIED);
          if (data.length < 40) throw new SmbException(INVALID);
          long timestamp = i64(data, 16);
          if (timestamp != 0 && timestamp != -1) {
            fs.setModified(h.path, timestamp / 10000 - 11644473600000L);
          }
          break;
        case 10:
        case 65:
          if (!h.canDelete()) throw new SmbException(DENIED);
          if (data.length < 20 || i64(data, 8) != 0) throw new SmbException(INVALID);
          String dest = path(data, 20, i32(data, 16));
          synchronized (allHandles) {
            fs.rename(h.path, dest);
            String previous = h.path;
            for (Handle other : allHandles) {
              if (other.path.equals(previous)) other.path = dest;
              else if (other.path.startsWith(previous + "/"))
                other.path = dest + other.path.substring(previous.length());
            }
          }
          break;
        case 13:
        case 64:
          if (!h.canDelete()) throw new SmbException(DENIED);
          if (data.length < (cls == 13 ? 1 : 4)) throw new SmbException(INVALID);
          boolean deleting = cls == 13 ? data[0] != 0 : (i32(data, 0) & 1) != 0;
          if (h.path.equals("/")) throw new SmbException(DENIED);
          if (deleting && fs.stat(h.path).directory && !fs.list(h.path).isEmpty())
            throw new SmbException(0xc0000101);
          h.deleteOnClose = deleting;
          break;
        case 19:
        case 20:
          if (!h.canWrite()) throw new SmbException(DENIED);
          if (data.length != 8 || i64(data, 0) < 0) throw new SmbException(INVALID);
          if (cls == 20) fs.resize(h.path, i64(data, 0));
          break;
        default:
          throw new SmbException(UNSUPPORTED);
      }
      return simple(2);
    }
  }

  private byte[] negotiateBody(int dialect) {
    byte[] token = Ntlm.initialToken();
    return Ntlm.join(
        allocate(64)
            .putShort((short) 65)
            .putShort((short) 1)
            .putShort((short) dialect)
            .putShort((short) 0)
            .put(guid)
            .putInt(0)
            .putInt(MAX)
            .putInt(MAX)
            .putInt(MAX)
            .putLong(filetime(System.currentTimeMillis()))
            .putLong(started)
            .putShort((short) 128)
            .putShort((short) token.length)
            .putInt(0)
            .array(),
        token);
  }
}
