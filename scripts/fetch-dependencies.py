#!/usr/bin/env python3
"""Fetch only this checkout's explicitly pinned source dependencies; never update a dirty checkout."""
from pathlib import Path
import argparse,json,re,subprocess,sys

def git(path,*args,capture=False):
    return subprocess.run(['git','-C',str(path),*args],check=True,text=True,
        stdout=subprocess.PIPE if capture else None).stdout

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true',help='check pins without fetching')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    lock=json.loads((root/'dependencies.lock.json').read_text())
    if not args.check:
        git(root, 'submodule', 'update', '--init', '--recursive')
    else:
        states=git(root, 'submodule', 'status', '--recursive', capture=True).splitlines()
        if any(line.startswith(('-', '+', 'U')) for line in states):
            raise SystemExit('A declared submodule is missing or differs from its pinned revision')
    directory=root/lock['directory']
    if not directory.resolve().is_relative_to(root):raise SystemExit('Dependency directory must be inside this checkout')
    for name,spec in lock['repositories'].items():
        if not re.fullmatch('[A-Za-z0-9_-]+',name) or not re.fullmatch('[0-9a-f]{40}',spec['commit']):
            raise SystemExit('Invalid dependency name or unpinned revision')
        target=directory/name
        if target.is_symlink():raise SystemExit(f'{target}: dependency symlinks are not reproducible')
        if not (target/'.git').exists():
            if args.check:raise SystemExit(f'Missing dependency: {name}')
            directory.mkdir(parents=True,exist_ok=True)
            subprocess.run(['git','clone',spec['url'],str(target)],check=True)
        current=git(target,'rev-parse','HEAD',capture=True).strip()
        if args.check and git(target,'status','--porcelain',capture=True).strip():
            raise SystemExit(f'{name}: dependency checkout contains uncommitted changes')
        if current!=spec['commit']:
            if args.check:raise SystemExit(f'{name}: expected {spec["commit"]}, found {current}')
            if git(target,'status','--porcelain',capture=True).strip():raise SystemExit(f'{name}: refusing to replace local changes')
            git(target,'fetch','origin',spec['commit'])
            git(target,'checkout','--detach',spec['commit'])
        elif not args.check and not git(target,'ls-files',capture=True).strip():
            git(target,'checkout','--detach',spec['commit'])
        if not args.check:git(target,'submodule','update','--init','--recursive')
        # Dependency locks are flattened and must agree with the revisions used here.
        nested=target/'dependencies.lock.json'
        if nested.exists():
            for child,childspec in json.loads(nested.read_text())['repositories'].items():
                if lock['repositories'].get(child)!=childspec:raise SystemExit(f'Conflicting or undeclared dependency: {name} -> {child}')
        print(f'{name} {spec["commit"]}')
if __name__=='__main__':main()
