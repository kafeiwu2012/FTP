package com.lanftp;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded NDR32 srvsvc endpoint: read-only share enumeration, not an RPC command gateway. */
final class RpcPipe {
  private static ByteBuffer b(byte[] v) {
    return ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static byte[] hex(String s) {
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++)
      out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
    return out;
  }

  private static final byte[] NDR = hex("045d888aeb1cc9119fe808002b10486002000000");
  private static final byte[] SRVS = hex("c84f324b7016d30112785a47bf6ee18803000000");
  private static final String[] ENUM = {
    "00000000000000005d3b0000010000004ad10000010000005f650000060000000000000006000000730068006100720065000000010000000000000000000000",
    "01000000010000008aa1000001000000bef800000100000061c000000000000063e4000006000000000000000600000073006800610072006500000012000000000000001200000041006e00640072006f006900640020004c0041004e002000530068006100720065000000010000000000000000000000",
    "02000000020000003ae9000001000000b96b000001000000de4b0000000000000999000000000000ffffffff000000002cc10000785e000006000000000000000600000073006800610072006500000012000000000000001200000041006e00640072006f006900640020004c0041004e00200053006800610072006500000009000000000000000900000043003a005c00730068006100720065000000abab0100000000000000010000000000bfbf010000000000000000000000"
  };
  private static final String[] INFO = {
    "000000000f0900005f65000006000000000000000600000073006800610072006500000000000000",
    "01000000b701000061c000000000000063e4000006000000000000000600000073006800610072006500000012000000000000001200000041006e00640072006f006900640020004c0041004e00200053006800610072006500000000000000",
    "02000000b7160000de4b0000000000000999000000000000ffffffff000000002cc10000785e000006000000000000000600000073006800610072006500000012000000000000001200000041006e00640072006f006900640020004c0041004e00200053006800610072006500000009000000000000000900000043003a005c00730068006100720065000000abab0100000000000000010000000000bfbf00000000"
  };

  static byte[] exchange(byte[] request) throws IOException {
    if (request.length < 16
        || request[0] != 5
        || request[1] != 0
        || (request[3] & 3) != 3
        || request[4] != 0x10
        || (b(request).getShort(8) & 65535) != request.length
        || b(request).getShort(10) != 0) throw new IOException("Unsupported RPC framing");
    int type = request[2] & 255, call = b(request).getInt(12);
    byte[] body;
    if (type == 11) {
      if (request.length < 28) throw new IOException("Invalid bind");
      int count = request[24] & 255, pos = 28;
      if (count < 1 || count > 16) throw new IOException("Invalid context count");
      ByteArrayOutputStream results = new ByteArrayOutputStream();
      for (int i = 0; i < count; i++) {
        if (pos + 24 > request.length) throw new IOException("Short bind context");
        int transfers = request[pos + 2] & 255;
        boolean accepted = false;
        boolean service = Arrays.equals(Arrays.copyOfRange(request, pos + 4, pos + 24), SRVS);
        pos += 24;
        for (int j = 0; j < transfers; j++) {
          if (pos + 20 > request.length) throw new IOException("Short transfer syntax");
          if (service && Arrays.equals(Arrays.copyOfRange(request, pos, pos + 20), NDR))
            accepted = true;
          pos += 20;
        }
        byte[] result =
            ByteBuffer.allocate(24)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) (accepted ? 0 : 2))
                .putShort((short) (accepted ? 0 : 2))
                .put(accepted ? NDR : new byte[20])
                .array();
        results.write(result);
      }
      byte[] address = "\\PIPE\\srvsvc\0".getBytes(StandardCharsets.US_ASCII);
      int fixed = (10 + address.length + 3) & ~3;
      ByteBuffer prefix = ByteBuffer.allocate(fixed + 4).order(ByteOrder.LITTLE_ENDIAN);
      prefix
          .putShort((short) 4280)
          .putShort((short) 4280)
          .putInt(1)
          .putShort((short) address.length)
          .put(address);
      prefix.position(fixed);
      prefix.put((byte) count).put((byte) 0).putShort((short) 0);
      body = Ntlm.join(prefix.array(), results.toByteArray());
      return pdu(12, call, body);
    }
    if (type != 0 || request.length < 24) throw new IOException("Unsupported RPC PDU");
    int context = b(request).getShort(20) & 65535, op = b(request).getShort(22) & 65535;
    byte[] stub = Arrays.copyOfRange(request, 24, request.length), data;
    try {
      int off = uniqueStringEnd(stub, 0);
      if (op == 15) {
        int level = b(stub).getInt(off);
        if (level < 0 || level > 2) throw new IOException("Unsupported share information level");
        data = hex(ENUM[level]);
      } else if (op == 16) {
        int chars = b(stub).getInt(off + 8);
        if (chars < 1 || chars > 1024 || off + 12 + 2 * chars > stub.length)
          throw new IOException("Invalid share name");
        String name = new String(stub, off + 12, 2 * (chars - 1), StandardCharsets.UTF_16LE);
        if (!name.equalsIgnoreCase("share")) throw new IOException("Unknown share");
        int at = (off + 12 + 2 * chars + 3) & ~3;
        int level = b(stub).getInt(at);
        if (level < 0 || level > 2) throw new IOException("Unsupported level");
        data = hex(INFO[level]);
      } else return fault(call, context, 0x1c010002);
    } catch (RuntimeException | IOException e) {
      return fault(call, context, 0x1c010003);
    }
    return pdu(
        2,
        call,
        Ntlm.join(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(data.length)
                .putShort((short) context)
                .putShort((short) 0)
                .array(),
            data));
  }

  private static int uniqueStringEnd(byte[] stub, int off) throws IOException {
    if (off + 4 > stub.length) throw new IOException("Short NDR");
    if (b(stub).getInt(off) == 0) return off + 4;
    if (off + 16 > stub.length) throw new IOException("Short string");
    int n = b(stub).getInt(off + 12);
    if (n < 0 || n > 1024 || off + 16 + n * 2 > stub.length)
      throw new IOException("Invalid NDR string");
    return (off + 16 + n * 2 + 3) & ~3;
  }

  private static byte[] fault(int call, int context, int status) {
    return pdu(
        3,
        call,
        ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .putShort((short) context)
            .putShort((short) 0)
            .putInt(status)
            .putInt(0)
            .array());
  }

  private static byte[] pdu(int type, int call, byte[] body) {
    return Ntlm.join(
        ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 5)
            .put((byte) 0)
            .put((byte) type)
            .put((byte) 3)
            .putInt(0x10)
            .putShort((short) (16 + body.length))
            .putShort((short) 0)
            .putInt(call)
            .array(),
        body);
  }
}
