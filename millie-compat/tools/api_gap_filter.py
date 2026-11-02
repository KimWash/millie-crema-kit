#!/usr/bin/env python3
"""Filter api_gap_scan output to members introduced in API levels (lo..hi], i.e. those whose SDK checks
were compiled out for a minSdk=hi app running on lo. usage: api_gap_filter.py gap.txt api-versions.xml 17 19"""
import sys, re, xml.etree.ElementTree as ET
gap, xmlp, lo, hi = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
classes = {}
for c in ET.parse(xmlp).getroot().iter('class'):
    cs = int(c.get('since', 1)); mem = {}
    for m in c:
        if m.tag in ('method', 'field'):
            mem[(m.tag, m.get('name'))] = int(m.get('since', cs))
    sups = [e.get('name') for e in c if e.tag in ('extends', 'implements')]
    classes[c.get('name')] = (cs, mem, sups)
def since(cname, tag, name, seen=None):
    seen = seen or set()
    if cname in seen or cname not in classes: return None
    seen.add(cname); cs, mem, sups = classes[cname]
    if (tag, name) in mem: return mem[(tag, name)]
    for s in sups:
        r = since(s, tag, name, seen)
        if r is not None: return r
    return None
for line in open(gap):
    m = re.match(r'MISSING L([^;]+);->(\S+)\s+(.*)', line)
    if m:
        c, member, rest = m.groups()
        if '(' in member:
            tag = 'method'; name = member
        else:
            tag = 'field'; name = member
        s = since(c, tag, name)
        if s is not None and lo < s <= hi: print('API%d %s->%s %s' % (s, c, member, rest))
        continue
    m = re.match(r'UNKNOWN L([^;]+);\s+(.*)', line)
    if m and m.group(1) in classes and lo < classes[m.group(1)][0] <= hi:
        print('API%d CLASS %s %s' % (classes[m.group(1)][0], m.group(1), m.group(2)))
