package com.lanftp;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class ServerHarness {
 public static void main(String[] args)throws Exception {
  Path root=Paths.get(args[0]).toRealPath();
  FtpServer.Vfs fs=new FtpServer.Vfs(){
   Path resolve(String p)throws IOException {Path f=root.resolve(p.substring(1)).normalize().toRealPath();if(!f.startsWith(root))throw new IOException("outside root");return f;}
   FtpServer.Entry entry(Path p)throws IOException{return new FtpServer.Entry(p.getFileName().toString(),Files.isDirectory(p),Files.isDirectory(p)?0:Files.size(p),Files.getLastModifiedTime(p).toMillis());}
   public FtpServer.Entry stat(String p)throws IOException{return entry(resolve(p));}
   public List<FtpServer.Entry> list(String p)throws IOException{List<FtpServer.Entry> r=new ArrayList<>();try(DirectoryStream<Path> s=Files.newDirectoryStream(resolve(p))){for(Path f:s)r.add(entry(f));}return r;}
   public InputStream open(String p)throws IOException{return Files.newInputStream(resolve(p));}
  };
  FtpServer s=new FtpServer(fs,"tester","test-password",0);s.start();System.out.println(s.port());System.out.flush();System.in.read();s.close();
 }
}
