#!/usr/bin/env python3
"""Browser API exercised against production streaming HTTP, FTP and SMB engines."""
import base64, contextlib, ftplib, http.client, io, json, pathlib, socket, subprocess, tempfile, unittest
from urllib.parse import urlencode
REPO=pathlib.Path(__file__).resolve().parents[1]
AUTH='Basic '+base64.b64encode(b'tester:test-password').decode()
@contextlib.contextmanager
def server(build,write=False,anonymous=False,anonymous_write=False):
 with tempfile.TemporaryDirectory() as d:
  root=pathlib.Path(d)/'share';root.mkdir();(root/'你好.txt').write_text('手机文件\n');(root/'empty.bin').write_bytes(b'')
  (root.parent/'secret').write_text('outside');(root/'escape').symlink_to(root.parent/'secret')
  p=subprocess.Popen(['java','-cp',str(build),'com.lanftp.ServerHarness',str(root),str(write).lower(),str(anonymous).lower(),str(anonymous_write).lower(),'web'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
  try:
   ftp,smb,port=map(int,p.stdout.readline().split());yield root,ftp,smb,port
  finally:
   p.stdin.write('\n');p.stdin.flush();p.wait(timeout=10);p.stdin.close();p.stdout.close()
def request(port,method='GET',route='/',path=None,body=None,auth=AUTH,headers=None,overwrite=False):
 if path is not None:route+='?'+urlencode({'path':path})+('&overwrite=1' if overwrite else '')
 h={'X-LanFTP-Request':'1'}
 if auth is not None:h['Authorization']=auth
 if headers:h.update(headers)
 c=http.client.HTTPConnection('127.0.0.1',port,timeout=10)
 try:
  c.request(method,route,body=body,headers=h);r=c.getresponse();return r.status,dict(r.getheaders()),r.read()
 finally:c.close()
class WebTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();cls.build=pathlib.Path(cls.temp.name)
  source=REPO/'app/src/main/java/com/lanftp'
  subprocess.run(['javac','-encoding','UTF-8','-d',str(cls.build),*[str(source/(n+'.java')) for n in ['AccessPolicy','FtpServer','Ntlm','RpcPipe','SmbServer','WebServer','WebUi']],str(REPO/'tests/LocalFileSystem.java'),str(REPO/'tests/ServerHarness.java')],check=True)
 @classmethod
 def tearDownClass(cls):cls.temp.cleanup()
 def test_authentication_required_and_wrong_password_not_guest(self):
  with server(self.build,anonymous=True) as (_,_,_,port):
   self.assertEqual(request(port,auth=None)[0],200)
   self.assertEqual(request(port,auth='Basic eDpiYWQ=')[0],401)
   self.assertEqual(request(port,route='/account',auth=None)[0],401)
  with server(self.build) as (_,_,_,port):
   status,headers,_=request(port,auth=None);self.assertEqual(status,401);self.assertIn('WWW-Authenticate',headers)
   self.assertEqual(request(port)[0],200)
 def test_unicode_listing_and_empty_download(self):
  with server(self.build) as (root,_,_,port):
   (root/'<img src=x onerror=alert(1)>.txt').write_text('safe')
   status,headers,data=request(port,route='/api/list',path='/');self.assertEqual(status,200)
   listing=json.loads(data);self.assertIn('你好.txt',[e['name'] for e in listing['entries']]);self.assertFalse(listing['writable'])
   self.assertEqual(request(port,route='/api/file',path='/empty.bin')[2],b'')
   _,headers,data=request(port,route='/api/file',path='/你好.txt');self.assertEqual(data,(root/'你好.txt').read_bytes());self.assertIn('filename*=UTF-8',headers['Content-Disposition'])
 def test_default_readonly_and_anonymous_policy(self):
  for write,anon,anon_write in [(False,False,False),(True,True,False),(True,True,True)]:
   with server(self.build,write,anon,anon_write) as (root,_,_,port):
    auth=None if anon else AUTH
    status,_,_=request(port,'PUT','/api/file','/new.txt',b'data',auth=auth)
    self.assertEqual(status,200 if anon_write else 403)
    self.assertEqual((root/'new.txt').exists(),anon_write)
    if write:self.assertEqual(request(port,'PUT','/api/file','/account.txt',b'data')[0],200)
 def test_upload_overwrite_download_and_ftp_crosscheck(self):
  with server(self.build,write=True) as (root,ftp,_,port):
   payload=bytes(range(256))*20000
   self.assertEqual(request(port,'PUT','/api/file','/上传.bin',payload)[0],200)
   self.assertEqual(request(port,'PUT','/api/file','/上传.bin',b'no')[0],409)
   self.assertEqual((root/'上传.bin').read_bytes(),payload)
   with ftplib.FTP() as f:
    f.connect('127.0.0.1',ftp);f.login('tester','test-password');out=io.BytesIO();f.retrbinary('RETR 上传.bin',out.write);self.assertEqual(out.getvalue(),payload)
   self.assertEqual(request(port,'PUT','/api/file','/上传.bin',b'replaced',overwrite=True)[0],200)
   self.assertEqual(request(port,route='/api/file',path='/上传.bin')[2],b'replaced')
 def test_range_and_head(self):
  with server(self.build) as (root,_,_,port):
   data=bytes(range(256))*10;(root/'range.bin').write_bytes(data)
   for value,expected in [('bytes=100-200',data[100:201]),('bytes=2000-',data[2000:]),('bytes=-30',data[-30:])]:
    status,h,body=request(port,route='/api/file',path='/range.bin',headers={'Range':value});self.assertEqual(status,206);self.assertEqual(body,expected);self.assertIn('Content-Range',h)
   self.assertEqual(request(port,route='/api/file',path='/range.bin',headers={'Range':'bytes=999999-'})[0],416)
   self.assertEqual(request(port,'HEAD','/api/file','/range.bin')[2],b'')
 def test_chunked_upload(self):
  with server(self.build,write=True) as (root,_,_,port):
   c=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
   try:
    c.request('PUT','/api/file?path=%2Fchunk.bin',body=iter([b'first',bytes(range(256))*100,b'last']),headers={'Authorization':AUTH,'X-LanFTP-Request':'1'},encode_chunked=True)
    r=c.getresponse();self.assertEqual(r.status,200,r.read());r.read();self.assertEqual((root/'chunk.bin').read_bytes(),b'first'+bytes(range(256))*100+b'last')
   finally:c.close()
 def test_mutations_root_empty_directory_and_csrf(self):
  with server(self.build,write=True) as (root,_,_,port):
   self.assertEqual(request(port,'POST','/api/mkdir','/新目录')[0],200)
   self.assertEqual(request(port,'PUT','/api/file','/新目录/file',b'data')[0],200)
   self.assertEqual(request(port,'DELETE','/api/file','/新目录')[0],409)
   self.assertEqual(request(port,'DELETE','/api/file','/新目录/file')[0],200)
   self.assertEqual(request(port,'DELETE','/api/file','/新目录')[0],200)
   for method,route in [('DELETE','/api/file'),('POST','/api/mkdir'),('PUT','/api/file')]:self.assertEqual(request(port,method,route,'/',b'')[0],403)
   for headers in [{'Origin':'http://evil.example'},{'Sec-Fetch-Site':'cross-site'},{'X-LanFTP-Request':'0'}]:
    self.assertEqual(request(port,'PUT','/api/file','/attack',b'bad',headers=headers)[0],403)
   self.assertFalse((root/'attack').exists())
 def test_outside_tree_rejected(self):
  with server(self.build,write=True) as (root,_,_,port):
   for path in ['/../../secret','/escape']:
    self.assertNotEqual(request(port,route='/api/file',path=path)[0],200)
    self.assertNotEqual(request(port,'PUT','/api/file',path,b'bad',overwrite=True)[0],200)
   self.assertEqual((root.parent/'secret').read_text(),'outside')
 def test_ambiguous_http_framing_rejected_without_mutation(self):
  with server(self.build,write=True) as (root,_,_,port):
   for framing in ['Content-Length: 1\r\nContent-Length: 2','Content-Length: 1\r\nTransfer-Encoding: chunked']:
    with socket.create_connection(('127.0.0.1',port),timeout=5) as s:
     s.sendall(('PUT /api/file?path=/bad HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: '+AUTH+'\r\nX-LanFTP-Request: 1\r\n'+framing+'\r\n\r\nx').encode());self.assertIn(b'400',s.recv(4096).split(b'\r\n')[0])
   self.assertFalse((root/'bad').exists())
if __name__=='__main__':unittest.main(verbosity=2)
