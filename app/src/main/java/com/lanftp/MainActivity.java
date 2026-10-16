package com.lanftp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.security.SecureRandom;

public final class MainActivity extends Activity {
  private static final int PICK_FOLDER = 100;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView state, address, folderView, smbState;
  private EditText user, password, port, smbPort;
  private Switch writable, anonymous, anonymousWrite, smbEnabled;
  private Button start, choose;
  private Uri folder;
  private final int ink = Color.rgb(22, 37, 56),
      muted = Color.rgb(91, 108, 126),
      blue = Color.rgb(21, 101, 216);
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          update();
          handler.postDelayed(this, 2000);
        }
      };

  private int dp(int x) {
    return (int) (x * getResources().getDisplayMetrics().density + .5f);
  }

  private TextView text(String value, int size, int color) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setPadding(0, dp(6), 0, dp(6));
    return v;
  }

  private GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private Button button(String label) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setBackground(background(blue, 12));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
    p.setMargins(0, dp(10), 0, dp(10));
    b.setLayoutParams(p);
    return b;
  }

  private Switch toggle(LinearLayout parent, String label, boolean value) {
    Switch t = new Switch(this);
    t.setText(label);
    t.setTextColor(ink);
    t.setTextSize(15);
    t.setPadding(0, dp(10), 0, dp(10));
    t.setChecked(value);
    parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    return t;
  }

  private EditText field(LinearLayout parent, String label, String value, int type) {
    parent.addView(text(label, 13, muted));
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextSize(16);
    e.setTextColor(ink);
    e.setInputType(type);
    e.setText(value);
    e.setPadding(dp(12), dp(8), dp(12), dp(8));
    e.setBackground(background(Color.rgb(239, 243, 248), 8));
    parent.addView(e, new LinearLayout.LayoutParams(-1, dp(48)));
    return e;
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setStatusBarColor(Color.rgb(245, 248, 252));
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    scroll.setBackgroundColor(Color.rgb(245, 248, 252));
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(24), dp(28), dp(24), dp(28));
    scroll.addView(root);
    setContentView(scroll);
    root.addView(text("LAN / FILE SHARING · 3.0", 12, blue));
    TextView title = text("把手机文件，\n留在你的局域网。", 30, ink);
    title.setTypeface(null, Typeface.BOLD);
    root.addView(title);
    root.addView(text("网页 + FTP + SMB2 · 手机本地文件共享", 14, muted));
    state = text("服务器已停止", 17, blue);
    state.setTypeface(null, Typeface.BOLD);
    root.addView(state);
    address = text("启动后显示网页 / FTP / SMB 地址", 16, ink);
    address.setTextIsSelectable(true);
    address.setPadding(dp(16), dp(16), dp(16), dp(16));
    address.setBackground(background(Color.WHITE, 12));
    root.addView(address);
    smbState = text("SMB 尚未启动", 13, muted);
    root.addView(smbState);
    Button browser = button("在本机预览共享文件");
    root.addView(browser);
    browser.setOnClickListener(
        v -> {
          if (!FtpService.running || FtpService.activeWebPort == 0) {
            toast("请先启动共享");
            return;
          }
          try {
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + FtpService.activeWebPort)));
          } catch (ActivityNotFoundException e) {
            toast("请在其他设备的浏览器输入上方网页地址");
          }
        });
    root.addView(text("iPad：在 Safari 输入网页地址，即可浏览、下载和按权限上传、删除。", 13, muted));
    Button diagnostics = button("连接诊断");
    root.addView(diagnostics);
    diagnostics.setOnClickListener(
        v -> {
          String report = FtpService.diagnostics();
          new AlertDialog.Builder(this)
              .setTitle("连接诊断")
              .setMessage(report)
              .setPositiveButton("关闭", null)
              .setNeutralButton(
                  "复制诊断",
                  (dialog, which) -> {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("连接诊断", report));
                    toast("已复制诊断");
                  })
              .show();
        });
    Button copy = button("复制连接地址");
    root.addView(copy);
    copy.setOnClickListener(
        v -> {
          if (!FtpService.running) {
            toast("请先启动共享");
            return;
          }
          java.util.List<String> ips = FtpService.lanIps();
          if (ips.isEmpty()) {
            toast("未发现局域网地址");
            return;
          }
          java.util.List<String> options = new java.util.ArrayList<>();
          for (String ip : ips) {
            if (FtpService.activeWebPort != 0)
              options.add("http://" + ip + ":" + FtpService.activeWebPort);
            options.add("ftp://" + ip + ":" + FtpService.activePort);
            if (FtpService.activeSmbPort != 0)
              options.add(
                  "smb://"
                      + ip
                      + (FtpService.activeSmbPort == 445 ? "" : ":" + FtpService.activeSmbPort)
                      + "/share");
          }
          new AlertDialog.Builder(this)
              .setTitle("选择要复制的连接地址")
              .setItems(
                  options.toArray(new String[0]),
                  (dialog, which) -> {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("共享地址", options.get(which)));
                    toast("地址已复制");
                  })
              .show();
        });
    String savedFolder = getPreferences(0).getString("folder", null);
    if (savedFolder != null) folder = Uri.parse(savedFolder);
    root.addView(text("01  共享文件夹", 15, ink));
    folderView =
        text(folder == null ? "尚未授权，请选择手机上的文件夹" : Uri.decode(folder.toString()), 13, muted);
    root.addView(folderView);
    choose = button("选择 / 更换共享文件夹");
    root.addView(choose);
    choose.setOnClickListener(v -> chooseFolder(folder));
    root.addView(text("02  访问权限", 15, ink));
    writable = toggle(root, "允许账号用户上传、覆盖、删除文件", getPreferences(0).getBoolean("writable", false));
    anonymous = toggle(root, "允许匿名 / 来宾访问（无需密码）", getPreferences(0).getBoolean("anonymous", false));
    anonymousWrite =
        toggle(root, "同时允许匿名用户上传、删除", getPreferences(0).getBoolean("anonymousWritable", false));
    root.addView(text("匿名写入开启后，同一局域网内的其他人无需密码即可覆盖或删除共享文件。仅分享可以公开且已有备份的目录。", 12, muted));
    writable.setOnCheckedChangeListener(
        (b, checked) -> {
          if (!checked) anonymousWrite.setChecked(false);
          anonymousWrite.setEnabled(checked && anonymous.isChecked());
        });
    anonymous.setOnCheckedChangeListener(
        (b, checked) -> {
          if (!checked) anonymousWrite.setChecked(false);
          anonymousWrite.setEnabled(checked && writable.isChecked());
        });
    if (!writable.isChecked() || !anonymous.isChecked()) anonymousWrite.setChecked(false);
    anonymousWrite.setEnabled(writable.isChecked() && anonymous.isChecked());
    root.addView(text("03  账号与端口", 15, ink));
    user =
        field(root, "用户名", getPreferences(0).getString("user", "ftp"), InputType.TYPE_CLASS_TEXT);
    byte[] random = new byte[6];
    new SecureRandom().nextBytes(random);
    StringBuilder generated = new StringBuilder();
    for (byte b : random) generated.append(String.format(java.util.Locale.ROOT, "%02x", b));
    password =
        field(
            root,
            "密码（至少 8 位；不保存，每次打开重新生成）",
            generated.toString(),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    if (saved != null) password.setText(saved.getString("password", generated.toString()));
    if (FtpService.running) {
      user.setText(FtpService.activeUser);
      password.setText(FtpService.activePassword);
    }
    port =
        field(
            root,
            "端口（1024–65535）",
            getPreferences(0).getString("port", "2121"),
            InputType.TYPE_CLASS_NUMBER);
    smbEnabled =
        toggle(root, "同时启用 SMB2（支持 SMB 的客户端使用）", getPreferences(0).getBoolean("smbEnabled", true));
    smbPort =
        field(
            root,
            "SMB 端口（默认 445；不可用时自动尝试 1445）",
            getPreferences(0).getString("smbPort", "445"),
            InputType.TYPE_CLASS_NUMBER);
    if (FtpService.running) {
      writable.setChecked(FtpService.activePolicy.writable);
      anonymous.setChecked(FtpService.activePolicy.anonymous);
      anonymousWrite.setChecked(FtpService.activePolicy.anonymousWritable);
      smbEnabled.setChecked(FtpService.activeSmbEnabled);
    }
    start = button("启动文件共享");
    root.addView(start);
    start.setOnClickListener(v -> startServer());
    Button stop = button("停止共享");
    root.addView(stop);
    stop.setBackground(background(Color.rgb(69, 85, 106), 12));
    stop.setOnClickListener(v -> stopService(new Intent(this, FtpService.class)));
    root.addView(text("04  其他设备如何连接", 15, ink));
    root.addView(
        text(
            "手机与电脑 / iPad 连接同一个 Wi-Fi，或让其他设备连接手机热点。\n\n"
                + "iPad / Windows 浏览器：输入上方 http:// 网页地址，浏览并下载文件；开启写入权限后可上传、覆盖、删除。下载后可保存到 iPad 的文件"
                + " App。网页无需安装其他软件。\n\n"
                + "账号：未开启匿名访问时，浏览器会要求填写手机界面中的账号密码。匿名只读时，可点击网页的账号登录使用账号写入权限。\n\n"
                + "FTP：使用 FileZilla 或支持 FTP 的文件管理器。SMB：Windows 默认使用"
                + " \\\\手机IP\\share；高位端口需要支持自定义端口的客户端。\n\n"
                + "免 Root 手机可能无法监听 SMB 的 445 端口；高位端口不能保证兼容 iPad 原生文件 App，建议优先使用 Safari 网页。应用不会请求"
                + " Root。",
            14,
            muted));
    root.addView(
        text(
            "仅在可信局域网使用：网页与 FTP 明文传输，SMB2 也不提供文件内容加密，请勿设置路由器端口映射。Android"
                + " 只允许访问你授权的文件夹；部分系统禁止选择存储根目录、Android/data 等目录。\n\n"
                + "关闭共享可停止文件访问。系统省电策略可能暂停连接；Android 15 长时间后台传输可能在约 6 小时后停止，需要手动重新启动。",
            12,
            muted));
  }

  void chooseFolder(Uri initial) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    if (initial != null) {
      Uri document =
          android.provider.DocumentsContract.isTreeUri(initial)
              ? android.provider.DocumentsContract.buildDocumentUriUsingTree(
                  initial, android.provider.DocumentsContract.getTreeDocumentId(initial))
              : initial;
      intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, document);
    }
    try {
      startActivityForResult(intent, PICK_FOLDER);
    } catch (ActivityNotFoundException e) {
      toast("设备缺少系统文件选择器");
    }
  }

  private void startServer() {
    if (FtpService.running) {
      toast("请先停止当前共享再修改设置");
      return;
    }
    if (folder == null) {
      toast("请先授权共享文件夹");
      return;
    }
    String u = user.getText().toString().trim(), p = password.getText().toString();
    int n, s;
    try {
      n = Integer.parseInt(port.getText().toString());
      if (n < 1024 || n > 65535) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      toast("端口须为 1024–65535");
      return;
    }
    try {
      s = Integer.parseInt(smbPort.getText().toString());
      if (s != 445 && (s < 1024 || s > 65535)) throw new NumberFormatException();
      if (smbEnabled.isChecked() && s == n) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      toast("SMB 端口请选择 445 或 1024–65535，且不能与 FTP 相同");
      return;
    }
    boolean hasWrite = false;
    for (UriPermission permission : getContentResolver().getPersistedUriPermissions())
      if (permission.getUri().equals(folder) && permission.isWritePermission()) hasWrite = true;
    if (writable.isChecked() && !hasWrite) {
      toast("当前目录只有读取权限，请点击重新选择文件夹并授予写入权限");
      return;
    }
    if (AccessPolicy.isGuest(u)
        || u.isEmpty()
        || u.length() > 64
        || u.contains("\r")
        || u.contains("\n")
        || p.length() < 8
        || p.length() > 256
        || p.contains("\r")
        || p.contains("\n")) {
      toast("请填写独立用户名（不能为 guest / anonymous）及至少 8 位的密码");
      return;
    }
    getPreferences(0)
        .edit()
        .putString("user", u)
        .putString("port", Integer.toString(n))
        .putString("smbPort", Integer.toString(s))
        .putBoolean("smbEnabled", smbEnabled.isChecked())
        .putBoolean("writable", writable.isChecked())
        .putBoolean("anonymous", anonymous.isChecked())
        .putBoolean("anonymousWritable", anonymousWrite.isChecked())
        .apply();
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 101);
    Intent i =
        new Intent(this, FtpService.class)
            .putExtra("folder", folder.toString())
            .putExtra("user", u)
            .putExtra("password", p)
            .putExtra("port", n)
            .putExtra("smbPort", s)
            .putExtra("smbEnabled", smbEnabled.isChecked())
            .putExtra("writable", writable.isChecked())
            .putExtra("anonymous", anonymous.isChecked())
            .putExtra("anonymousWritable", anonymousWrite.isChecked());
    try {
      startForegroundService(i);
      start.setEnabled(false);
      choose.setEnabled(false);
      writable.setEnabled(false);
      anonymous.setEnabled(false);
      anonymousWrite.setEnabled(false);
      smbEnabled.setEnabled(false);
      toast("正在启动；当前界面中的账号密码用于本次共享");
    } catch (RuntimeException e) {
      toast("无法启动，请保持应用在前台后重试");
    }
  }

  private void setIfChanged(TextView view, String value) {
    if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
  }

  private void update() {
    boolean active = FtpService.running;
    setIfChanged(state, FtpService.status);
    start.setEnabled(!active && !FtpService.status.startsWith("正在"));
    choose.setEnabled(!active);
    user.setEnabled(!active);
    password.setEnabled(!active);
    port.setEnabled(!active);
    boolean enabled = !active && !FtpService.status.startsWith("正在");
    choose.setEnabled(enabled);
    user.setEnabled(enabled);
    password.setEnabled(enabled);
    port.setEnabled(enabled);
    writable.setEnabled(enabled);
    anonymous.setEnabled(enabled);
    anonymousWrite.setEnabled(enabled && writable.isChecked() && anonymous.isChecked());
    smbEnabled.setEnabled(enabled);
    smbPort.setEnabled(enabled);
    setIfChanged(smbState, FtpService.smbStatus);
    if (active) {
      setIfChanged(address, FtpService.addresses());
    } else setIfChanged(address, getString(R.string.address_pending));
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("password", password.getText().toString());
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == PICK_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
      Uri selected = data.getData();
      try {
        int granted = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
          granted |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        getContentResolver().takePersistableUriPermission(selected, granted);
        Uri old = folder;
        folder = selected;
        getPreferences(0).edit().putString("folder", folder.toString()).apply();
        folderView.setText(Uri.decode(folder.toString()));
        if (old != null && !old.equals(selected))
          try {
            getContentResolver()
                .releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
          } catch (SecurityException ignored) {
          }
      } catch (SecurityException e) {
        toast("无法保留授权，请重新选择手机本地文件夹");
      }
    }
  }
}
