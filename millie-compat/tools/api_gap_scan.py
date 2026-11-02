#!/usr/bin/env python3
"""Find framework/core members referenced by app smali that don't exist on the device's boot classpath.
usage: api_gap_scan.py <app_smali_dir>... -- <boot_smali_dir>..."""
import os, re, sys, collections
args = sys.argv[1:]; i = args.index('--'); app_dirs, boot_dirs = args[:i], args[i+1:]
def walk(d):
    for r, _, fs in os.walk(d):
        for f in fs:
            if f.endswith('.smali'): yield os.path.join(r, f)
cls = {}  # name -> (super, [ifaces], set(methods), set(fields))
def index(path, store):
    sup = None; ifs = []; ms = set(); fs = set(); name = None
    for line in open(path, errors='replace'):
        if line.startswith('.class'): name = line.split()[-1]
        elif line.startswith('.super'): sup = line.split()[-1]
        elif line.startswith('.implements'): ifs.append(line.split()[-1])
        elif line.startswith('.method'):
            ms.add(line.split()[-1])
        elif line.startswith('.field'):
            t = line.split(' = ')[0].split()[-1]; fs.add(t.split(':')[0])
    if name: store[name] = (sup, ifs, ms, fs)
for d in boot_dirs:
    for p in walk(d): index(p, cls)
app_cls = {}
for d in app_dirs:
    for p in walk(d): index(p, app_cls)
def has(c, kind, member, seen=None):
    seen = seen or set()
    if c in seen: return None
    seen.add(c)
    e = cls.get(c)
    if e is None: return None  # unknown class
    sup, ifs, ms, fs = e
    if kind == 'm' and member in ms: return True
    if kind == 'f' and member in fs: return True
    for nxt in ([sup] if sup else []) + ifs:
        r = has(nxt, kind, member, seen)
        if r: return True
    return False
ref = re.compile(r'(L(?:android|java|javax|dalvik|org/apache/http|org/json|org/w3c|org/xml)/[^;\s]+;)->([^\s(:]+)(\([^)]*\)\S+|:\S+)')
missing = collections.defaultdict(set); unknown = collections.defaultdict(set)
for d in app_dirs:
    for p in walk(d):
        owner = None
        for line in open(p, errors='replace'):
            if line.startswith('.class'): owner = line.split()[-1]
            m = ref.search(line)
            if not m: continue
            c, n, sig = m.groups()
            if c in app_cls: continue
            kind = 'm' if sig.startswith('(') else 'f'
            member = n + sig if kind == 'm' else n
            r = has(c, kind, member)
            if r is None: unknown[c].add(owner)
            elif r is False: missing[c + '->' + member].add(owner)
for k in sorted(missing):
    users = sorted(missing[k]); print('MISSING %s  (%d users, e.g. %s)' % (k, len(users), users[0]))
print('--- unknown classes:')
for k in sorted(unknown): print('UNKNOWN %s (%d users, e.g. %s)' % (k, len(unknown[k]), sorted(unknown[k])[0]))
