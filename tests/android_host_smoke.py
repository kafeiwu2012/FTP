#!/usr/bin/env python3
"""Run only with the disposable emulator fixture started by SmokeInstrumentation.

Uses the real Android SAF backend and foreground service through adb TCP forwards.
No device Root, fabricated document grants, or changes to other directories are used.
"""
import ftplib, hashlib, io, os, pathlib, socket, subprocess, time
from test_web import request

ADB=os.environ.get('ADB','/workspace/tools/android-sdk/platform-tools/adb')
SERIAL=os.environ.get('ANDROID_TEST_SERIAL','emulator-5554')
if not SERIAL.startswith('emulator-'):raise SystemExit('Disposable emulator required')
adb=[ADB,'-s',SERIAL];forwards=[]
def command(*args,check=True):return subprocess.run(adb+list(args),capture_output=True,text=True,check=check,timeout=30)
def forward(remote):
 port=int(command('forward','tcp:0','tcp:'+str(remote)).stdout.strip());forwards.append(port);return port
class AndroidFTP(ftplib.FTP):
 def makepasv(self):
  _,remote=super().makepasv();return '127.0.0.1',forward(remote)
def ftp_login(port,user='tester',password='test-password'):
 f=AndroidFTP();f.connect('127.0.0.1',port,timeout=30);f.login(user,password);return f

def main():
 deadline=time.monotonic()+240
 while time.monotonic()<deadline:
  r=command('shell','run-as','com.lanftp','cat','files/smoke-ready',check=False)
  if r.returncode==0 and r.stdout.startswith('uid='):ready=r.stdout;break
  time.sleep(1)
 else:raise RuntimeError('Android instrumentation did not report ready; inspect its result')
 fields=dict(line.split('=',1) for line in ready.splitlines() if '=' in line)
 assert int(fields['uid'])>=10000,'App must run as an ordinary non-root Android UID'
 ftp_port=forward(int(fields['ftp']))
 print(ready.strip(),flush=True)
 payload=os.urandom(70013);original=b'Android SAF integration fixture\n'
 with ftp_login(ftp_port) as f:
  out=io.BytesIO();f.retrbinary('RETR sample.txt',out.write);assert out.getvalue()==original
  f.storbinary('STOR 手机上传.bin',io.BytesIO(payload))
  f.rename('手机上传.bin','改名.bin')
  out=io.BytesIO();f.retrbinary('RETR 改名.bin',out.write);assert out.getvalue()==payload
  f.delete('改名.bin');f.mkd('ftp-dir');f.rmd('ftp-dir')
 print('PASS: actual SAF FTP Unicode upload, download byte comparison, rename, delete, mkdir/rmdir',flush=True)
 with ftp_login(ftp_port,'anonymous','') as f:
  assert 'sample.txt' in f.nlst()
  try:f.storbinary('STOR must-not-exist.txt',io.BytesIO(b'bad'))
  except ftplib.error_perm:pass
  else:raise AssertionError('Anonymous FTP write was not denied')
 print('PASS: anonymous reads allowed; anonymous writes denied on actual Android service',flush=True)
 web_port=forward(int(fields['web']))
 assert request(web_port,route='/api/list',path='/',auth=None)[0]==200
 assert request(web_port,'PUT','/api/file','/anonymous-denied.txt',b'bad',auth=None)[0]==403
 assert request(web_port,'PUT','/api/file','/你好.txt',payload,overwrite=True)[0]==200
 with ftp_login(ftp_port) as f:
  out=io.BytesIO();f.retrbinary('RETR 你好.txt',out.write);assert out.getvalue()==payload
 assert request(web_port,route='/api/file',path='/你好.txt',headers={'Range':'bytes=100-200'})[2]==payload[100:201]
 print('PASS: actual SAF browser upload, FTP cross-read, range download and anonymous write denial',flush=True)
 if os.environ.get('BROWSER_SMOKE')=='1':
  subprocess.run(['node',str(pathlib.Path(__file__).with_name('browser_smoke.js')),str(web_port)],check=True,timeout=240)
 assert request(web_port,'DELETE','/api/file','/你好.txt')[0]==200
 print('Payload SHA-256:' ,hashlib.sha256(payload).hexdigest(),flush=True)
 command('shell','run-as','com.lanftp','touch','files/smoke-stop')
 deadline=time.monotonic()+30
 while time.monotonic()<deadline:
  try:
   with ftp_login(ftp_port) as f:pass
  except (EOFError,OSError,ftplib.Error):print('PASS: stopping sharing closes FTP service',flush=True);break
  time.sleep(.5)
 else:raise AssertionError('Sharing did not stop')
 print('ANDROID_SMOKE_PASSED',flush=True)

if __name__=='__main__':
 try:main()
 finally:
  command('shell','run-as','com.lanftp','touch','files/smoke-stop',check=False)
  for port in forwards:command('forward','--remove','tcp:'+str(port),check=False)
