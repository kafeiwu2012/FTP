package com.lanftp;

import java.io.*;
import java.nio.file.*;

public class ServerHarness {
  public static void main(String[] args) throws Exception {
    FtpServer.Vfs fs = new LocalFileSystem(Paths.get(args[0]));
    AccessPolicy policy =
        new AccessPolicy(
            args.length > 1 && Boolean.parseBoolean(args[1]),
            args.length > 2 && Boolean.parseBoolean(args[2]),
            args.length > 3 && Boolean.parseBoolean(args[3]));
    FtpServer ftp = new FtpServer(fs, "tester", "test-password", 0, policy);
    ftp.start();
    WebServer web = new WebServer(fs, "tester", "test-password", 0, policy);
    web.start();
    System.out.println(ftp.port() + " " + web.port());
    System.out.flush();
    System.in.read();
    ftp.close();
    web.close();
  }
}
