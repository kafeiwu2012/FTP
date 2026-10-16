package com.lanftp;

/** One immutable access policy shared by FTP and SMB for the entire service session. */
public final class AccessPolicy {
  public static final AccessPolicy READ_ONLY = new AccessPolicy(false, false, false);
  public final boolean writable, anonymous, anonymousWritable;

  public AccessPolicy(boolean writable, boolean anonymous, boolean anonymousWritable) {
    this.writable = writable;
    this.anonymous = anonymous;
    this.anonymousWritable = writable && anonymous && anonymousWritable;
  }

  public boolean canWrite(boolean guest) {
    return writable && (!guest || anonymousWritable);
  }

  public static boolean isGuest(String name) {
    return name.isEmpty() || name.equalsIgnoreCase("anonymous") || name.equalsIgnoreCase("guest");
  }
}
