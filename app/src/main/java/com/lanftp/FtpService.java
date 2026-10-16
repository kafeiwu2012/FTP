package com.lanftp;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import java.net.*;
import java.util.*;

public final class FtpService extends Service {
    public static volatile boolean running=false;
    public static volatile String activeUser="", activePassword="";
    public static volatile int activePort=2121;
    public static volatile String status="服务器已停止";
    private FtpServer server;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean destroyed;
    private final Object lock=new Object();
    public static String addresses(int port){
        List<String> addresses=new ArrayList<>();
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()){NetworkInterface n=interfaces.nextElement();if(!n.isUp()||n.isLoopback())continue;
                Enumeration<InetAddress> ips=n.getInetAddresses();while(ips.hasMoreElements()){InetAddress ip=ips.nextElement();if(ip instanceof Inet4Address && ip.isSiteLocalAddress())addresses.add("ftp://"+ip.getHostAddress()+":"+port);}}
        }catch(Exception ignored){}return addresses.isEmpty()?"未发现局域网 IPv4 地址，请连接 Wi-Fi 或开启热点":android.text.TextUtils.join("\n",addresses);
    }
    @Override public void onCreate(){super.onCreate();NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("ftp","FTP 文件共享",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,FtpService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"ftp").setContentTitle("局域网 FTP · 只读共享").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(open).addAction(new Notification.Action.Builder(null,"停止共享",stop).build()).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(Build.VERSION.SDK_INT>=29)startForeground(1,notification("正在启动…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(1,notification("正在启动…"));
        synchronized(lock){if(server!=null)return START_NOT_STICKY;}
        status="正在启动…";
        String folder=intent.getStringExtra("folder"),user=intent.getStringExtra("user"),password=intent.getStringExtra("password");int port=intent.getIntExtra("port",2121);
        new Thread(()->{
            try{
                if(folder==null||user==null||password==null||password.length()<8)throw new IllegalArgumentException("请填写目录及账号密码");
                SafFileSystem fs=new SafFileSystem(getContentResolver(),Uri.parse(folder));if(!fs.stat("/").directory)throw new IllegalArgumentException("请选择文件夹");
                synchronized(lock){
                    if(destroyed)return;
                    server=new FtpServer(fs,user,password,port);
                    wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lanftp:sharing");wake.acquire(6*60*60*1000L);
                    WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                    if(wm!=null){wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"lanftp:wifi");wifi.acquire();}
                    activeUser=user;activePassword=password;activePort=port;server.start();running=true;status="运行中 · 只读共享";
                    getSystemService(NotificationManager.class).notify(1,notification("端口 "+port+" · 点击查看连接地址"));
                }
            }catch(Exception e){status="启动失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());stopSelf();}
        },"ftp-start").start();
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId,int fgsType){status="系统已停止长时间共享，请重新启动";stopSelf();}
    @Override public void onDestroy(){
        synchronized(lock){destroyed=true;if(server!=null){server.close();server=null;}if(wake!=null&&wake.isHeld())wake.release();if(wifi!=null&&wifi.isHeld())wifi.release();running=false;activePassword="";activeUser="";}
        if(status.startsWith("运行")||status.startsWith("正在"))status="服务器已停止";
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
