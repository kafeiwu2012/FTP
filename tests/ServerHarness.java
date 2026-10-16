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
    SmbServer smb = new SmbServer(fs, "tester", "test-password", 0, policy);
    smb.start();
    WebServer web = new WebServer(fs, "tester", "test-password", 0, policy);
    web.start();
    if (args.length > 4) {
      System.out.println(ftp.port() + " " + smb.port() + " " + web.port());
    } else System.out.println(ftp.port() + " " + smb.port());
    System.out.flush();
    int command;
    while ((command = System.in.read()) >= 0) {
      if (command == 'd') {
        System.out.println(
            java.util.Base64.getEncoder()
                .encodeToString(
                    smb.diagnostics().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        System.out.flush();
      } else if (command == '\n') break;
    }
    ftp.close();
    smb.close();
    web.close();
  }
}
