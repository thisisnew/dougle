"""Style Archify's canonical SVG exports for Korean GitHub documentation.

Usage: python3 docs/diagrams/brand-export.py INPUT.svg OUTPUT.svg SPEC.json
Topology and coordinates come from Archify; this changes typography and colors.
PNG exports are rendered from the resulting SVG by the browser, not edited.
"""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS='http://www.w3.org/2000/svg'
ET.register_namespace('',NS)
def tag(name): return '{'+NS+'}'+name
source, output, spec = map(Path, sys.argv[1:])
root=ET.parse(source).getroot()
data=json.loads(spec.read_text())
w,h=map(float,root.get('viewBox').split()[2:])
# The canonical HTML retains original export provenance and bundled font notices.
# This derivative uses local Korean system fonts and keeps the exported notices.
for parent in list(root.iter()):
 for child in list(parent):
  if child.tag==tag('style') or child.get('fill')=='url(#grid)' or child.get('data-semantic-sigil'):
   parent.remove(child)
root.set('style','background:#fff')
root.set('viewBox',f'0 0 {w:g} {h+80:g}')
root.set('width',str(int(w)));root.set('height',str(int(h+80)))
css=ET.SubElement(root,tag('style'))
css.text='''text {font-family:"Apple SD Gothic Neo","Malgun Gothic",sans-serif; fill:#292f3e}
.c-mask {fill:white;stroke:none}.c-region {fill:#f8faff;stroke:#cad8ea;stroke-dasharray:5 5}
.c-backend,.c-external,.c-frontend,.c-security,.c-messagebus {fill:#fff;stroke:#aabbd2}
.c-database,.c-cloud {fill:#edf4ff;stroke:#2866c2}
.t-muted {fill:#56647a}.t-edge-default,.t-cloud {fill:#2866c2}
.a-default,.a-emphasis {fill:none;stroke:#2866c2}
marker polygon {fill:#2866c2}
'''
# New title and caption frame, separate from the authored graph.
g=ET.Element(tag('g'),{'transform':'translate(0 50)'})
for e in list(root):
 if e.tag not in {tag('style'),tag('defs'),tag('title'),tag('desc'),tag('metadata')}:
  root.remove(e)
  if e.tag==tag('rect') and e.get('x')=='0' and e.get('y')=='0': continue
  g.append(e)
root.append(ET.Element(tag('rect'),{'x':'0','y':'0','width':str(int(w)),'height':str(int(h+80)),'fill':'#fff'}))
root.append(g)
for parent in root.iter():
 for e in list(parent):
  if e.tag==tag('text'):
   if 'data-node-label' in e.attrib:
    e.set('font-size','18');e.set('y',str(float(e.get('y'))-4))
   elif e.get('class')=='t-muted':
    e.set('font-size','14');e.set('y',str(float(e.get('y'))+4))
   elif 'data-boundary-label' in e.attrib:e.set('font-size','15')
   else:e.set('font-size','14')
  if e.get('data-graph-role')=='automatic-crossover-underlay': e.set('stroke','#fff')
  if e.tag==tag('path') and 'var(' in e.get('fill',''):e.set('fill','#2866c2')
  if e.tag==tag('path') and e.get('marker-end'):e.set('stroke-width','1.8')
# Expand edge label masks to match the larger Korean text. Center on route.
paths={e.get('data-edge-id'):e for e in root.iter(tag('path')) if e.get('data-edge-id')}
for group in root.iter(tag('g')):
 texts=list(group.findall(tag('text')));rect=group.find(tag('rect'))
 if not texts or rect is None:continue
 t=texts[0]
 if group.get('data-edge-id'):
  path=paths[group.get('data-edge-id')]
  points=[tuple(map(float,p.split(','))) for p in path.get('data-composition-points').split(';')]
  if len(points)==2:
   (x1,y1),(x2,y2)=points
   t.set('x',str((x1+x2)/2));t.set('y',str((y1+y2)/2+(4 if x1==x2 else -10)))
   edge=next((e for e in data['connections'] if e.get('id')==group.get('data-edge-id')),None)
   if edge and y1==y2 and x1<x2 and any(e['from']==edge['to'] and e['to']==edge['from'] for e in data['connections']):
    t.set('y',str(y1+22))
  x,y=float(t.get('x')),float(t.get('y'))
  units=sum(1 if ord(c)>255 else .56 for c in (t.text or ''))
  width=units*14+16
  rect.set('x',str(x-width/2));rect.set('y',str(y-16));rect.set('width',str(width));rect.set('height','24')
 elif group.get('data-graph-role')=='structural-frame-label':
  rect.set('width',str(len(t.text or '')*15+20));rect.set('height','18')
# Title and a short scope caption are part of the GitHub figure.
title=ET.SubElement(root,tag('text'),{'x':'24','y':'30','font-size':('20' if spec.stem.endswith('mobile') else '25'),'font-weight':'700'})
title.text=data['meta']['title']
notes={
 'overview-mobile':'누구나 같은 방식으로 연결·공유합니다.',
 'overview':'각자 publish·fetch할 수 있습니다. Slack 봇은 실행 PC의 지식을 사용합니다.',
 'memory':'코드 동작은 구현을, 현재 상태는 해당 시스템을 직접 확인합니다.',
 'bot':'개발자에게 요청하면 대기 안내를 남기고, 같은 스레드에 답변을 이어갑니다.'}
notes['domain']='업무 지식과 Slack 문의는 저장 위치가 다르며, 공유 패키지는 선택한 지식만 담습니다.'
footer=ET.SubElement(root,tag('text'),{'x':'24','y':str(h+62),'font-size':'14','class':'t-muted'})
footer.text=(
 '구현 전 목표 구조입니다. 권한·실패·복구 조건은 함께 제공한 서버 명세를 따릅니다.'
 if 'TO-BE' in data['meta']['title'] else notes[spec.stem]
)
ET.ElementTree(root).write(output,encoding='utf-8',xml_declaration=True)
