package com.lanftp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Read-only FTP engine. No filesystem access exists outside the supplied Vfs. */
public final class FtpServer implements Closeable {
    public static final class Entry {
        public final String name; public final boolean directory; public final long size, modified;
        public Entry(String n, boolean d, long s, long m) { name=n; directory=d; size=s; modified=m; }
    }
    public interface Vfs {
        Entry stat(String path) throws IOException;
        List<Entry> list(String path) throws IOException;
        InputStream open(String path) throws IOException;
    }
    private final Vfs fs;
    private final String user, password;
    private final ServerSocket listener;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Set<ServerSocket> passiveListeners = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = new ThreadPoolExecutor(0,8,60,TimeUnit.SECONDS,new SynchronousQueue<Runnable>());
    private volatile boolean running=true;
    private Thread acceptor;
    public FtpServer(Vfs fs, String user, String password, int port) throws IOException {
        this.fs=fs; this.user=user; this.password=password;
        listener=new ServerSocket(); listener.setReuseAddress(true); listener.bind(new InetSocketAddress(port));
    }
    public int port() { return listener.getLocalPort(); }
    public void start() {
        acceptor=new Thread(() -> {
            while(running) try {
                Socket s=listener.accept(); s.setSoTimeout(120000); clients.add(s);
                try { workers.execute(() -> { try { new Session(s).run(); } finally { clients.remove(s); try{s.close();}catch(IOException ignored){} } }); }
                catch(RejectedExecutionException e){ clients.remove(s); s.close(); }
            } catch(IOException e) { if(running) close(); }
        },"ftp-accept"); acceptor.start();
    }
    @Override public void close() {
        running=false; try{listener.close();}catch(IOException ignored){}
        for(ServerSocket s:passiveListeners) try{s.close();}catch(IOException ignored){}
        for(Socket s:clients) try{s.close();}catch(IOException ignored){}
        workers.shutdownNow();
    }
    static String normalize(String cwd, String path) throws IOException {
        if(path.indexOf('\0')>=0 || path.indexOf('\r')>=0 || path.indexOf('\n')>=0 || path.indexOf('\\')>=0) throw new IOException("Invalid path");
        Deque<String> parts=new ArrayDeque<>();
        for(String p:((path.startsWith("/")?"":cwd+"/")+path).split("/")) {
            if(p.isEmpty() || p.equals("."))continue;
            if(p.equals("..")){ if(parts.isEmpty())throw new IOException("Outside shared folder"); parts.removeLast(); }
            else parts.addLast(p);
        }
        return "/"+String.join("/",parts);
    }
    private static boolean equal(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private final class Session {
        final Socket control; final BufferedReader in; final BufferedWriter out;
        ServerSocket passive; InetSocketAddress active; Socket data;
        int failures=0; boolean authenticated=false, userAccepted=false; String cwd="/"; long offset=0;
        Session(Socket s) {
            control=s;
            try { in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8)); out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8)); }
            catch(IOException e){throw new UncheckedIOException(e);}
        }
        void reply(int code,String text)throws IOException{out.write(code+" "+text+"\r\n");out.flush();}
        String line()throws IOException{
            StringBuilder b=new StringBuilder(); int c;
            while((c=in.read())!=-1){if(c=='\n')return b.toString().replace("\r",""); if(b.length()>=8192)throw new IOException("Command too long"); b.append((char)c);}
            return null;
        }
        void resetData(){if(passive!=null){passiveListeners.remove(passive);try{passive.close();}catch(IOException ignored){} passive=null;} active=null;}
        void run(){try{
            reply(220,"LAN FTP ready (read only)"); String l;
            while(running && (l=line())!=null){int space=l.indexOf(' '); String cmd=(space<0?l:l.substring(0,space)).toUpperCase(Locale.ROOT);String arg=space<0?"":l.substring(space+1);
                try{
                    if(cmd.equals("QUIT")){reply(221,"Goodbye");break;}
                    if(cmd.equals("USER")){authenticated=false; userAccepted=equal(arg,user); reply(331,"Password required");continue;}
                    if(cmd.equals("PASS")){authenticated=userAccepted && equal(arg,password);reply(authenticated?230:530,authenticated?"Logged in":"Authentication failed");if(!authenticated && ++failures>=5)return;continue;}
                    if(cmd.equals("FEAT")){out.write("211-Features\r\n UTF8\r\n EPSV\r\n PASV\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n MLST type*;size*;modify*;\r\n211 End\r\n");out.flush();continue;}
                    if(cmd.equals("OPTS") && arg.equalsIgnoreCase("UTF8 ON")){reply(200,"UTF8 enabled");continue;}
                    if(cmd.equals("SYST")){reply(215,"UNIX Type: L8");continue;}
                    if(cmd.equals("NOOP")){reply(200,"OK");continue;}
                    if(!authenticated){reply(530,"Please log in");continue;}
                    switch(cmd){
                    case "PWD": case "XPWD": reply(257,"\""+cwd.replace("\"","\"\"")+"\" is current directory");break;
                    case "CWD": case "XCWD": case "CDUP": {
                        String path=normalize(cwd,cmd.equals("CDUP")?"..":arg); if(!fs.stat(path).directory)throw new IOException("Not a folder");cwd=path;reply(250,"Directory changed");break;}
                    case "TYPE": if(arg.equalsIgnoreCase("I")||arg.equalsIgnoreCase("A"))reply(200,"Type set");else reply(504,"Unsupported type");break;
                    case "MODE": case "STRU":reply(arg.equalsIgnoreCase(cmd.equals("MODE")?"S":"F")?200:504,"Stream/file only");break;
                    case "PASV": case "EPSV": {
                        resetData(); passive=new ServerSocket(); passive.bind(new InetSocketAddress(control.getLocalAddress(),0));passive.setSoTimeout(15000);passiveListeners.add(passive);
                        int p=passive.getLocalPort();byte[] a=control.getLocalAddress().getAddress();
                        if(cmd.equals("EPSV"))reply(229,"Entering Extended Passive Mode (|||"+p+"|)");
                        else if(a.length==4)reply(227,"Entering Passive Mode ("+(a[0]&255)+","+(a[1]&255)+","+(a[2]&255)+","+(a[3]&255)+","+(p/256)+","+(p%256)+")");
                        else {resetData();reply(522,"Use EPSV for IPv6");}break;}
                    case "PORT": {
                        resetData();String[] a=arg.split(",");if(a.length!=6)throw new IOException("Invalid PORT");int[] n=new int[6];for(int i=0;i<6;i++){n[i]=Integer.parseInt(a[i]);if(n[i]<0||n[i]>255)throw new IOException("Invalid PORT");}
                        InetAddress host=InetAddress.getByName(n[0]+"."+n[1]+"."+n[2]+"."+n[3]);int p=n[4]*256+n[5];
                        if(!host.equals(control.getInetAddress())||p<1024)throw new IOException("Unsafe PORT");active=new InetSocketAddress(host,p);reply(200,"Active data address accepted");break;}
                    case "REST": {long n=Long.parseLong(arg);if(n<0)throw new IOException("Invalid offset");offset=n;reply(350,"Restart offset set");break;}
                    case "ABOR": resetData();offset=0;reply(226,"Abort complete");break;
                    case "CLNT": reply(200,"OK");break;
                    case "MLST": {String path=normalize(cwd,arg);Entry e=fs.stat(path);out.write("250-File information\r\n type="+(e.directory?"dir":"file")+";size="+e.size+";modify="+timestamp(e.modified)+"; "+path+"\r\n250 End\r\n");out.flush();break;}
                    case "SIZE": {Entry e=fs.stat(normalize(cwd,arg));if(e.directory)throw new IOException("Not a file");reply(213,Long.toString(e.size));break;}
                    case "MDTM":reply(213,timestamp(fs.stat(normalize(cwd,arg)).modified));break;
                    case "LIST": case "NLST": case "MLSD": {
                        String path=normalize(cwd,(arg.equals("-a")||arg.equals("-l")||arg.equals("-la"))?"":arg);
                        Entry e=fs.stat(path); List<Entry> entries=e.directory?fs.list(path):Collections.singletonList(e);
                        transfer(() -> {BufferedWriter w=new BufferedWriter(new OutputStreamWriter(data.getOutputStream(),StandardCharsets.UTF_8));
                            for(Entry item:entries){String name=item.name.replace('\r','_').replace('\n','_');
                                if(cmd.equals("NLST"))w.write(name+"\r\n");
                                else if(cmd.equals("MLSD"))w.write("type="+(item.directory?"dir":"file")+";size="+item.size+";modify="+timestamp(item.modified)+"; "+name+"\r\n");
                                else {SimpleDateFormat f=new SimpleDateFormat("MMM dd HH:mm",Locale.US);w.write((item.directory?"dr-xr-xr-x":"-r--r--r--")+" 1 ftp ftp "+item.size+" "+f.format(new Date(item.modified))+" "+name+"\r\n");}
                            }w.flush();});break;}
                    case "RETR": {
                        String path=normalize(cwd,arg);if(fs.stat(path).directory)throw new IOException("Not a file");final long skip=offset;offset=0;
                        try(InputStream source=fs.open(path)){transfer(() -> {long remaining=skip;while(remaining>0){long n=source.skip(remaining);if(n<=0){if(source.read()<0)throw new EOFException("Offset exceeds file size");n=1;}remaining-=n;}
                            byte[] b=new byte[65536];int n;OutputStream dest=data.getOutputStream();while(running&&(n=source.read(b))!=-1)dest.write(b,0,n);dest.flush();});}break;}
                    case "STOR":case "APPE":case "DELE":case "RMD":case "MKD":case "RNFR":case "RNTO":case "SITE":reply(550,"Read-only server");break;
                    default:reply(502,"Command not implemented");
                    }
                }catch(IOException|IllegalArgumentException|SecurityException e){reply(550,"Operation unavailable; check path and folder permission");}
            }
        }catch(IOException ignored){}finally{resetData();if(data!=null)try{data.close();}catch(IOException ignored){}}}
        void transfer(IOAction action)throws IOException {
            if(passive==null&&active==null){reply(425,"Use PASV, EPSV or PORT first");return;}
            reply(150,"Opening data connection");
            try {
                if(passive!=null){data=passive.accept();if(!data.getInetAddress().equals(control.getInetAddress()))throw new IOException("Wrong data peer");}
                else{data=new Socket();data.connect(active,15000);}data.setSoTimeout(30000);clients.add(data);
                action.run();data.close();reply(226,"Transfer complete");
            } catch(IOException|SecurityException e){reply(426,"Transfer failed");}
            finally{if(data!=null){clients.remove(data);try{data.close();}catch(IOException ignored){}data=null;}resetData();}
        }
    }
    private interface IOAction{void run()throws IOException;}
    private static String timestamp(long t){SimpleDateFormat f=new SimpleDateFormat("yyyyMMddHHmmss",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(t));}
}
