package com.lanftp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** NTLMv2 authentication and SMB 2 signing; never enables NTLMv1 or LM authentication. */
final class Ntlm {
  private static final byte[] MAGIC = {'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};
  static final byte[] OID_NTLM = {
    0x06, 0x0a, 0x2b, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0a
  };
  private static final byte[] OID_SPNEGO = {0x06, 0x06, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x02};
  private final byte[] challenge = new byte[8];

  Ntlm() {
    new SecureRandom().nextBytes(challenge);
  }

  static byte[] join(byte[]... parts) {
    int n = 0;
    for (byte[] p : parts) n += p.length;
    byte[] out = new byte[n];
    int pos = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, pos, p.length);
      pos += p.length;
    }
    return out;
  }

  static byte[] der(int tag, byte[] bytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    if (bytes.length < 128) out.write(bytes.length);
    else if (bytes.length < 256) {
      out.write(0x81);
      out.write(bytes.length);
    } else {
      out.write(0x82);
      out.write(bytes.length >> 8);
      out.write(bytes.length);
    }
    out.write(bytes, 0, bytes.length);
    return out.toByteArray();
  }

  static byte[] initialToken() {
    return der(0x60, join(OID_SPNEGO, der(0xa0, der(0x30, der(0xa0, der(0x30, OID_NTLM))))));
  }

  static byte[] responseToken(byte[] token, boolean complete) {
    return der(
        0xa1,
        der(
            0x30,
            complete
                ? der(0xa0, der(0x0a, new byte[] {0}))
                : join(
                    der(0xa0, der(0x0a, new byte[] {1})),
                    der(0xa1, OID_NTLM),
                    der(0xa2, der(0x04, token)))));
  }

  static byte[] raw(byte[] token) throws IOException {
    // SPNEGO has bounded framing; locate the embedded NTLMSSP mechanism token.
    for (int i = 0; i <= token.length - 12; i++) {
      boolean match = true;
      for (int j = 0; j < 8; j++)
        if (token[i + j] != MAGIC[j]) {
          match = false;
          break;
        }
      if (match) return Arrays.copyOfRange(token, i, token.length);
    }
    throw new IOException("NTLM mechanism required");
  }

  static int type(byte[] raw) throws IOException {
    if (raw.length < 12) throw new IOException("Invalid NTLM token");
    return buffer(raw).getInt(8);
  }

  static ByteBuffer buffer(byte[] b) {
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
  }

  byte[] challengeToken() {
    byte[] name = "LANFTP".getBytes(StandardCharsets.UTF_16LE);
    ByteArrayOutputStream infoBytes = new ByteArrayOutputStream();
    String[] names = {"LANFTP", "LANFTP", "lanftp.local", "local"};
    for (int i = 0; i < names.length; i++) {
      byte[] value = names[i].getBytes(StandardCharsets.UTF_16LE);
      byte[] av =
          ByteBuffer.allocate(4 + value.length)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putShort((short) (i + 1))
              .putShort((short) value.length)
              .put(value)
              .array();
      infoBytes.write(av, 0, av.length);
    }
    byte[] time =
        ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort((short) 7)
            .putShort((short) 8)
            .putLong((System.currentTimeMillis() + 11644473600000L) * 10000)
            .array();
    infoBytes.write(time, 0, time.length);
    infoBytes.write(new byte[4], 0, 4);
    byte[] info = infoBytes.toByteArray();
    ByteBuffer out =
        ByteBuffer.allocate(56 + name.length + info.length).order(ByteOrder.LITTLE_ENDIAN);
    out.put(MAGIC).putInt(2);
    secbuf(out, name.length, 56);
    out.putInt(0xe28a8215);
    out.put(challenge).putLong(0);
    secbuf(out, info.length, 56 + name.length);
    out.put(new byte[] {6, 1, (byte) 0xb1, 0x1d, 0, 0, 0, 15});
    out.put(name).put(info);
    return out.array();
  }

  private static void secbuf(ByteBuffer b, int len, int offset) {
    b.putShort((short) len).putShort((short) len).putInt(offset);
  }

  static byte[] field(byte[] raw, int at) throws IOException {
    if (at < 0 || at + 8 > raw.length) throw new IOException("Invalid NTLM field");
    ByteBuffer b = buffer(raw);
    int len = b.getShort(at) & 65535, off = b.getInt(at + 4);
    if (off < 0 || off > raw.length || len > raw.length - off)
      throw new IOException("Invalid NTLM field");
    return Arrays.copyOfRange(raw, off, off + len);
  }

  static String username(byte[] raw) throws IOException {
    return new String(field(raw, 36), StandardCharsets.UTF_16LE);
  }

  byte[] authenticate(byte[] raw, String expectedUser, String password) throws IOException {
    if (type(raw) != 3 || raw.length < 64) throw new IOException("Invalid authenticate token");
    String user = username(raw), domain = new String(field(raw, 28), StandardCharsets.UTF_16LE);
    if (!user.equalsIgnoreCase(expectedUser)) throw new IOException("Invalid account");
    byte[] response = field(raw, 20);
    if (response.length < 48 || response[16] != 1 || response[17] != 1)
      throw new IOException("NTLMv2 required");
    byte[] key =
        hmac(
            "HmacMD5",
            md4(password.getBytes(StandardCharsets.UTF_16LE)),
            (user.toUpperCase(Locale.ROOT) + domain).getBytes(StandardCharsets.UTF_16LE));
    byte[] proof =
        hmac("HmacMD5", key, join(challenge, Arrays.copyOfRange(response, 16, response.length)));
    if (!MessageDigest.isEqual(proof, Arrays.copyOf(response, 16)))
      throw new IOException("Invalid password");
    byte[] session = hmac("HmacMD5", key, proof), encrypted = field(raw, 52);
    if ((buffer(raw).getInt(60) & 0x40000000) != 0) {
      if (encrypted.length != 16) throw new IOException("Invalid session key");
      session = rc4(session, encrypted);
    }
    return session;
  }

  static byte[] hmac(String algo, byte[] key, byte[] data) throws IOException {
    try {
      Mac m = Mac.getInstance(algo);
      m.init(new SecretKeySpec(key, algo));
      return m.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IOException("Crypto unavailable", e);
    }
  }

  private static byte[] rc4(byte[] key, byte[] input) {
    int[] s = new int[256];
    for (int i = 0; i < 256; i++) s[i] = i;
    int j = 0;
    for (int i = 0; i < 256; i++) {
      j = (j + s[i] + (key[i % key.length] & 255)) & 255;
      int t = s[i];
      s[i] = s[j];
      s[j] = t;
    }
    byte[] out = new byte[input.length];
    int i = 0;
    j = 0;
    for (int k = 0; k < input.length; k++) {
      i = (i + 1) & 255;
      j = (j + s[i]) & 255;
      int t = s[i];
      s[i] = s[j];
      s[j] = t;
      out[k] = (byte) (input[k] ^ s[(s[i] + s[j]) & 255]);
    }
    return out;
  }

  // RFC 1320 MD4 is only used for the NT password hash, as required by NTLMv2.
  static byte[] md4(byte[] input) {
    int size = ((input.length + 8) / 64 + 1) * 64;
    byte[] bytes = Arrays.copyOf(input, size);
    bytes[input.length] = (byte) 0x80;
    buffer(bytes).putLong(size - 8, (long) input.length * 8);
    int[] h = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476}, x = new int[16];
    int[] order2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15},
        order3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
    int[][] shifts = {{3, 7, 11, 19}, {3, 5, 9, 13}, {3, 9, 11, 15}};
    for (int pos = 0; pos < size; pos += 64) {
      for (int k = 0; k < 16; k++) x[k] = buffer(bytes).getInt(pos + k * 4);
      int a = h[0], b = h[1], c = h[2], d = h[3];
      for (int round = 0; round < 3; round++)
        for (int k = 0; k < 16; k++) {
          int f =
              round == 0
                  ? ((b & c) | (~b & d))
                  : round == 1 ? ((b & c) | (b & d) | (c & d)) : (b ^ c ^ d);
          int ix = round == 0 ? k : round == 1 ? order2[k] : order3[k];
          int n =
              Integer.rotateLeft(
                  a + f + x[ix] + (round == 0 ? 0 : round == 1 ? 0x5a827999 : 0x6ed9eba1),
                  shifts[round][k % 4]);
          a = d;
          d = c;
          c = b;
          b = n;
        }
      h[0] += a;
      h[1] += b;
      h[2] += c;
      h[3] += d;
    }
    ByteBuffer result = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    for (int n : h) result.putInt(n);
    return result.array();
  }
}
