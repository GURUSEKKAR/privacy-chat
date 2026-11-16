#!/usr/bin/env python3
"""Delete only explicitly retained encrypted backup files older than the configured age."""
import argparse, pathlib, time
parser=argparse.ArgumentParser()
parser.add_argument('directory',type=pathlib.Path)
parser.add_argument('--max-age-hours',type=int,required=True)
parser.add_argument('--apply',action='store_true')
args=parser.parse_args()
if args.max_age_hours<1: parser.error('max age must be positive')
cutoff=time.time()-args.max_age_hours*3600
for file in args.directory.glob('secretchat-*.dump.age'):
    if file.is_symlink() or not file.is_file() or file.stat().st_mtime>=cutoff: continue
    print(('Removing ' if args.apply else 'Would remove ')+file.name)
    if args.apply: file.unlink()
