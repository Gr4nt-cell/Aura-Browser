#!/usr/bin/env python3
"""Build a signed installable APK using only Android SDK + JDK; no Gradle download required."""
from pathlib import Path
import os,subprocess,shutil,zipfile
root=Path(__file__).resolve().parent
sdk=Path(os.environ.get('ANDROID_HOME','/workspace/android-sdk'))
bt=sdk/'build-tools/36.0.0'; api=sdk/'platforms/android-36/android.jar'
VERSION_CODE='4';VERSION_NAME='1.3'
build=root/'build';build.mkdir(exist_ok=True)
def run(*args):
 subprocess.run([str(a) for a in args],check=True,cwd=root)
for name in ['classes','dex','compiled-res','generated']:
 p=build/name
 if p.exists():shutil.rmtree(p)
 p.mkdir()
run(bt/'aapt2','compile','--dir',root/'app/src/main/res','-o',build/'compiled-res')
run(bt/'aapt2','link','-o',build/'base.apk','-I',api,'--manifest',root/'app/src/main/AndroidManifest.xml','--java',build/'generated','--min-sdk-version','26','--target-sdk-version','36','--version-code',VERSION_CODE,'--version-name',VERSION_NAME, '-A',root/'app/src/main/assets',*[str(p) for p in (build/'compiled-res').glob('*.flat')])
sources=list((root/'app/src/main/java').rglob('*.java'))+list((build/'generated').rglob('*.java'))
run('javac','--release','17','-encoding','UTF-8','-classpath',api,'-d',build/'classes',*sources)
with zipfile.ZipFile(build/'classes.jar','w',zipfile.ZIP_DEFLATED) as out:
 for p in (build/'classes').rglob('*.class'):out.write(p,p.relative_to(build/'classes'))
run(bt/'d8','--release','--min-api','26','--lib',api,'--output',build/'dex',build/'classes.jar')
shutil.copy2(build/'base.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as apk:
 for p in (build/'dex').glob('*.dex'):apk.write(p,p.name)
run(bt/'zipalign','-f','-p','4',build/'unsigned.apk',build/'aligned.apk')
signing=root/'signing';signing.mkdir(exist_ok=True)
configured_key=os.environ.get('ANDROID_KEYSTORE_PATH')
key=Path(configured_key) if configured_key else signing/'development.jks'
alias=os.environ.get('KEY_ALIAS','prismdev')
os.environ.setdefault('KEYSTORE_PASSWORD','android')
os.environ.setdefault('KEY_PASSWORD',os.environ['KEYSTORE_PASSWORD'])
if not key.exists():
 if configured_key:raise SystemExit('Configured signing keystore does not exist')
 run('keytool','-genkeypair','-keystore',key,'-storepass:env','KEYSTORE_PASSWORD','-keypass:env','KEY_PASSWORD','-alias',alias,'-dname','CN=Aura Browser Development, O=Gr4nt','-keyalg','RSA','-keysize','3072','-validity','10000')
out=build/f'AuraBrowser-{VERSION_NAME}.apk'
run(bt/'apksigner','sign','--ks',key,'--ks-key-alias',alias,'--ks-pass','env:KEYSTORE_PASSWORD','--key-pass','env:KEY_PASSWORD','--out',out,build/'aligned.apk')
run(bt/'apksigner','verify','--verbose',out)
print(f'Built {out} ({out.stat().st_size:,} bytes)')

# Android App Bundle for Google Play. Needs BUNDLETOOL_JAR (path to bundletool-all.jar).
bundletool=os.environ.get('BUNDLETOOL_JAR')
if bundletool:
 proto=build/'proto'
 if proto.exists():shutil.rmtree(proto)
 proto.mkdir()
 run(bt/'aapt2','link','--proto-format','-o',build/'base-proto.zip','-I',api,'--manifest',root/'app/src/main/AndroidManifest.xml','--min-sdk-version','26','--target-sdk-version','36','--version-code',VERSION_CODE,'--version-name',VERSION_NAME,'-A',root/'app/src/main/assets',*[str(p) for p in (build/'compiled-res').glob('*.flat')])
 (build/'module').mkdir(exist_ok=True);module=build/'module'/'base.zip'
 with zipfile.ZipFile(build/'base-proto.zip') as src, zipfile.ZipFile(module,'w',zipfile.ZIP_DEFLATED) as mod:
  for info in src.infolist():
   if info.filename=='AndroidManifest.xml':mod.writestr('manifest/AndroidManifest.xml',src.read(info))
   elif info.filename=='resources.pb':mod.writestr('resources.pb',src.read(info))
   elif info.filename.startswith('res/'):mod.writestr(info.filename,src.read(info))
  for p in (build/'dex').glob('*.dex'):mod.write(p,'dex/'+p.name)
  for p in (root/'app/src/main/assets').rglob('*'):
   if p.is_file():mod.write(p,'assets/'+str(p.relative_to(root/'app/src/main/assets')))
 aab_unsigned=build/'unsigned.aab';aab=build/f'AuraBrowser-{VERSION_NAME}.aab'
 if aab_unsigned.exists():aab_unsigned.unlink()
 run('java','-jar',bundletool,'build-bundle','--modules',module,'--output',aab_unsigned)
 run('jarsigner','-keystore',key,'-storepass:env','KEYSTORE_PASSWORD','-keypass:env','KEY_PASSWORD','-sigalg','SHA256withRSA','-digestalg','SHA-256','-signedjar',aab,aab_unsigned,alias)
 run('java','-jar',bundletool,'validate','--bundle',aab)
 print(f'Built {aab} ({aab.stat().st_size:,} bytes)')
