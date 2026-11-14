import json, sys
from color import oklch, to_lch, contrast
# Today's green theme, kept byte-for-byte as the default.
SAGE = {'dark': dict(background='#050705', containerLow='#0a0d0a', container='#0c100c', containerHigh='#131913', containerHighest='#192019',
    hover='#202a20', text='#f0f4ed', muted='#bec9bb', subtle='#8e998b', outline='#879184', hairline='#2a332a', secondary='#b8ccb5',
    onSecondary='#243424', secondaryContainer='#334832', onSecondaryContainer='#d4e8d0', tertiary='#c6cbb8'),
  'light': dict(background='#f8faf5', containerLow='#ffffff', container='#ffffff', containerHigh='#eef2eb', containerHighest='#e7ede4',
    hover='#dde5da', text='#181d17', muted='#424940', subtle='#5f675d', outline='#727970', hairline='#d3dbd0', secondary='#536451',
    onSecondary='#ffffff', secondaryContainer='#d6e8d2', onSecondaryContainer='#111f11', tertiary='#5d6253')}
GREEN = {'dark': dict(primary='#78d47b', onPrimary='#09210b', primaryContainer='#173b1b', onPrimaryContainer='#b9f3b9'),
         'light': dict(primary='#2e7034', onPrimary='#ffffff', primaryContainer='#b9f3b9', onPrimaryContainer='#08210a')}
# (id, name, hue, chroma scale): the scale multiplies Sage's tint chroma at the same lightness.
SURFACES = [('sage','Sage',145,1.0,0), ('neutral','Neutral',0,0.0,0), ('slate','Slate',255,1.35,.03), ('stone','Stone',75,1.15,.025),
    ('ocean','Ocean',215,1.7,.04), ('midnight','Midnight',268,2.3,.055), ('plum','Plum',322,1.7,.045), ('mocha','Mocha',55,1.8,.04)]
# The last value lifts dark surfaces (not text) so a tinted background reads as its color, not as black.
# (id, name, hue, dark chroma, light chroma)
ACCENTS = [('green','Green',145,0,0), ('lime','Lime',128,.165,.13), ('gold','Gold',96,.145,.115), ('amber','Amber',72,.155,.13),
    ('orange','Orange',50,.165,.15), ('red','Red',27,.17,.17), ('rose','Rose',8,.15,.15), ('pink','Pink',350,.15,.14),
    ('magenta','Magenta',330,.17,.16), ('purple','Purple',310,.155,.15), ('violet','Violet',292,.155,.16), ('indigo','Indigo',275,.14,.16),
    ('blue','Blue',255,.13,.15), ('sky','Sky',232,.115,.11), ('teal','Teal',190,.105,.09), ('graphite','Graphite',250,.012,.012)]

def surface(hue, k, lift, mode):
    out = {}
    for key, ref in SAGE[mode].items():
        L, C, _ = to_lch(ref)
        cap = .06 if key.startswith(('secondary','onSecondary')) else .045
        h = (hue - 25) % 360 if key == 'tertiary' else hue
        if mode == 'dark' and L < .4 and not key.startswith(('onSecondary', 'secondaryContainer')): L += lift * (1 - (L - .124) / .3)
        out[key] = ref if ref == '#ffffff' else oklch(L, min(C * k * (1 + lift * 12 if mode == 'dark' and L < .45 else 1), cap), h)
    return out

def accent(hue, cd, cl, surfaces):
    def worst(color, mode, keys): return min(contrast(color, s[mode][k]) for s in surfaces for k in keys)
    # Dark: the darkest primary that still reads on every dark surface. Light: the lightest that reads on every light surface.
    dark = next(oklch(L/1000, cd, hue) for L in range(700, 900) if worst(oklch(L/1000, cd, hue), 'dark', ['background','container','containerHigh']) >= 6.0
                and worst(oklch(L/1000, cd, hue), 'dark', ['containerHighest','hover']) >= 4.5)
    light = next(oklch(L/1000, cl, hue) for L in range(560, 300, -1) if worst(oklch(L/1000, cl, hue), 'light', ['background','container','containerHigh']) >= 5.0
                 and worst(oklch(L/1000, cl, hue), 'light', ['containerHighest','hover']) >= 4.5 and contrast(oklch(L/1000, cl, hue), '#ffffff') >= 5.0)
    return {'dark': dict(primary=dark, onPrimary=oklch(.22, min(cd*.34, .055), hue), primaryContainer=oklch(.316, min(cd*.46, .072), hue),
                         onPrimaryContainer=oklch(.91, min(cd*.6, .095), hue)),
            'light': dict(primary=light, onPrimary='#ffffff', primaryContainer=oklch(.91, min(cl*.75, .095), hue),
                          onPrimaryContainer=oklch(.221, min(cl*.45, .055), hue))}

surfaces = []
for sid, name, hue, k, lift in SURFACES:
    s = {'id': sid, 'name': name} | ({'dark': SAGE['dark'], 'light': SAGE['light']} if sid == 'sage'
        else {'dark': surface(hue, k, lift, 'dark'), 'light': surface(hue, k, lift, 'light')})
    s['swatch'] = s['dark']['containerHighest'] if False else oklch(.62, min(.0167 * k * 2.2, .07), hue)
    surfaces.append(s)
accents = []
for aid, name, hue, cd, cl in ACCENTS:
    a = {'id': aid, 'name': name} | (GREEN if aid == 'green' else accent(hue, cd, cl, surfaces))
    a['swatch'] = '#3f9a47' if aid == 'green' else oklch(.6, max(cd, cl), hue)
    accents.append(a)
json.dump({'format': 'nuvori-appearance', 'version': 1, 'defaults': {'accent': 'green', 'surface': 'sage'},
           'accents': accents, 'surfaces': surfaces}, open(sys.argv[1], 'w', newline='\n'), indent=2)
