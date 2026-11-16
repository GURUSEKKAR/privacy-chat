#!/usr/bin/env python3
"""Resolve runtime Maven coordinates and query OSV. Fails closed on unavailable service."""
import datetime,json,pathlib,re,subprocess,urllib.request,sys
result=subprocess.run(['mvn','-B','dependency:list','-DincludeScope=runtime'],text=True,capture_output=True,check=True)
packages=set()
for line in result.stdout.splitlines():
    match=re.search(r'\[INFO\]\s+([\w.\-]+):([\w.\-]+):jar:(?:[\w.\-]+:)?([^:\s]+):(compile|runtime)',line)
    if match and match[1]!='com.secretchat': packages.add((match[1]+':'+match[2],match[3]))
if not packages: raise SystemExit('No dependencies resolved')
packages=sorted(packages)
request=urllib.request.Request('https://api.osv.dev/v1/querybatch',data=json.dumps({'queries':[{'package':{'ecosystem':'Maven','name':name},'version':version} for name,version in packages]}).encode(),headers={'Content-Type':'application/json'})
with urllib.request.urlopen(request,timeout=60) as response: results=json.load(response)['results']
findings=[{'package':name,'version':version,'vulnerabilities':entry['vulns']} for (name,version),entry in zip(packages,results) if entry.get('vulns')]
report={'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':'https://api.osv.dev/v1/querybatch','package_count':len(packages),'packages':[{'name':name,'version':version} for name,version in packages],'findings':findings}
pathlib.Path('docs/verification/dependency-audit.json').write_text(json.dumps(report,indent=2)+'\n')
print(f'Checked {len(packages)} runtime Maven coordinates; {len(findings)} packages have published findings.')
sys.exit(bool(findings))
