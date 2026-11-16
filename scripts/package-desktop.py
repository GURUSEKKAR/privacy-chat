#!/usr/bin/env python3
"""Build a self-contained native desktop image with the current host's JDK 21."""
import argparse,json,os,pathlib,platform,shutil,subprocess,tempfile

root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument('--output',type=pathlib.Path,default=root/'target'/'desktop')
parser.add_argument('--local-tls',action='store_true')
parser.add_argument('--replace',action='store_true')
args=parser.parse_args()
os.chdir(root)
home=os.environ.get('JAVA_HOME')
if not home and platform.system()=='Darwin':home=subprocess.check_output(['/usr/libexec/java_home','-v','21'],text=True).strip()
if not home:raise SystemExit('Set JAVA_HOME to a JDK 21 installation')
java=pathlib.Path(home)/'bin'/'java';jpackage=pathlib.Path(home)/'bin'/'jpackage'
version=subprocess.run([str(java),'-version'],capture_output=True,text=True,check=True)
if 'version "21.' not in version.stderr:raise SystemExit('Native release packaging requires JDK 21')
environment=dict(os.environ,JAVA_HOME=home)
def run(command):subprocess.run([str(x) for x in command],check=True,env=environment)
output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
name='Null';image=output/(name+'.app' if platform.system()=='Darwin' else name)
if image.exists():
    if not args.replace:raise SystemExit('Image already exists; use --replace to replace this generated app')
    shutil.rmtree(image)
run(['mvn','-B','-pl','desktop-client','-am','package','-DskipTests'])
with tempfile.TemporaryDirectory(prefix='desktop-input-',dir=root/'target') as staging:
    stage=pathlib.Path(staging)
    run(['mvn','-B','-pl','desktop-client','-am','dependency:copy-dependencies','-DincludeScope=runtime','-DoutputDirectory='+str(stage)])
    jar=root/'desktop-client/target/desktop-client-0.1.0-SNAPSHOT.jar';shutil.copy2(jar,stage/jar.name)
    command=[jpackage,'--type','app-image','--name',name,'--app-version','1.0.0','--vendor','Null',
        '--description','Ephemeral invitation-only encrypted group chat','--input',stage,'--main-jar',jar.name,
        '--main-class','com.secretchat.client.Launcher','--dest',output]
    if args.local_tls:
        trust=root/'.local/tls/truststore.p12'
        if not trust.is_file():raise SystemExit('Run scripts/prepare-local-tls.sh before packaging --local-tls')
        shutil.copy2(trust,stage/trust.name)
        command+=['--java-options','-Dsecretchat.packagedServer=https://localhost:18443',
            '--java-options','-Djavax.net.ssl.trustStore=$APPDIR/truststore.p12',
            '--java-options','-Djavax.net.ssl.trustStorePassword=changeit']
    run(command)
binary=image/'Contents/MacOS'/name if platform.system()=='Darwin' else image/'bin'/name
check=subprocess.run([str(binary),'--verify-runtime'],check=True,capture_output=True,text=True,timeout=40,env=environment)
result=next((line for line in check.stdout.splitlines() if line.startswith('{')),None)
if not result or json.loads(result).get('ok') is not True:raise SystemExit('Packaged runtime did not verify')
(output/'runtime-check.json').write_text(result+'\n')
if platform.system()=='Darwin':
    archive=output/'Null-macos.zip'
    if archive.exists():archive.unlink()
    run(['/usr/bin/ditto','-c','-k','--sequesterRsrc','--keepParent',image,archive])
    print('Native application:',image)
    print('Portable desktop archive:',archive)
else:print('Native application:',image)
print('This local application image is not a notarized public distribution.')
