#!/usr/bin/env python3
"""Real FTP + two independent SMB client stacks against the production Java engines."""
import contextlib, ftplib, io, os, pathlib, socket, struct, subprocess, tempfile, unittest
import smbclient
from impacket.smbconnection import SMBConnection, SessionError
from smbprotocol.exceptions import SMBResponseException, SMBAuthenticationError
from smbprotocol.tree import SMB2TreeConnectRequest

REPO=pathlib.Path(__file__).resolve().parents[1]
BUILD=None
@contextlib.contextmanager
def server(write=False,anonymous=False,anonymous_write=False):
 with tempfile.TemporaryDirectory() as d:
  root=pathlib.Path(d)/'share';root.mkdir();(root/'你好.txt').write_bytes('手机文件\n'.encode());(root/'binary.bin').write_bytes(bytes(range(256))*1024)
  (pathlib.Path(d)/'secret').write_text('outside share');(root/'escape').symlink_to(pathlib.Path(d)/'secret')
  args=['java','-cp',str(BUILD),'com.lanftp.ServerHarness',str(root),str(write).lower(),str(anonymous).lower(),str(anonymous_write).lower()]
  p=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
  try:
   ftp,port=map(int,p.stdout.readline().split());yield root,ftp,port
  finally:
   if p.poll() is None:p.stdin.write('\n');p.stdin.flush()
   p.wait(timeout=10);p.stdin.close();p.stdout.close()

def ftp_login(port,user='tester',password='test-password'):
 f=ftplib.FTP();f.connect('127.0.0.1',port,timeout=10)
 try:f.login(user,password);return f
 except:f.close();raise

def smb_login(port,user='tester',password='test-password'):
 c=SMBConnection('LANFTP','127.0.0.1',sess_port=port,timeout=10)
 try:c.login(user,password);return c
 except:c.close();raise

class SharingTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  global BUILD
  cls.build=tempfile.TemporaryDirectory();BUILD=pathlib.Path(cls.build.name)
  source=REPO/'app/src/main/java/com/lanftp'
  subprocess.run(['javac','-encoding','UTF-8','-d',str(BUILD),*[str(source/(n+'.java')) for n in ['AccessPolicy','FtpServer','Ntlm','RpcPipe','SmbServer']],str(REPO/'tests/LocalFileSystem.java'),str(REPO/'tests/ServerHarness.java')],check=True)
 @classmethod
 def tearDownClass(cls):cls.build.cleanup()
 def test_directory_formats_identity_and_end_error(self):
  with server() as (root,ftp,port):
   (root/'nested').mkdir();(root/'nested'/'你好.txt').write_text('nested')
   c=smb_login(port)
   try:
    tree=c.connectTree('share');raw=c.getSMBServer()
    for parent in ['', 'nested']:
     ids=[]
     for cls,prefix,id_offset in [(1,64,None),(2,68,None),(3,94,None),(12,12,None),(37,104,96),(38,80,72),(60,88,72),(63,114,72)]:
      handle=c.openFile(tree,parent,desiredAccess=0x120089,shareMode=7,creationOption=1,creationDisposition=1)
      try:
       data=raw.queryDirectory(tree,handle,searchString='你好.txt',informationClass=cls)
       length=struct.unpack_from('<I',data,8 if cls==12 else 60)[0]
       self.assertEqual(data[prefix:prefix+length].decode('utf-16le'),'你好.txt')
       if id_offset is not None:ids.append(struct.unpack_from('<Q',data,id_offset)[0])
       try:raw.queryDirectory(tree,handle,informationClass=cls);self.fail('Expected end of enumeration')
       except Exception as error:
        self.assertEqual(error.get_error_code(),0x80000006)
        body=error.get_error_packet()['Data'];self.assertGreaterEqual(len(body),9)
        self.assertEqual(struct.unpack_from('<HBBI',body),(9,0,0,0))
      finally:c.closeFile(tree,handle)
     file=c.openFile(tree,(parent+'/' if parent else '')+'你好.txt',desiredAccess=0x120089,shareMode=7)
     try:
      index=struct.unpack('<Q',raw.queryInfo(tree,file,fileInfoClass=6))[0]
      self.assertTrue(all(x==index for x in ids))
      identity=raw.queryInfo(tree,file,fileInfoClass=59);self.assertEqual(len(identity),24)
      self.assertEqual(struct.unpack_from('<Q',identity,8)[0],index)
      for cls,size in [(8,4),(17,4),(28,16)]:self.assertEqual(len(raw.queryInfo(tree,file,fileInfoClass=cls)),size)
     finally:c.closeFile(tree,file)
   finally:c.close()
 def test_account_upload_random_write_and_cross_protocol_read(self):
  with server(write=True) as (root,ftp,port):
   payload=os.urandom(250000)
   with ftp_login(ftp) as f:f.storbinary('STOR 上传.bin',io.BytesIO(payload))
   cache={};kw={'port':port,'connection_cache':cache,'username':'tester','password':'test-password','auth_protocol':'ntlm'};path=r'\\127.0.0.1\share'
   try:
    smbclient.register_session('127.0.0.1',port=port,username='tester',password='test-password',require_signing=True,auth_protocol='ntlm',connection_cache=cache)
    with smbclient.open_file(path+'\\上传.bin','r+b',**kw) as f:
     self.assertEqual(f.read(),payload);f.seek(1024);f.write(b'CHANGED');f.truncate(200000)
    expected=payload[:1024]+b'CHANGED'+payload[1031:200000]
    with ftp_login(ftp) as f:
     out=io.BytesIO();f.retrbinary('RETR 上传.bin',out.write);self.assertEqual(out.getvalue(),expected)
     f.storbinary('APPE 上传.bin',io.BytesIO(b'end'))
    with smbclient.open_file(path+'\\上传.bin','rb',**kw) as f:self.assertEqual(f.read(),expected+b'end')
    smbclient.rename(path+'\\上传.bin',path+'\\改名.bin',**kw);smbclient.remove(path+'\\改名.bin',**kw);self.assertFalse((root/'改名.bin').exists())
   finally:smbclient.reset_connection_cache(connection_cache=cache)
 def test_smb_directory_mutations_and_nonempty_protection(self):
  with server(write=True) as (root,ftp,port):
   c=smb_login(port)
   try:
    c.createDirectory('share','新目录');c.putFile('share','新目录/file.bin',io.BytesIO(b'data').read)
    with self.assertRaises(SessionError):c.deleteDirectory('share','新目录')
    self.assertEqual((root/'新目录/file.bin').read_bytes(),b'data');c.deleteFile('share','新目录/file.bin');c.deleteDirectory('share','新目录');self.assertFalse((root/'新目录').exists())
   finally:c.close()
 def test_ftp_directory_and_file_mutations(self):
  with server(write=True) as (root,ftp,port):
   with ftp_login(ftp) as f:
    f.mkd('新目录');f.storbinary('STOR 新目录/file.txt',io.BytesIO(b'hello'));f.rename('新目录/file.txt','新目录/renamed.txt')
    with self.assertRaises(ftplib.error_perm):f.rmd('新目录')
    f.delete('新目录/renamed.txt');f.rmd('新目录')
    for command in ['DELE /','RMD /','MKD ../../escape','STOR ../../escape']:
     with self.assertRaises(ftplib.error_perm):f.sendcmd(command)
   self.assertFalse((root/'新目录').exists())
 def test_default_read_only_on_both_protocols(self):
  with server() as (root,ftp,port):
   original=(root/'你好.txt').read_bytes()
   with ftp_login(ftp) as f:
    for command in ['STOR 你好.txt','APPE 你好.txt','DELE 你好.txt','MKD x','RMD /','RNFR 你好.txt']:
     with self.assertRaises(ftplib.error_perm):f.sendcmd(command)
   c=smb_login(port)
   try:
    with self.assertRaises(SessionError):c.putFile('share','你好.txt',io.BytesIO(b'bad').read)
    with self.assertRaises(SessionError):c.deleteFile('share','你好.txt')
    with self.assertRaises(SessionError):c.createDirectory('share','x')
   finally:c.close()
   self.assertEqual((root/'你好.txt').read_bytes(),original)
 def test_anonymous_off_by_default(self):
  with server(write=True) as (root,ftp,port):
   with self.assertRaises(ftplib.error_perm):ftp_login(ftp,'anonymous','')
   with self.assertRaises(SessionError):smb_login(port,'anonymous','')
 def test_anonymous_read_only_despite_writable_account(self):
  with server(write=True,anonymous=True) as (root,ftp,port):
   original=(root/'你好.txt').read_bytes()
   with ftp_login(ftp,'anonymous','') as f:
    self.assertIn('你好.txt',f.nlst())
    for command in ['STOR new','DELE 你好.txt','MKD x']:
     with self.assertRaises(ftplib.error_perm):f.sendcmd(command)
   c=smb_login(port,'anonymous','')
   try:
    self.assertTrue(c.isGuestSession());out=io.BytesIO();c.getFile('share','你好.txt',out.write);self.assertEqual(out.getvalue(),original)
    with self.assertRaises(SessionError):c.putFile('share','guest.txt',io.BytesIO(b'bad').read)
    with self.assertRaises(SessionError):c.deleteFile('share','你好.txt')
   finally:c.close()
   self.assertFalse((root/'guest.txt').exists());self.assertEqual((root/'你好.txt').read_bytes(),original)
 def test_anonymous_write_requires_explicit_opt_in(self):
  with server(write=True,anonymous=True,anonymous_write=True) as (root,ftp,port):
   with ftp_login(ftp,'anonymous','') as f:f.storbinary('STOR guest.txt',io.BytesIO(b'ftp guest'))
   c=smb_login(port,'guest','')
   try:
    c.putFile('share','guest-smb.txt',io.BytesIO(b'smb guest').read);c.deleteFile('share','guest.txt')
   finally:c.close()
   self.assertEqual((root/'guest-smb.txt').read_bytes(),b'smb guest');self.assertFalse((root/'guest.txt').exists())
 def test_domain_and_case_insensitive_ntlm_account(self):
  with server() as (_,_,port):
   c=SMBConnection('LANFTP','127.0.0.1',sess_port=port,timeout=10)
   try:
    c.login('TeStEr','test-password','WORKGROUP')
    self.assertIn('你好.txt',[e.get_longname() for e in c.listPath('share','*')])
   finally:c.close()
 def test_wrong_password_does_not_fall_back_to_guest(self):
  with server(write=True,anonymous=True,anonymous_write=True) as (root,ftp,port):
   with self.assertRaises(ftplib.error_perm):ftp_login(ftp,'tester','bad-password')
   with self.assertRaises(SessionError):smb_login(port,'tester','bad-password')
 def test_smb_and_ftp_root_escape(self):
  with server(write=True) as (root,ftp,port):
   with ftp_login(ftp) as f:
    for command in ['RETR ../secret','RETR escape','STOR ../secret','DELE ../secret']:
     with self.assertRaises(ftplib.error_perm):f.sendcmd(command)
   c=smb_login(port)
   try:
    tid=c.connectTree('share')
    for name in ['..\\secret','escape']:
     with self.assertRaises(SessionError):c.createFile(tid,name,desiredAccess=0x120089,creationDisposition=1)
   finally:c.close()
   self.assertEqual((root.parent/'secret').read_text(),'outside share')
 def test_share_enumeration_ndr_levels(self):
  from impacket.dcerpc.v5 import srvs,transport
  with server() as (_,_,port):
   c=smb_login(port)
   try:
    rpc=transport.SMBTransport('127.0.0.1',port,filename=r'\srvsvc',smb_connection=c).get_dce_rpc();rpc.connect();rpc.bind(srvs.MSRPC_UUID_SRVS)
    for level in [0,1,2]:
     r=srvs.hNetrShareEnum(rpc,level);self.assertEqual(r['TotalEntries'],1);self.assertEqual(r['InfoStruct']['ShareInfo']['Level'+str(level)]['Buffer'][0]['shi%d_netname'%level],'share\x00')
     info=srvs.hNetrShareGetInfo(rpc,'share\x00',level);self.assertEqual(info['InfoStruct']['ShareInfo'+str(level)]['shi%d_netname'%level],'share\x00')
   finally:c.close()
 def test_smb_signature_tampering_rejected(self):
  with server(write=True) as (_,_,port):
   cache={}
   try:
    s=smbclient.register_session('127.0.0.1',port=port,username='tester',password='test-password',auth_protocol='ntlm',require_signing=True,connection_cache=cache)
    conn=s.connection;original=conn._generate_signature;req=SMB2TreeConnectRequest();req['buffer']=r'\\127.0.0.1\share'.encode('utf-16-le')
    try:
     conn._generate_signature=lambda *a,**kw:b'\0'*16
     pending=conn.send(req,sid=s.session_id)
    finally:conn._generate_signature=original
    with self.assertRaises(SMBResponseException) as cm:conn.receive(pending)
    self.assertEqual(cm.exception.status,0xc0000022)
   finally:smbclient.reset_connection_cache(connection_cache=cache)
 def test_guest_respects_client_required_signing(self):
  with server(anonymous=True) as (_,_,port):
   cache={}
   try:
    with self.assertRaises((SMBResponseException,SMBAuthenticationError)):smbclient.register_session('127.0.0.1',port=port,username='anonymous',password='',auth_protocol='ntlm',require_signing=True,connection_cache=cache)
   finally:smbclient.reset_connection_cache(connection_cache=cache)
 def test_unauthenticated_smb_denied_and_service_survives(self):
  with server() as (_,_,port):
   header=bytearray(64);struct.pack_into('<IHHIHHIIQIIQ',header,0,0x424d53fe,64,1,0,3,1,0,0,1,0,0,123)
   body=struct.pack('<HHHH',9,0,72,0);packet=bytes(header)+body
   with socket.create_connection(('127.0.0.1',port),timeout=10) as s:
    s.sendall(b'\x00'+len(packet).to_bytes(3,'big')+packet);length=int.from_bytes(s.recv(4)[1:],'big');r=b''
    while len(r)<length:r+=s.recv(length-len(r))
    self.assertEqual(struct.unpack_from('<I',r,8)[0],0xc0000203)
   c=smb_login(port)
   try:self.assertIn('你好.txt',[e.get_longname() for e in c.listPath('share','*')])
   finally:c.close()

if __name__=='__main__':unittest.main(verbosity=2)
