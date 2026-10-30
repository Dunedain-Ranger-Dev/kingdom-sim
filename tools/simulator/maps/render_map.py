import json, os
HERE = os.path.dirname(os.path.abspath(__file__))
from PIL import Image, ImageDraw, ImageFont
m=json.load(open(os.path.join(HERE, 'large.json'),encoding='utf-8'))
col={k['id']:k['color'] for k in m['kingdoms']}
xs=[n['x'] for n in m['nodes']]; ys=[n['y'] for n in m['nodes']]
x0,y0=min(xs)-80,min(ys)-60; W=max(xs)-x0+130; H=max(ys)-y0+60
s=1600/W; img=Image.new('RGB',(1600,int(H*s)),'white'); d=ImageDraw.Draw(img)
P={n['id']:((n['x']-x0)*s,(n['y']-y0)*s) for n in m['nodes']}
for a,b in m['edges']: d.line([P[a],P[b]],fill='#bbbbbb',width=2)
f=ImageFont.truetype('arial.ttf',11)
sh={'capital':12,'town':8,'castle':8,'food':6,'production':6,'outpost':5}
for n in m['nodes']:
    x,y=P[n['id']]; r=sh[n['type']]; c=col.get(n['home'] or '', '#888888')
    if n['type']=='castle': d.rectangle([x-r,y-r,x+r,y+r],fill=c)
    elif n['type']=='outpost': d.polygon([(x,y-r-2),(x-r,y+r),(x+r,y+r)],fill=c)
    else: d.ellipse([x-r,y-r,x+r,y+r],fill=c,outline='black' if n['type']=='capital' else c)
    d.text((x+r+2,y-6),n['name'],fill='#333333',font=f)
img.save(os.path.join(HERE, 'large_map.png'))
