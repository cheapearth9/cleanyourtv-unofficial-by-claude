#!/usr/bin/env python3
"""Turn upstream cleanyourtv's examples/*/ folders into the phone app's asset database.

    python3 tools/build_db.py <path to cleanyourtv checkout> phone/src/main/assets/db

Writes:
  models.txt   one block per TV:  "#M<TAB>dir<TAB>tv<TAB>brand<TAB>os<TAB>stock<TAB>hasConfig"
                                  "#N<TAB>notes"   then one package name per line
  cfg/<dir>.json   the TV's recommended dock layout (upstream config.json), when it has one
"""
import json, os, re, sys

PKG = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
src, dst = sys.argv[1], sys.argv[2]
ex = os.path.join(src, 'examples')
os.makedirs(os.path.join(dst, 'cfg'), exist_ok=True)
for f in os.listdir(os.path.join(dst, 'cfg')):
    os.remove(os.path.join(dst, 'cfg', f))

def clean(s):
    return re.sub(r'[\t\r\n]+', ' ', str(s or '')).strip()

blocks, skipped = [], []
for d in sorted(os.listdir(ex)):
    p = os.path.join(ex, d)
    lst = os.path.join(p, 'debloat.txt')
    if not os.path.isfile(lst):
        continue
    raw = open(lst, encoding='utf-8-sig').read().replace('\r', '')
    pkgs, stock = [], ''
    for line in raw.split('\n'):
        m = re.match(r'^\s*#\s*Stock launcher:\s*([A-Za-z0-9_.]+)', line)
        if m and not stock:
            stock = m.group(1)
        line = re.sub(r'\s+#.*$', '', line).strip()
        if not line or line.startswith('#'):
            continue
        tok = line.split()[0]
        if PKG.match(tok) and tok not in pkgs:
            pkgs.append(tok)
    if not pkgs:
        skipped.append(d)
        continue
    meta = {}
    tj = os.path.join(p, 'tv.json')
    if os.path.isfile(tj):
        try:
            meta = json.load(open(tj, encoding='utf-8-sig'))
        except ValueError:
            meta = {}
    cfg = os.path.join(p, 'config.json')
    has_cfg = 0
    if os.path.isfile(cfg):
        try:
            obj = json.load(open(cfg, encoding='utf-8-sig'))
            if isinstance(obj, dict):
                json.dump(obj, open(os.path.join(dst, 'cfg', d + '.json'), 'w'), separators=(',', ':'))
                has_cfg = 1
        except ValueError:
            pass
    name = clean(meta.get('tv')) or d
    blocks.append('\t'.join(['#M', d, name, clean(meta.get('brand')), clean(meta.get('os')), stock, str(has_cfg)]))
    blocks.append('#N\t' + clean(meta.get('notes'))[:600])
    blocks.extend(pkgs)

open(os.path.join(dst, 'models.txt'), 'w', encoding='utf-8').write('\n'.join(blocks) + '\n')
n = sum(1 for b in blocks if b.startswith('#M'))
print(f'{n} TV lists, {len(skipped)} skipped (no active packages): {skipped[:5]}')
