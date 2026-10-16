package com.lanftp;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import java.net.*;
import java.util.*;

/** FTP and browser access always share the same immutable policy and granted document tree. */
public final class FtpService extends Service {
  public static volatile boolean running = false;
  public static volatile String activeUser = "", activePassword = "";
  public static volatile int activePort = 2121, activeWebPort = 0;
  public static volatile AccessPolicy activePolicy = AccessPolicy.READ_ONLY;
  public static volatile String status = "服务器已停止";
  public static volatile String folderStatus = "尚未检查共享目录";
  private static volatile WebServer diagnosticWeb;

  public static String diagnostics() {
    return "版本 3.1\n"
        + status
        + "\n"
        + folderStatus
        + "\n"
        + (diagnosticWeb == null ? "网页未启动" : diagnosticWeb.diagnostics());
  }

  private FtpServer ftp;
  private WebServer web;
  private PowerManager.WakeLock wake;
  private WifiManager.WifiLock wifi;
  private boolean destroyed, starting;
  private final Object lock = new Object();

  static List<String> lanIps() {
    List<String> ips = new ArrayList<>();
    try {
      List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
      interfaces.sort(
          Comparator.comparingInt(
              n -> {
                String name = n.getName().toLowerCase(Locale.ROOT);
                if (name.contains("wlan")
                    || name.startsWith("ap")
                    || name.startsWith("eth")
                    || name.contains("wifi")) return 0;
                if (name.contains("rmnet") || name.startsWith("ccmni")) return 2;
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("wg"))
                  return 3;
                return 1;
              }));
      for (NetworkInterface n : interfaces) {
        if (!n.isUp() || n.isLoopback()) continue;
        Enumeration<InetAddress> addresses = n.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress ip = addresses.nextElement();
          if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) ips.add(ip.getHostAddress());
        }
      }
    } catch (SocketException ignored) {
    }
    return ips;
  }

  public static String addresses() {
    List<String> lines = new ArrayList<>();
    List<String> ips = lanIps();
    for (String ip : ips.isEmpty() ? Collections.<String>emptyList() : ips.subList(0, 1)) {
      if (activeWebPort != 0) lines.add("网页  http://" + ip + ":" + activeWebPort);
      lines.add("FTP  ftp://" + ip + ":" + activePort);
    }
    if (ips.size() > 1) lines.add("其他网络地址可点“复制连接地址”选择");
    return lines.isEmpty()
        ? "未发现局域网 IPv4 地址，请连接 Wi-Fi 或开启热点"
        : android.text.TextUtils.join("\n", lines);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel("ftp", "局域网文件共享", NotificationManager.IMPORTANCE_LOW));
  }

  private Notification notification(String text) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, FtpService.class).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "ftp")
        .setContentTitle("局域网文件共享")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentIntent(open)
        .addAction(new Notification.Action.Builder(null, "停止共享", stop).build())
        .setOngoing(true)
        .build();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || "stop".equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    synchronized (lock) {
      if (ftp != null || starting) return START_NOT_STICKY;
      starting = true;
    }
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(1, notification("正在启动…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(1, notification("正在启动…"));
    status = "正在启动…";
    folderStatus = "正在检查共享目录";
    diagnosticWeb = null;
    String folder = intent.getStringExtra("folder"),
        user = intent.getStringExtra("user"),
        password = intent.getStringExtra("password");
    int port = intent.getIntExtra("port", 2121);
    AccessPolicy policy =
        new AccessPolicy(
            intent.getBooleanExtra("writable", false),
            intent.getBooleanExtra("anonymous", false),
            intent.getBooleanExtra("anonymousWritable", false));
    new Thread(
            () -> {
              try {
                if (folder == null
                    || user == null
                    || AccessPolicy.isGuest(user)
                    || password == null
                    || password.length() < 8) throw new IllegalArgumentException("请填写目录及独立账号密码");
                if (port < 1024 || port > 65535) throw new IllegalArgumentException("端口设置无效");
                Uri tree = Uri.parse(folder);
                boolean read = false, write = false;
                for (UriPermission permission : getContentResolver().getPersistedUriPermissions())
                  if (permission.getUri().equals(tree)) {
                    read = permission.isReadPermission();
                    write = permission.isWritePermission();
                  }
                if (!read || (policy.writable && !write))
                  throw new IllegalArgumentException("请重新选择文件夹，并允许所需的读写授权");
                SafFileSystem fs = new SafFileSystem(getContentResolver(), tree);
                if (!fs.stat("/").directory) throw new IllegalArgumentException("请选择文件夹");
                try {
                  int count = fs.list("/").size();
                  folderStatus = "启动时目录读取成功：" + count + " 项";
                } catch (java.io.IOException | SecurityException e) {
                  folderStatus = "目录读取失败，请重新选择文件夹并授权";
                  throw new IllegalArgumentException(folderStatus);
                }
                if (policy.writable && !fs.canCreate())
                  throw new IllegalArgumentException("所选文件夹不支持写入，请选择可写的本地目录");
                synchronized (lock) {
                  if (destroyed) return;
                  ftp = new FtpServer(fs, user, password, port, policy);
                  for (int candidate = 8080; candidate <= 8090; candidate++) {
                    if (candidate == port) continue;
                    try {
                      web = new WebServer(fs, user, password, candidate, policy);
                      break;
                    } catch (java.io.IOException unavailable) {
                      /* Try the next visible browser port. */
                    }
                  }
                  if (web == null) throw new java.io.IOException("网页端口 8080–8090 均不可用");
                  activeWebPort = web.port();
                  diagnosticWeb = web;
                  web.start();
                  wake =
                      ((PowerManager) getSystemService(POWER_SERVICE))
                          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanftp:sharing");
                  wake.acquire(6 * 60 * 60 * 1000L);
                  WifiManager wm =
                      (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                  if (wm != null) {
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanftp:wifi");
                    wifi.acquire();
                  }
                  activeUser = user;
                  activePassword = password;
                  activePort = port;
                  activePolicy = policy;
                  ftp.start();
                  running = true;
                  status =
                      "运行中 · "
                          + (policy.writable ? "账号可读写" : "只读")
                          + (policy.anonymous
                              ? (policy.anonymousWritable ? " · 匿名可读写" : " · 匿名只读")
                              : " · 需账号登录");
                  getSystemService(NotificationManager.class).notify(1, notification(status));
                }
              } catch (Exception e) {
                status =
                    "启动失败："
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                stopSelf();
              } finally {
                synchronized (lock) {
                  starting = false;
                }
              }
            },
            "sharing-start")
        .start();
    return START_NOT_STICKY;
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    status = "系统已停止长时间共享，请重新启动";
    stopSelf();
  }

  @Override
  public void onDestroy() {
    synchronized (lock) {
      destroyed = true;
      if (ftp != null) {
        ftp.close();
        ftp = null;
      }
      if (web != null) {
        web.close();
        web = null;
      }
      activeWebPort = 0;
      if (wake != null && wake.isHeld()) wake.release();
      if (wifi != null && wifi.isHeld()) wifi.release();
      running = false;
      activePassword = "";
      activeUser = "";
    }
    if (status.startsWith("运行") || status.startsWith("正在")) status = "服务器已停止";
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
