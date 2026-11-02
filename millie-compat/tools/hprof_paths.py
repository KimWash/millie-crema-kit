#!/usr/bin/env python3
"""Minimal HPROF (J2SE, after hprof-conv) analyzer: find instances of a class and print the
shortest reference path from a GC root to each. Usage: hprof_paths.py file.hprof ClassNameSubstring"""
import struct, sys, collections
f = open(sys.argv[1], 'rb').read(); target = sys.argv[2]
p = f.index(b'\0') + 1
idsz = struct.unpack('>I', f[p:p+4])[0]; p += 12
rid = (lambda b, o: struct.unpack('>I', b[o:o+4])[0]) if idsz == 4 else (lambda b, o: struct.unpack('>Q', b[o:o+8])[0])
strings = {}; cls_name = {}; cls_info = {}  # class_obj -> (super, [(field_name_id, type)])
inst = {}  # obj -> (class_obj, data_bytes_offset, length)
arrs = {}  # obj -> list of ids
roots = {}  # obj -> root type
TS = {2: idsz, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
while p < len(f):
    tag = f[p]; ln = struct.unpack('>I', f[p+5:p+9])[0]; body = p + 9; end = body + ln
    if tag == 0x01:
        strings[rid(f, body)] = f[body+idsz:end].decode('utf-8', 'replace')
    elif tag == 0x02:
        cls_name[rid(f, body+4)] = rid(f, body+8+idsz)
    elif tag in (0x0c, 0x1c):
        q = body
        while q < end:
            st = f[q]; q += 1
            if st == 0xff: roots[rid(f, q)] = 'unknown'; q += idsz
            elif st == 0x01: roots[rid(f, q)] = 'jni-global'; q += 2*idsz
            elif st in (0x02, 0x03): roots[rid(f, q)] = 'jni-local/java-frame'; q += idsz + 8
            elif st == 0x04: roots[rid(f, q)] = 'native-stack'; q += idsz + 4
            elif st == 0x05: roots[rid(f, q)] = 'sticky-class'; q += idsz
            elif st == 0x06: roots[rid(f, q)] = 'thread-block'; q += idsz + 4
            elif st == 0x07: roots[rid(f, q)] = 'monitor'; q += idsz
            elif st == 0x08: roots[rid(f, q)] = 'thread'; q += idsz + 8
            elif st == 0x20:  # class dump
                co = rid(f, q); q += idsz; q += 4; sup = rid(f, q); q += idsz; q += idsz*5; q += 4
                n = struct.unpack('>H', f[q:q+2])[0]; q += 2
                for _ in range(n): q += 2; t = f[q]; q += 1 + TS[t]
                n = struct.unpack('>H', f[q:q+2])[0]; q += 2
                statics = []
                for _ in range(n):
                    nm = rid(f, q); q += idsz; t = f[q]; q += 1
                    if t == 2: statics.append((nm, rid(f, q)))
                    q += TS[t]
                n = struct.unpack('>H', f[q:q+2])[0]; q += 2
                fl = []
                for _ in range(n): nm = rid(f, q); q += idsz; t = f[q]; q += 1; fl.append((nm, t))
                cls_info[co] = (sup, fl, statics)
            elif st == 0x21:
                o = rid(f, q); q += idsz + 4; co = rid(f, q); q += idsz; n = struct.unpack('>I', f[q:q+4])[0]; q += 4
                inst[o] = (co, q, n); q += n
            elif st == 0x22:
                o = rid(f, q); q += idsz + 4; n = struct.unpack('>I', f[q:q+4])[0]; q += 4; q += idsz
                arrs[o] = [rid(f, q + i*idsz) for i in range(n)]; q += n*idsz
            elif st == 0x23:
                o = rid(f, q); q += idsz + 4; n = struct.unpack('>I', f[q:q+4])[0]; q += 4; t = f[q]; q += 1 + n*TS[t]
            elif st in (0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0xfe): q += idsz + (4 if st == 0xfe else 0)
            elif st == 0xc3: q += idsz + 8  # primitive array nodata
            else: raise SystemExit('unknown subtag %x at %d' % (st, q))
    p = end
name = lambda co: strings.get(cls_name.get(co), '?%x' % co)
# build reverse edges
rev = collections.defaultdict(list)
def fields_of(co):
    out = []
    while co and co in cls_info:
        out += cls_info[co][1]; co = cls_info[co][0]
    return out
for o, (co, off, n) in inst.items():
    q = off
    for nm, t in fields_of(co):
        if t == 2:
            r = rid(f, q)
            if r: rev[r].append((o, strings.get(nm, '?')))
        q += TS[t]
for o, lst in arrs.items():
    for i, r in enumerate(lst):
        if r: rev[r].append((o, '[%d]' % i))
for co, (sup, fl, statics) in cls_info.items():
    for nm, r in statics:
        if r: rev[r].append((co, 'static ' + strings.get(nm, '?'))); roots.setdefault(co, 'class-static')
targets = [o for o, (co, _, _) in inst.items() if target in name(co)]
print('instances of', target, ':', len(targets))
def desc(o):
    if o in inst: return name(inst[o][0])
    if o in arrs: return 'Object[]'
    if o in cls_name: return 'class ' + name(o)
    return '?'
for t in targets[:3]:
    # BFS backwards to a root, skipping weak/soft referents
    prev = {t: None}; dq = collections.deque([t]); found = None
    while dq:
        x = dq.popleft()
        if x in roots and x != t: found = x; break
        for (src, via) in rev.get(x, []):
            if via == 'referent': continue
            if src not in prev: prev[src] = (x, via); dq.append(src)
    print('\n== path for %x (%s)' % (t, desc(t)))
    if not found: print('  no strong path to a root (only weak/soft or unreachable)'); continue
    x = found; chain = []
    while x != t:
        nx, via = prev[x]; chain.append('%s  --%s-->' % (desc(x), via)); x = nx
    print('  ROOT[%s] ' % roots[found] + '\n    '.join(chain) + '\n    ' + desc(t))
