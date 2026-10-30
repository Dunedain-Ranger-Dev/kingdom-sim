"""Builds the large continent (large_ruins.json and large_settled.json in the mod's map data): the continent grown to about 100 nodes.

Keeps every existing node (positions scaled 1.5x), grows each faction's homeland by 7 nodes inside its own region,
adds neutral land on the borders, then links everything with non-crossing roads. The Dwarves keep a sealed rear:
their resource nodes touch only their capital.
"""
import json, os
HERE = os.path.dirname(os.path.abspath(__file__))
import math
import random

random.seed(7)
src = json.load(open(os.path.join(HERE, 'small_map.json'), encoding='utf-8'))
SCALE = 1.5
nodes = []
for n in src['nodes']:
    n = dict(n)
    n['x'], n['y'] = round(n['x'] * SCALE), round(n['y'] * SCALE)
    nodes.append(n)
settled_src = {n['id']: n for n in src['settled']}

caps = {n['owner']: n for n in nodes if n['type'] == 'capital'}
MIN_D = 150


def d(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def free(p, extra=0):
    return all(d(p, (n['x'], n['y'])) >= MIN_D + extra for n in nodes)


xs = [n['x'] for n in nodes]
ys = [n['y'] for n in nodes]
X0, X1, Y0, Y1 = min(xs) - 250, max(xs) + 60, min(ys) - 250, max(ys) + 300
cands = []
step = 70
for gx in range(X0, X1, step):
    for gy in range(Y0, Y1, step):
        cands.append((gx + random.randint(-25, 25), gy + random.randint(-25, 25)))


def nearest_cap(p):
    return min(caps.values(), key=lambda c: d(p, (c['x'], c['y'])))


NAMES = {
    'hb': ['Brightwater', 'Gullcrest', 'Tidewall Keep', 'Seagrass Fields', 'Netmakers\' Dock', 'Lighthouse Point', 'Shellbeach Mine'],
    'go': ['Muckmire', 'Rotgut Den', 'Clawpit Fort', 'Beetle Farms', 'Junkpile Quarry', 'Snoutwatch', 'Fungus Hollow'],
    'or': ['Ironhoof Camp', 'Skullrock', 'Warcry Hold', 'Ashgrass Range', 'Bonepit Mine', 'Horizon Totem', 'Redfang Herds'],
    'ko': ['Coilden', 'Glittercrag', 'Fangwall Keep', 'Lichen Fields', 'Ember Shaft', 'Ridge Eye', 'Slagpool'],
    'un': ['Hollowmere', 'Cryptford', 'Wailing Bastion', 'Mournrot Fields', 'Ossuary Pit', 'Gloomlamp', 'Weepwillow'],
    'el': ['Galeheart', 'Cinderwake', 'Tidecall Bastion', 'Bloomstone', 'Geode Hollow', 'Windspire', 'Ashglass Flats'],
}
TYPES = ['town', 'castle', 'food', 'production', 'outpost', 'food', 'production']
# The name lists above are in the order: town, town, castle, food, production, outpost, extra food/production.
NAME_TYPES = ['town', 'town', 'castle', 'food', 'production', 'outpost', 'food']

for kid, names in NAMES.items():
    cap = caps[kid]
    cell = [p for p in cands if nearest_cap(p) is cap]
    cell.sort(key=lambda p: d(p, (cap['x'], cap['y'])))
    added = 0
    for p in cell:
        if added == 7:
            break
        if not free(p):
            continue
        t = NAME_TYPES[added]
        nodes.append({'id': f'{kid}_x{added + 1}', 'name': names[added], 'type': t, 'owner': None, 'home': kid,
                      'tier': 0, 'garrison': 0, 'defense': 0, 'morale': 10, 'devastation': 0, 'x': p[0], 'y': p[1]})
        added += 1
    assert added == 7, (kid, added)

# Dwarves: two more sealed rear nodes in the corner behind the capital.
dwc = caps['dw']
rear_ids = ['dw_mine', 'dw_farm']
for i, (name, t, dx, dy) in enumerate([('Mithril Deep', 'production', 330, 0), ('Stonebeard Granary', 'food', 420, -110)]):
    nid = f'dw_x{i + 1}'
    nodes.append({'id': nid, 'name': name, 'type': t, 'owner': None, 'home': 'dw', 'tier': 0, 'garrison': 0, 'defense': 0,
                  'morale': 10, 'devastation': 0, 'x': dwc['x'] + dx, 'y': dwc['y'] + dy})
    rear_ids.append(nid)
for nid in ['dw_mine', 'dw_farm']:
    n = next(n for n in nodes if n['id'] == nid)
    n['x'], n['y'] = dwc['x'] + {'dw_mine': (220, -200), 'dw_farm': (300, 170)}[nid][0], dwc['y'] + {'dw_mine': (220, -200), 'dw_farm': (300, 170)}[nid][1]

# Neutral land: border points roughly as far from two capitals, spread out.
NEUTRAL = [('Hollow Crown', 'castle'), ('Waystone Inn', 'town'), ('Sunfall Orchards', 'food'), ('Copperdeep', 'production'),
           ('Old Beacon', 'outpost'), ('Thornfield', 'food'), ('Greyhold Ruins', 'castle'), ('Saltlick Flats', 'production'),
           ('Fallow Moor', 'food'), ('Kingsroad Toll', 'town')]
capl = list(caps.values())


def border_score(p):
    ds = sorted(d(p, (c['x'], c['y'])) for c in capl)
    return ds[1] - ds[0]


near_dw = lambda p: d(p, (dwc['x'], dwc['y'])) < 420
IX0, IX1, IY0, IY1 = min(xs) + 80, max(xs) - 80, min(ys) + 80, max(ys) - 40
pool = [p for p in cands if not near_dw(p) and IX0 <= p[0] <= IX1 and IY0 <= p[1] <= IY1]
for name, t in NEUTRAL:
    ok = [p for p in pool if free(p, 20) and border_score(p) < 160]
    if not ok:
        ok = [p for p in pool if free(p, -25)]
    if not ok:
        break
    p = max(ok, key=lambda q: min(d(q, (n['x'], n['y'])) for n in nodes))
    nodes.append({'id': 'n_' + name.lower().replace(' ', '_').replace("'", ''), 'name': name, 'type': t, 'owner': None,
                  'home': None, 'tier': 0, 'garrison': 0, 'defense': 0, 'morale': 10, 'devastation': 0, 'x': p[0], 'y': p[1]})

# ---- Roads: shortest-first, no crossings, no road grazing another node, at most 5 roads per node ----
P = {n['id']: (n['x'], n['y']) for n in nodes}
P_TYPE = {n['id']: n['type'] for n in nodes}
edges = [['dw_cap', r] for r in rear_ids]
sealed = set(rear_ids)
deg = {n['id']: 0 for n in nodes}
for a, b in edges:
    deg[a] += 1
    deg[b] += 1


def ccw(a, b, c):
    return (c[1] - a[1]) * (b[0] - a[0]) - (b[1] - a[1]) * (c[0] - a[0])


def crosses(e1, e2):
    a, b = P[e1[0]], P[e1[1]]
    c, dd = P[e2[0]], P[e2[1]]
    if len({*e1, *e2}) < 4:
        return False
    return (ccw(a, b, c) * ccw(a, b, dd) < 0) and (ccw(c, dd, a) * ccw(c, dd, b) < 0)


def grazes(a, b):
    pa, pb = P[a], P[b]
    L = d(pa, pb)
    for nid, q in P.items():
        if nid in (a, b):
            continue
        t = max(0, min(1, ((q[0] - pa[0]) * (pb[0] - pa[0]) + (q[1] - pa[1]) * (pb[1] - pa[1])) / (L * L)))
        if d(q, (pa[0] + t * (pb[0] - pa[0]), pa[1] + t * (pb[1] - pa[1]))) < 55:
            return True
    return False


def ok_edge(a, b, maxdeg=5):
    if a in sealed or b in sealed:
        return False
    lim = lambda i: maxdeg + (3 if P_TYPE[i] == 'capital' else 0)
    if deg[a] >= lim(a) or deg[b] >= lim(b):
        return False
    e = [a, b]
    return not any(crosses(e, f) for f in edges) and not grazes(a, b)


ids = list(P)
pairs = sorted(((d(P[a], P[b]), a, b) for i, a in enumerate(ids) for b in ids[i + 1:]), key=lambda t: t[0])
for L, a, b in pairs:
    if L > 330:
        break
    if ok_edge(a, b):
        edges.append([a, b])
        deg[a] += 1
        deg[b] += 1


def components():
    adj = {i: set() for i in ids}
    for a, b in edges:
        adj[a].add(b)
        adj[b].add(a)
    seen, comps = set(), []
    for i in ids:
        if i in seen:
            continue
        stack, comp = [i], set()
        while stack:
            x = stack.pop()
            if x in comp:
                continue
            comp.add(x)
            stack.extend(adj[x] - comp)
        seen |= comp
        comps.append(comp)
    return comps


while len(comps := components()) > 1:
    main = max(comps, key=len)
    best = min(((d(P[a], P[b]), a, b) for a in main for b in ids if b not in main and ok_edge(a, b, 7)), default=None)
    assert best, 'cannot connect'
    edges.append([best[1], best[2]])
    deg[best[1]] += 1
    deg[best[2]] += 1

# Settled start: home nodes start owned with the per-type defaults; neutrals get a small garrison.
DEF = {'capital': [5, 30, 10], 'town': [2, 10, 2], 'castle': [1, 15, 6], 'production': [2, 5, 1], 'food': [1, 5, 1], 'outpost': [1, 5, 3]}
settled = []
for n in nodes:
    s = dict(n)
    if n['id'] in settled_src:
        o = settled_src[n['id']]
        s.update({k: o[k] for k in ('owner', 'tier', 'garrison', 'defense')})
    elif n['home']:
        t, g, df = DEF[n['type']]
        s.update({'owner': n['home'], 'tier': t, 'garrison': g, 'defense': df})
    else:
        s.update({'tier': 1, 'garrison': 8, 'defense': 1})
    settled.append(s)
for s in settled:
    if s['id'].startswith('dw_') and s['type'] != 'capital':
        s.update({'owner': 'dw', 'tier': 1, 'garrison': 0, 'defense': 1})

# Written as the mod's map data, one kingdom or node per line, where the Java and browser simulators both read it.
MAPS = os.path.join(HERE, '..', '..', '..', 'src', 'main', 'resources', 'data', 'ddkingdom', 'realm', 'sim', 'maps')


def write_map(file, name, map_nodes):
    def line(o):
        return json.dumps(o, ensure_ascii=False, separators=(',', ':'))
    sep = ',\n    '
    text = '{\n  "name": %s,\n  "kingdoms": [\n    %s\n  ],\n  "nodes": [\n    %s\n  ],\n  "edges": [%s]\n}\n' % (
        json.dumps(name), sep.join(line(k) for k in src['kingdoms']), sep.join(line(n) for n in map_nodes),
        ', '.join(line(e) for e in edges))
    with open(os.path.join(MAPS, file), 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


write_map('large_ruins.json', 'Large continent, %d nodes (ruins start)' % len(nodes), nodes)
write_map('large_settled.json', 'Large continent, %d nodes (settled start)' % len(nodes), settled)
print(len(nodes), 'nodes', len(edges), 'edges')
