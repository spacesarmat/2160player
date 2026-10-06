# -*- coding: utf-8 -*-
"""render preview.html (or any file) with headless Chrome -> PNG"""
import subprocess, sys, os
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
HERE = os.path.dirname(os.path.abspath(__file__))


def shot(src, out, w, h, scale=1, transparent=False):
    args = [CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files',
            '--force-device-scale-factor=%s' % scale, '--screenshot=' + out, '--window-size=%d,%d' % (w, h)]
    if transparent: args.append('--default-background-color=00000000')
    args.append('file:///' + src.replace('\\', '/'))
    subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)


if __name__ == '__main__':
    os.makedirs(os.path.join(HERE, '_shots'), exist_ok=True)
    h = int(sys.argv[1]) if len(sys.argv) > 1 else 3600
    out = os.path.join(HERE, '_shots', 'preview.png')
    shot(os.path.join(HERE, 'preview.html'), out, 1400, h)
    from PIL import Image
    im = Image.open(out)
    step = 1100
    for i in range(0, im.size[1], step):
        im.crop((0, i, 1400, min(im.size[1], i + step))).save(os.path.join(HERE, '_shots', 'part%d.png' % (i // step)))
    print(im.size)
