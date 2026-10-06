# -*- coding: utf-8 -*-
import os, re

INFO = {
    'c1': ('Концепт 1 — «Монумент»',
           'Золотые литые цифры 2160 стоят на двухступенчатом обсидиановом постаменте, как памятник. '
           'Слово PLAYER отлито золотом на цоколе. Позади — «корона» из семи прожекторов, бьющих из одной точки за монументом. '
           'Камера чуть снизу и слева: единая точка схода, свет сверху-слева, фаски на всех гранях.'),
    'c2': ('Концепт 2 — «Премьера 4K»',
           'Хромированные цифры с янтарными, будто раскалёнными торцами, в резком ракурсе три четверти — как гигантская '
           'декорация на съёмочной площадке. На лицевых гранях — тонкая пиксельная сетка (отсылка к 2160p), '
           'построенная в той же перспективе. Два прожектора бьют сверху, внизу — зеркальный пол и титр PLAYER.'),
    'c3': ('Концепт 3 — «Ар-деко»',
           'Высокие узкие цифры с каннелюрами (вертикальными желобками), как фасад кинотеатра 1930-х. '
           'Сильный ракурс снизу: вертикали сходятся вверх (трёхточечная перспектива), за монументом — '
           'лучевой «санбёрст». PLAYER — на ступенчатом цоколе-зиккурате.'),
}


def uniq(svg, tag):
    """make ids unique for each inline copy"""
    ids = set(re.findall(r'id="([^"]+)"', svg))
    for i in sorted(ids, key=len, reverse=True):
        svg = svg.replace('id="%s"' % i, 'id="%s-%s"' % (i, tag)).replace('url(#%s)' % i, 'url(#%s-%s)' % (i, tag))
    return svg


def sized(svg, w, h):
    return re.sub(r'width="\d+" height="\d+"', 'width="%d" height="%d"' % (w, h), svg, count=1)


def make(res, out):
    n = [0]
    def inst(svg, w, h):
        n[0] += 1
        return sized(uniq(svg, 'i%d' % n[0]), w, h)

    sec = []
    for key in ['c1', 'c2', 'c3']:
        if key not in res: continue
        r = res[key]; title, desc = INFO[key]
        icon = r['icon']
        sec.append('''
<section>
  <h2>%s</h2>
  <p class="desc">%s</p>
  <div class="hero">%s</div>
  <h3>Иконка приложения: 512 / 96 / 48 / 24 px</h3>
  <div class="row dark">
    <div class="cell">%s<span>512</span></div>
    <div class="cell">%s<span>96</span></div>
    <div class="cell">%s<span>48</span></div>
    <div class="cell">%s<span>24</span></div>
    <div class="cell"><div class="circle">%s</div><span>круглая маска лаунчера</span></div>
    <div class="cell"><div class="circle small">%s</div><span>круг 64</span></div>
  </div>
  <h3>Android TV баннер 320×180 и светлый фон</h3>
  <div class="row light">
    <div class="cell">%s<span>TV баннер 320×180</span></div>
    <div class="cell">%s<span>иконка 96 на светлом</span></div>
    <div class="cell">%s<span>48</span></div>
    <div class="cell"><div class="circle">%s</div><span>круг</span></div>
    <div class="cell wide">%s<span>герой, уменьшен</span></div>
  </div>
</section>''' % (title, desc, inst(r['hero'], 1600, 900),
                 inst(icon, 220, 220), inst(icon, 96, 96), inst(icon, 48, 48), inst(icon, 24, 24),
                 inst(icon, 150, 150), inst(icon, 64, 64),
                 inst(r['banner'], 320, 180), inst(icon, 96, 96), inst(icon, 48, 48), inst(icon, 150, 150),
                 inst(r['hero'], 320, 180)))

    html = '''<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><title>2160 Player — логотип v2</title>
<style>
body{margin:0;background:#0E0E10;color:#F2F2F2;font:15px/1.5 "Segoe UI",system-ui,sans-serif}
header{padding:28px 40px 6px}
h1{margin:0;font-size:26px;letter-spacing:.04em}
header p{color:#9a9aa2;margin:6px 0 0}
section{padding:26px 40px 34px;border-top:1px solid #222}
h2{margin:0 0 6px;color:#FFB020;font-size:22px}
h3{margin:22px 0 10px;font-size:14px;color:#aaa;font-weight:500;text-transform:uppercase;letter-spacing:.08em}
.desc{max-width:1100px;margin:0 0 16px;color:#d6d6da}
.hero svg{width:100%%;height:auto;display:block;border-radius:10px}
.row{display:flex;gap:26px;align-items:flex-end;flex-wrap:wrap;padding:22px;border-radius:12px}
.dark{background:#17171a}.light{background:#ECECEF;color:#333}
.cell{display:flex;flex-direction:column;align-items:center;gap:6px;font-size:12px;color:#888}
.circle{width:150px;height:150px;border-radius:50%%;overflow:hidden}
.circle.small{width:64px;height:64px}
.circle svg{width:100%%;height:100%%}
.wide svg{border-radius:8px}
</style></head><body>
<header><h1>2160 Player — логотип v2: кинематографичный монумент</h1>
<p>Три концепта. Всё построено из собственных геометрических контуров (без шрифтов) и единой 3D-камеры: одна точка схода, один источник света.</p></header>
%s
</body></html>''' % ''.join(sec)
    with open(os.path.join(out, 'preview.html'), 'w', encoding='utf-8') as f:
        f.write(html)
