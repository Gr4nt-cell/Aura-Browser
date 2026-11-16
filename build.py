#!/usr/bin/env python3
"""Build a signed installable APK using only Android SDK + JDK; no Gradle download required."""
from pathlib import Path
import os,subprocess,shutil,zipfile
root=Path(__file__).resolve().parent
sdk=Path(os.environ.get('ANDROID_HOME','/workspace/android-sdk'))
bt=sdk/'build-tools/36.0.0'; api=sdk/'platforms/android-36/android.jar'
build=root/'build';build.mkdir(exist_ok=True)
def run(*args):
 subprocess.run([str(a) for a in args],check=True,cwd=root)
for name in ['classes','dex','compiled-res','generated']:
 p=build/name
 if p.exists():shutil.rmtree(p)
 p.mkdir()
run(bt/'aapt2','compile','--dir',root/'app/src/main/res','-o',build/'compiled-res')
run(bt/'aapt2','link','-o',build/'base.apk','-I',api,'--manifest',root/'app/src/main/AndroidManifest.xml','--java',build/'generated','--min-sdk-version','26','--target-sdk-version','36','--version-code','3','--version-name','1.2', '-A',root/'app/src/main/assets',*[str(p) for p in (build/'compiled-res').glob('*.flat')])
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
out=build/'AuraBrowser-1.2.apk'
run(bt/'apksigner','sign','--ks',key,'--ks-key-alias',alias,'--ks-pass','env:KEYSTORE_PASSWORD','--key-pass','env:KEY_PASSWORD','--out',out,build/'aligned.apk')
run(bt/'apksigner','verify','--verbose',out)
print(f'Built {out} ({out.stat().st_size:,} bytes)')
