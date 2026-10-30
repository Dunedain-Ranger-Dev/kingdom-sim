import os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
d='web'
s=open(f'{d}/index.html',encoding='utf-8').read()
for f in ['scenario.js','continent.js','continent-large.js','engine.js']:
    js=open(f'{d}/{f}',encoding='utf-8').read()
    s=s.replace(f'<script src="{f}"></script>', '<script>\n'+js+'\n</script>')
assert 'src="engine.js"' not in s
open('kingdom-simulator.html','w',encoding='utf-8').write(s)
print(len(s))
