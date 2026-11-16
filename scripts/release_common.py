"""Shared file inventory and source binding for release evidence."""
import hashlib,json,pathlib,subprocess

ROOT=pathlib.Path(__file__).resolve().parent.parent

def source_inventory(root=ROOT):
    output=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=root).decode()
    return sorted({name for name in output.split('\0') if name and not any(part in {'.git','.local','target','__pycache__'} for part in pathlib.PurePosixPath(name).parts) and (root/name).is_file()})

def verification_digest(root=ROOT):
    records=[]
    for name in source_inventory(root):
        path=pathlib.PurePosixPath(name)
        if not (name.startswith('web-client/') or name.endswith('pom.xml') or any(name.startswith(module+'/src/') for module in ['backend','desktop-client','protocol-contracts','e2e-tests'])):continue
        if (root/name).is_symlink():raise ValueError('Verification inputs cannot contain symlinks')
        records.append({'path':name,'sha256':hashlib.sha256((root/name).read_bytes()).hexdigest()})
    return hashlib.sha256(json.dumps(records,sort_keys=True,separators=(',',':')).encode()).hexdigest()
