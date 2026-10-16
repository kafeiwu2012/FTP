#!/usr/bin/env python3
"""Functional tests against the production FTP engine, using a temporary filesystem."""
import ftplib, io, os, pathlib, subprocess, tempfile, unittest

class FtpTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();cls.root=pathlib.Path(cls.temp.name)
  (cls.root/'子目录').mkdir();(cls.root/'子目录'/'你好.txt').write_bytes('手机里的文件\n'.encode())
  cls.payload=os.urandom(1024*1024+37);(cls.root/'binary.bin').write_bytes(cls.payload)
  (cls.root/'empty.txt').write_bytes(b'')
  build=cls.root/'classes';build.mkdir()
  repo=pathlib.Path(__file__).resolve().parents[1]
  subprocess.run(['javac','-encoding','UTF-8','-d',str(build),str(repo/'app/src/main/java/com/lanftp/FtpServer.java'),str(repo/'tests/ServerHarness.java')],check=True)
  cls.proc=subprocess.Popen(['java','-cp',str(build),'com.lanftp.ServerHarness',str(cls.root)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
  cls.port=int(cls.proc.stdout.readline())
 @classmethod
 def tearDownClass(cls):
  cls.proc.stdin.write('\n');cls.proc.stdin.flush();cls.proc.wait(timeout=10);cls.proc.stdin.close();cls.proc.stdout.close();cls.temp.cleanup()
 def setUp(self):
  self.ftp=ftplib.FTP();self.ftp.connect('127.0.0.1',self.port,timeout=10);self.ftp.login('tester','test-password')
 def tearDown(self):
  self.ftp.close()
 def test_login_rejected(self):
  with self.assertRaises(ftplib.error_perm):self.ftp.login('tester','wrong-password')
  with self.assertRaises(ftplib.error_perm):self.ftp.nlst()
 def test_listing_unicode_and_cwd(self):
  self.assertIn('子目录',self.ftp.nlst());self.ftp.cwd('子目录');self.assertEqual(self.ftp.pwd(),'/子目录')
  out=io.BytesIO();self.ftp.retrbinary('RETR 你好.txt',out.write);self.assertEqual(out.getvalue(),'手机里的文件\n'.encode())
  self.ftp.cwd('..');self.assertEqual(self.ftp.pwd(),'/')
 def test_binary_and_resume(self):
  for offset in [0,12345,len(self.payload)]:
   out=io.BytesIO();self.ftp.retrbinary('RETR binary.bin',out.write,rest=offset);self.assertEqual(out.getvalue(),self.payload[offset:])
 def test_active_download(self):
  self.ftp.set_pasv(False);out=io.BytesIO();self.ftp.retrbinary('RETR binary.bin',out.write);self.assertEqual(out.getvalue(),self.payload)
 def test_epsv(self):
  self.ftp.af=__import__('socket').AF_INET6
  out=io.BytesIO();self.ftp.retrbinary('RETR binary.bin',out.write);self.assertEqual(out.getvalue(),self.payload)
 def test_mlsd_size_mdtm(self):
  entries=dict(self.ftp.mlsd());self.assertEqual(entries['子目录']['type'],'dir');self.assertEqual(int(entries['binary.bin']['size']),len(self.payload))
  self.assertIn('type=file;',self.ftp.sendcmd('MLST binary.bin'))
  self.ftp.voidcmd('TYPE I');self.assertEqual(self.ftp.size('binary.bin'),len(self.payload));self.assertRegex(self.ftp.sendcmd('MDTM binary.bin'),r'^213 \d{14}$')
 def test_empty(self):
  out=io.BytesIO();self.ftp.retrbinary('RETR empty.txt',out.write);self.assertEqual(out.getvalue(),b'')
 def test_traversal(self):
  for path in ['..','/../etc','../../','/子目录/../../','\\etc']:
   with self.assertRaises(ftplib.error_perm):self.ftp.cwd(path)
  self.assertEqual(self.ftp.pwd(),'/')
 def test_writes_denied(self):
  for command in ['STOR x','APPE x','DELE binary.bin','MKD x','RMD 子目录','RNFR binary.bin','RNTO y','SITE CHMOD 777 binary.bin']:
   with self.assertRaises(ftplib.error_perm):self.ftp.sendcmd(command)
  self.assertEqual((self.root/'binary.bin').read_bytes(),self.payload)
 def test_port_bounce_denied(self):
  for command in ['PORT 127,0,0,2,10,10','PORT 127,0,0,1,0,22']:
   with self.assertRaises(ftplib.error_perm):self.ftp.sendcmd(command)
 def test_missing_file(self):
  with self.assertRaises(ftplib.error_perm):self.ftp.retrbinary('RETR missing',lambda b:None)
  self.assertEqual(self.ftp.voidcmd('NOOP'),'200 OK')
 def test_parallel(self):
  from concurrent.futures import ThreadPoolExecutor
  def download(_):
   with ftplib.FTP() as f:
    f.connect('127.0.0.1',self.port,timeout=10);f.login('tester','test-password');out=io.BytesIO();f.retrbinary('RETR binary.bin',out.write);return out.getvalue()
  with ThreadPoolExecutor(max_workers=4) as pool:
   for value in pool.map(download,range(4)):self.assertEqual(value,self.payload)

if __name__=='__main__':unittest.main(verbosity=2)
