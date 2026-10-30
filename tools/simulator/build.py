"""Builds kingdom-simulator.html: the page with the engine and the mod's rules and maps inlined, one file to open or share."""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'data', 'ddkingdom', 'realm', 'sim')


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


data = {'rules': json.loads(read(os.path.join(DATA, 'rules.json'))), 'maps': {}}
for name in sorted(os.listdir(os.path.join(DATA, 'maps'))):
    if name.endswith('.json'):
        data['maps'][name[:-5]] = json.loads(read(os.path.join(DATA, 'maps', name)))
# "</" is escaped so no name in the data can close the script tag early.
data_js = 'window.KINGDOM_DATA = ' + json.dumps(data, ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/') + ';'

page = read(os.path.join(HERE, 'web', 'index.html'))
for tag, js in (('<script src="data.js"></script>', data_js), ('<script src="engine.js"></script>', read(os.path.join(HERE, 'web', 'engine.js')))):
    assert page.count(tag) == 1, tag
    page = page.replace(tag, '<script>\n' + js + '\n</script>')
with open(os.path.join(HERE, 'kingdom-simulator.html'), 'w', encoding='utf-8', newline='\n') as f:
    f.write(page)
print(len(page), 'bytes')
