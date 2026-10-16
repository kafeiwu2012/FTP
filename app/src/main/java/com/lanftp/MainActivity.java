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
    private static final int PICK_FOLDER=100;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state,address,folderView;private EditText user,password,port;private Button start,choose;private Uri folder;
    private final int ink=Color.rgb(22,37,56),muted=Color.rgb(91,108,126),blue=Color.rgb(21,101,216);
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,1000);}};
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));return v;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(background(blue,12));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.setMargins(0,dp(10),0,dp(10));b.setLayoutParams(p);return b;}
    private EditText field(LinearLayout parent,String label,String value,int type){parent.addView(text(label,13,muted));EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(ink);e.setInputType(type);e.setText(value);e.setPadding(dp(12),dp(8),dp(12),dp(8));e.setBackground(background(Color.rgb(239,243,248),8));parent.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));return e;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(Color.rgb(245,248,252));getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });scroll.setBackgroundColor(Color.rgb(245,248,252));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(28),dp(24),dp(28));scroll.addView(root);setContentView(scroll);
        root.addView(text("LAN / FILE SHARING",12,blue));TextView title=text("把手机文件，\n留在你的局域网。",30,ink);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        root.addView(text("无需数据线 · 无需云端 · 只读保护",14,muted));
        state=text("服务器已停止",17,blue);state.setTypeface(null,Typeface.BOLD);root.addView(state);
        address=text("启动后显示 FTP 连接地址",18,ink);address.setTextIsSelectable(true);address.setPadding(dp(16),dp(16),dp(16),dp(16));address.setBackground(background(Color.WHITE,12));root.addView(address);
        Button copy=button("复制连接地址");root.addView(copy);copy.setOnClickListener(v->{String a=address.getText().toString();if(!a.startsWith("ftp://")){toast("请先启动服务器并连接局域网");return;}((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("FTP 地址",a));toast("连接地址已复制");});
        String savedFolder=getPreferences(0).getString("folder",null);if(savedFolder!=null)folder=Uri.parse(savedFolder);
        root.addView(text("01  共享文件夹",15,ink));folderView=text(folder==null?"尚未授权，请选择手机上的文件夹":Uri.decode(folder.toString()),13,muted);root.addView(folderView);
        choose=button("选择 / 更换共享文件夹");root.addView(choose);choose.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);try{startActivityForResult(i,PICK_FOLDER);}catch(ActivityNotFoundException e){toast("设备缺少系统文件选择器");}});
        root.addView(text("02  连接凭据",15,ink));
        user=field(root,"用户名",getPreferences(0).getString("user","ftp"),InputType.TYPE_CLASS_TEXT);
        byte[] random=new byte[6];new SecureRandom().nextBytes(random);StringBuilder generated=new StringBuilder();for(byte b:random)generated.append(String.format(java.util.Locale.ROOT,"%02x",b));
        password=field(root,"密码（至少 8 位；不保存，每次打开重新生成）",generated.toString(),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if(saved!=null)password.setText(saved.getString("password",generated.toString()));
        if(FtpService.running){user.setText(FtpService.activeUser);password.setText(FtpService.activePassword);}
        port=field(root,"端口（1024–65535）",getPreferences(0).getString("port","2121"),InputType.TYPE_CLASS_NUMBER);
        start=button("启动 FTP 服务器");root.addView(start);start.setOnClickListener(v->startServer());Button stop=button("停止共享");root.addView(stop);stop.setBackground(background(Color.rgb(69,85,106),12));stop.setOnClickListener(v->stopService(new Intent(this,FtpService.class)));
        root.addView(text("03  其他设备如何连接",15,ink));root.addView(text("手机与电脑 / iPad 连接同一个 Wi-Fi（也可使用手机热点）。\n\nWindows：文件资源管理器地址栏输入上方 ftp:// 地址，按提示输入用户名和密码；也可使用 FileZilla。\niPad：使用支持 FTP 的客户端（如 FE File Explorer），填写主机、端口、用户名和密码；系统“文件”App 不支持 FTP。\n\n支持浏览、下载、中文文件名和断点续传。共享为只读，不允许修改或删除手机文件。",14,muted));
        root.addView(text("仅在可信局域网使用：FTP 的账号和文件以明文传输，请勿设置路由器端口映射。Android 只允许访问你授权的文件夹；部分系统禁止选择存储根目录、Android/data 等目录。\n\n关闭共享可停止文件访问。系统省电策略可能暂停连接；Android 15 长时间后台传输可能在约 6 小时后停止，需要手动重新启动。",12,muted));
    }
    private void startServer(){
        if(FtpService.running){toast("请先停止当前共享再修改设置");return;}
        if(folder==null){toast("请先授权共享文件夹");return;}
        String u=user.getText().toString().trim(),p=password.getText().toString();int n;
        try{n=Integer.parseInt(port.getText().toString());if(n<1024||n>65535)throw new NumberFormatException();}catch(NumberFormatException e){toast("端口须为 1024–65535");return;}
        if(u.isEmpty()||u.length()>64||u.contains("\r")||u.contains("\n")||p.length()<8||p.length()>256||p.contains("\r")||p.contains("\n")){toast("请填写用户名及至少 8 位的密码");return;}
        getPreferences(0).edit().putString("user",u).putString("port",Integer.toString(n)).apply();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);
        Intent i=new Intent(this,FtpService.class).putExtra("folder",folder.toString()).putExtra("user",u).putExtra("password",p).putExtra("port",n);
        try{startForegroundService(i);toast("正在启动；当前界面中的账号密码用于本次共享");}catch(RuntimeException e){toast("无法启动，请保持应用在前台后重试");}
    }
    private void update(){boolean active=FtpService.running;state.setText(FtpService.status);start.setEnabled(!active&&!FtpService.status.startsWith("正在"));choose.setEnabled(!active);user.setEnabled(!active);password.setEnabled(!active);port.setEnabled(!active);
        if(active){address.setText(FtpService.addresses(FtpService.activePort));}else address.setText(R.string.address_pending);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("password",password.getText().toString());super.onSaveInstanceState(out);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_FOLDER&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri selected=data.getData();
        try{getContentResolver().takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION);Uri old=folder;folder=selected;getPreferences(0).edit().putString("folder",folder.toString()).apply();folderView.setText(Uri.decode(folder.toString()));if(old!=null&&!old.equals(selected))try{getContentResolver().releasePersistableUriPermission(old,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
        }catch(SecurityException e){toast("无法保留授权，请重新选择手机本地文件夹");}}}
}
