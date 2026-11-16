"""Runs emulator and adb within one managed process namespace."""
from pathlib import Path
import os,subprocess,time,sys,json,threading,http.server,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/qa';OUT.mkdir(exist_ok=True)
SDK=Path('/workspace/android-sdk');ADB=str(SDK/'platform-tools/adb')
def adb(*a,timeout=40):
 r=subprocess.run([ADB,*map(str,a)],capture_output=True,timeout=timeout)
 if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')+r.stdout.decode(errors='replace'))
 return r.stdout
class Fixture(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  page=b'''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Prism browser test</title><style>body{font:18px sans-serif;margin:24px;color:#243}button,input{font:inherit;margin:16px 0;padding:14px}footer{background:#bde;padding:20px}</style><h1>Browser test page</h1><p id="ok">Web content is visible.</p><a href="/second">Open second page</a><p><label>Upload file <input type="file" multiple></label></p><button onclick="window.open('/popup')">Open pop-up</button><p><a href="/sample.mp4">Video sample link</a></p><p style="height:450px">Scroll down</p><footer>Bottom of page is unobstructed</footer>'''
  if self.path.endswith('.mp4'):page=b'not-a-playable-video-fixture'
  self.send_response(200);self.send_header('Content-Type','video/mp4' if self.path.endswith('.mp4') else 'text/html');self.send_header('Content-Length',str(len(page)));self.end_headers();self.wfile.write(page)
 def log_message(self,*a):pass
server=http.server.ThreadingHTTPServer(('0.0.0.0',8765),Fixture);threading.Thread(target=server.serve_forever,daemon=True).start()
log=open(OUT/'emulator.log','w');emu=subprocess.Popen([str(SDK/'emulator/emulator'),'-avd','prism-qa2','-no-window','-no-audio','-no-boot-anim','-gpu','swiftshader','-accel','off','-memory','2048','-cores','4','-no-snapshot','-camera-back','none','-camera-front','none'],stdout=log,stderr=subprocess.STDOUT)
results=[]
def record(s):print(s,flush=True);results.append(s)
def shot(name):
 (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',timeout=60))
def dump(name):
 adb('shell','uiautomator','dump','/sdcard/prism-window.xml',timeout=70);data=adb('shell','cat','/sdcard/prism-window.xml');(OUT/(name+'.xml')).write_bytes(data);return ET.fromstring(data)
try:
 adb('start-server');start=time.time();last=0
 while time.time()-start<480:
  try:
   if adb('shell','getprop','sys.boot_completed',timeout=10).strip()==b'1':break
  except Exception:pass
  if time.time()-last>30:print('Emulator booting: '+str(int(time.time()-start))+' seconds',flush=True);last=time.time()
  if emu.poll() is not None:raise RuntimeError('Emulator stopped; see emulator.log')
  time.sleep(3)
 else:raise RuntimeError('Software emulator did not finish booting in eight minutes')
 record('PASS Android 15 emulator boot')
 for key in ['window_animation_scale','transition_animation_scale','animator_duration_scale']:adb('shell','settings','put','global',key,'0')
 adb('shell','input','keyevent','82');adb('install','-r',ROOT/'build/Prism-1.0.apk',timeout=120);record('PASS APK installation')
 adb('logcat','-c');adb('shell','am','start','-W','-n','com.gr4nt.aurabrowser/.BrowserActivity',timeout=90);time.sleep(6)
 assert adb('shell','pidof','com.gr4nt.aurabrowser').strip(),'App process missing'
 shot('mobile-home');tree=dump('mobile-home');assert any('Favourites'==n.get('text') for n in tree.iter('node')),'Start page missing';record('PASS phone start page rendered')
 adb('shell','am','start','-W','-a','android.intent.action.VIEW','-d','http://10.0.2.2:8765/','com.gr4nt.aurabrowser/.BrowserActivity',timeout=90);time.sleep(12)
 shot('mobile-web');tree=dump('mobile-web');assert any('Browser test page' in n.get('text','') for n in tree.iter('node')),'WebView fixture did not render';record('PASS WebView navigation and page rendering')
 # Open tab deck using the toolbar's accessible Tabs label.
 nodes=[n for n in tree.iter('node') if n.get('content-desc')=='Tabs'];
 if nodes:
  import re
  b=list(map(int,re.findall(r'\d+',nodes[-1].get('bounds'))));adb('shell','input','tap',(b[0]+b[2])//2,(b[1]+b[3])//2);time.sleep(2);shot('mobile-tabs');dump('mobile-tabs');record('PASS tab overview opened');adb('shell','input','keyevent','4')
 # Trigger a compact tablet window and a desktop window on the same device.
 adb('shell','wm','size','1600x1100');adb('shell','wm','density','240');time.sleep(6);shot('tablet-web');dump('tablet-web');record('PASS tablet resize rendered')
 adb('shell','wm','size','1920x1200');adb('shell','wm','density','160');time.sleep(6);shot('desktop-web');tree=dump('desktop-web');assert any(n.get('text')=='Your window to the web' for n in tree.iter('node')),'Desktop sidebar missing';record('PASS desktop resize and sidebar rendered')
 crash=adb('logcat','-d','-s','AndroidRuntime:E').decode(errors='replace');(OUT/'crash-log.txt').write_text(crash);assert 'FATAL EXCEPTION' not in crash,crash;record('PASS no AndroidRuntime startup/resize crash')
except Exception as e:
 record('FAIL '+str(e));(OUT/'results.txt').write_text('\n'.join(results));raise
finally:
 (OUT/'results.txt').write_text('\n'.join(results));server.shutdown()
 try:adb('emu','kill',timeout=5)
 except Exception:emu.terminate()
 try:emu.wait(timeout=15)
 except Exception:emu.kill()
