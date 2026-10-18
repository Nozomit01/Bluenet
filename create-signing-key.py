"""Create/reuse a local signing identity; never place this key in source archives."""
import os,secrets,subprocess
from pathlib import Path
root=Path(__file__).resolve().parent
folder=root/'.signing';folder.mkdir(exist_ok=True);folder.chmod(0o700)
store=folder/'release.jks';password=folder/'password.txt'
if store.exists():
    if not password.exists():raise SystemExit('Signing password file is missing; refusing to replace the signing key.')
else:
    password.write_text(secrets.token_urlsafe(32)+'\n');password.chmod(0o600)
    java=Path(os.environ['JAVA_HOME'])/'bin/keytool'
    subprocess.run([str(java),'-genkeypair','-keystore',str(store),'-storetype','PKCS12','-alias','bluenet','-keyalg','RSA','-keysize','3072',
        '-validity','10000','-dname','CN=Blue Network, O=Blue Network Android, C=RU',
        '-storepass:file',str(password),'-keypass:file',str(password)],check=True)
    store.chmod(0o600)
print('Local APK signing identity ready (private key excluded from source archive).')
