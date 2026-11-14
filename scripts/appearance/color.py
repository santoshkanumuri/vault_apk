import math
def srgb_to_lin(c): return c/12.92 if c<=0.04045 else ((c+0.055)/1.055)**2.4
def lin_to_srgb(c): return 12.92*c if c<=0.0031308 else 1.055*c**(1/2.4)-0.055
def hex_to_rgb(h): h=h.lstrip('#'); return tuple(int(h[i:i+2],16)/255 for i in (0,2,4))
def rgb_to_hex(r): return '#'+''.join('%02x'%max(0,min(255,round(c*255))) for c in r)
def rgb_to_oklab(rgb):
    r,g,b=[srgb_to_lin(c) for c in rgb]
    l=0.4122214708*r+0.5363325363*g+0.0514459929*b
    m=0.2119034982*r+0.6806995451*g+0.1073969566*b
    s=0.0883024619*r+0.2817188376*g+0.6299787005*b
    l,m,s=[math.copysign(abs(x)**(1/3),x) for x in (l,m,s)]
    return (0.2104542553*l+0.7936177850*m-0.0040720468*s, 1.9779984951*l-2.4285922050*m+0.4505937099*s, 0.0259040371*l+0.7827717662*m-0.8086757660*s)
def oklab_to_lin(L,a,b):
    l=(L+0.3963377774*a+0.2158037573*b)**3; m=(L-0.1055613458*a-0.0638541728*b)**3; s=(L-0.0894841775*a-1.2914855480*b)**3
    return (4.0767416621*l-3.3077115913*m+0.2309699292*s, -1.2684380046*l+2.6097574011*m-0.3413193965*s, -0.0041960863*l-0.7034186147*m+1.7076147010*s)
def oklch(L,C,h):
    """Gamut-mapped: keep L and h, reduce C until it fits sRGB."""
    for _ in range(60):
        a=C*math.cos(math.radians(h)); b=C*math.sin(math.radians(h))
        lin=oklab_to_lin(L,a,b)
        if all(-1e-4<=c<=1+1e-4 for c in lin): return rgb_to_hex([lin_to_srgb(min(1,max(0,c))) for c in lin])
        C*=0.95
    return rgb_to_hex([lin_to_srgb(min(1,max(0,c))) for c in lin])
def to_lch(hexv):
    L,a,b=rgb_to_oklab(hex_to_rgb(hexv)); return L, math.hypot(a,b), (math.degrees(math.atan2(b,a))%360)
def lum(hexv): r,g,b=[srgb_to_lin(c) for c in hex_to_rgb(hexv)]; return 0.2126*r+0.7152*g+0.0722*b
def contrast(x,y): a,b=sorted((lum(x),lum(y)),reverse=True); return (a+0.05)/(b+0.05)
