"""Builds the Pentago board, blocks, marbles, trays, arrow handles, rims and gaze markers as GLB, in Python + Pillow
(no Blender on this machine; design assets: 框 / 转盘 / 凹坑 / 碟全程序化, gate B "棋具外观 推荐默认：全程序化不用 Tripo").

  python3 tools/build_models.py

Writes app/src/main/assets/models/. Numbers match domain/Layout.kt (design gameSpec.board / theme.boardVsPieces):
  frame      maple 0.50 x 0.50 x 0.035 m, origin on its top centre, four 0.205 m pockets 12 mm deep where the blocks sit
  quad       one walnut block 0.20 x 0.02 x 0.20 m, corners R 2 cm, 3 mm top chamfer, nine spherical dimples (dia 4.4 cm,
             1.0 cm deep, a darker floor); origin on its top centre (it stands 8 mm proud of the frame)
  marble_*   sphere dia 4.0 cm (16 x 10 = 320 triangles), origin at its centre: obsidian black (glossy) / pearl white
  tray       walnut tray 0.27 x 0.14 x 0.022 m for 3 x 6 marbles, origin on its floor centre (bottom)
  handle_*   curved teal arrows (6 x 4 cm footprint, 4 mm thick, emissive), concentric with their block; origin at the
             handle centre (Layout.handle): l/r = the block's outer side, cw / ccw = the turn they make
  rim        a 3 mm amber band just inside the block's top edge (S07 "该转了" glow), origin like quad
  marker     the gaze-target dot: 5 mm, 1.5 mm thick, a half shade darker than walnut (board-input template, <= 5 mm)
All materials OPAQUE (profile D24 / B12).
"""
import io
import json
import math
import os
import random
import struct

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")

MAPLE = (0xD9, 0xB8, 0x8A)
WALNUT = (0x8A, 0x5E, 0x3B)   # design #6E4A2B rendered at L* 28 vs the black marble 8 (ΔL* 20 < 25): lifted to render ~35
WALNUT_FLOOR = (0x55, 0x37, 0x20)
MARKER = (0x5E, 0x3F, 0x24)
OBSIDIAN = (0x14, 0x14, 0x18)
PEARL = (0xF2, 0xEE, 0xE6)
TEAL = (0x2F, 0xA6, 0x9A)
AMBER = (0xD9, 0x9A, 0x2B)

PITCH = 0.06
FRAME, FRAME_T = 0.50, 0.035
QUAD, QUAD_T, PROUD, QUAD_OFF = 0.20, 0.02, 0.008, 0.105
POCKET = 0.205
DIMPLE_A, DIMPLE_D = 0.022, 0.010
CORNER_R, CHAMFER = 0.02, 0.003
MARBLE_R = 0.020
TRAY_W, TRAY_D, TRAY_H, TRAY_FLOOR, TRAY_WALL = 0.27, 0.14, 0.022, 0.006, 0.012
HANDLE_R, HANDLE_BAND, HANDLE_T = 0.13, 0.010, 0.004


def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "abalone-push tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    """Positions / normals / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def add(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]
        self.nor += [tuple(n) for n in norms]
        self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def tri(self, mat, a, b, c, n=None, uv=(0.5, 0.5)):
        if n is None:
            u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            l = math.sqrt(sum(x * x for x in n)) or 1.0
            n = tuple(x / l for x in n)
        self.add(mat, [a, b, c], [n, n, n], [uv, uv, uv], [0, 1, 2])

    def face(self, mat, verts, n, uvs=None):
        """A convex polygon (fan) wound counter-clockwise around its normal [n] (front face = the side n points to)."""
        v = [tuple(p) for p in verts]
        a, b, c = v[0], v[1], v[2]
        u = [b[k] - a[k] for k in range(3)]; w = [c[k] - a[k] for k in range(3)]
        cr = (u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
        uvs = list(uvs) if uvs else [(0.5, 0.5)] * len(v)
        if sum(cr[k] * n[k] for k in range(3)) < 0:
            v = v[::-1]; uvs = uvs[::-1]
        idx = []
        for i in range(1, len(v) - 1):
            idx += [0, i, i + 1]
        self.add(mat, v, [tuple(n)] * len(v), uvs, idx)

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/png"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-20s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))
        return tris


def mat(name, color=None, rough=0.6, tex=None, metal=0.0, emissive=None):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    m = {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}
    if emissive is not None:
        m["emissiveFactor"] = [srgb_lin(c) * emissive for c in (color or (255, 255, 255))]
    return m


def mat(name, color=None, rough=0.6, tex=None, metal=0.0, emissive=None):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    m = {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}
    if emissive is not None:
        m["emissiveFactor"] = [srgb_lin(c) * emissive for c in (color or (255, 255, 255))]
    return m


def wood_texture(base, size=1024, seed=1, lines=160, amp=(2, 9), shade=(-22, 14)):
    rnd = random.Random(seed)
    im = Image.new("RGB", (size, size), base)
    dr = ImageDraw.Draw(im)
    for i in range(lines):
        y0 = rnd.uniform(0, size)
        a = rnd.uniform(*amp)
        freq = rnd.uniform(1, 4)
        sh = rnd.randint(*shade)
        col = tuple(max(0, min(255, c + sh)) for c in base)
        pts = [(x, y0 + a * math.sin(2 * math.pi * freq * x / size + i)) for x in range(0, size + 8, 8)]
        dr.line(pts, fill=col, width=rnd.randint(1, 3))
    return im.filter(ImageFilter.GaussianBlur(1.2))


# ---------------------------------------------------------------- frame (maple)

def box_faces(m, mi, x0, x1, y0, y1, z0, z1, uvk=4.0, skip=()):
    """An axis-aligned box's faces (outward), UV by world position so the grain runs on."""
    def uv(a, b):
        return (a * uvk, b * uvk)
    faces = {
        "top": ([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0), lambda p: uv(p[0], p[2])),
        "bottom": ([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0), lambda p: uv(p[0], p[2])),
        "front": ([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1), lambda p: uv(p[0], p[1])),
        "back": ([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (0, 0, -1), lambda p: uv(p[0], p[1])),
        "left": ([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1, 0, 0), lambda p: uv(p[2], p[1])),
        "right": ([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (1, 0, 0), lambda p: uv(p[2], p[1])),
    }
    for k, (v, n, f) in faces.items():
        if k in skip:
            continue
        m.face(mi, v, n, [f(p) for p in v])


def build_frame():
    m = Mesh()
    h = FRAME / 2
    d = 0.012                       # pocket depth (the block bottom sits at -0.012)
    pk = [(-QUAD_OFF - POCKET / 2, -QUAD_OFF + POCKET / 2), (QUAD_OFF - POCKET / 2, QUAD_OFF + POCKET / 2)]
    xs = [-h, pk[0][0], pk[0][1], pk[1][0], pk[1][1], h]
    # top face: a 5 x 5 grid of rectangles minus the four pocket cells; pockets: floor + four walls
    for i in range(5):
        for j in range(5):
            x0, x1, z0, z1 = xs[i], xs[i + 1], xs[j], xs[j + 1]
            pocket = i in (1, 3) and j in (1, 3)
            y = -d if pocket else 0.0
            v = [(x0, y, z0), (x1, y, z0), (x1, y, z1), (x0, y, z1)]
            m.face(0, v, (0, 1, 0), [(p[0] * 4, p[2] * 4) for p in v])
            if pocket:
                for (a, b, n) in (((x0, z0), (x1, z0), (0, 0, 1)), ((x1, z1), (x0, z1), (0, 0, -1)), ((x0, z1), (x0, z0), (1, 0, 0)), ((x1, z0), (x1, z1), (-1, 0, 0))):
                    w = [(a[0], -d, a[1]), (b[0], -d, b[1]), (b[0], 0.0, b[1]), (a[0], 0.0, a[1])]
                    m.face(0, w, n, [((p[0] + p[2]) * 4, p[1] * 4) for p in w])
    # 2 mm chamfer round the outer top edge, then the sides and the bottom
    c = 0.002
    for (n, a, b) in (((0, 0.7, 0.7), (-h, h), None),):
        pass
    sides = [((-h, h), (h, h), (0, 0, 1)), ((h, -h), (-h, -h), (0, 0, -1)), ((-h, -h), (-h, h), (-1, 0, 0)), ((h, h), (h, -h), (1, 0, 0))]
    for (a, b, n) in sides:
        w = [(a[0], -FRAME_T, a[1]), (b[0], -FRAME_T, b[1]), (b[0], -c, b[1]), (a[0], -c, a[1])]
        m.face(0, w, n, [((p[0] + p[2]) * 4, p[1] * 4) for p in w])
        # chamfer strip from (side, -c) to (side inset c, 0)
        ia = (a[0] - n[0] * c, a[1] - n[2] * c); ib = (b[0] - n[0] * c, b[1] - n[2] * c)
        w = [(a[0], -c, a[1]), (b[0], -c, b[1]), (ib[0], 0.0, ib[1]), (ia[0], 0.0, ia[1])]
        nn = (n[0] * 0.707, 0.707, n[2] * 0.707)
        m.face(0, w, nn, [((p[0] + p[2]) * 4, 0.0) for p in w])
    m.face(0, [(-h, -FRAME_T, -h), (h, -FRAME_T, -h), (h, -FRAME_T, h), (-h, -FRAME_T, h)], (0, -1, 0))
    return m.write("frame", [mat("Maple", tex=0, rough=0.55)], [wood_texture(MAPLE, seed=7, lines=130, shade=(-18, 10))])


# ---------------------------------------------------------------- block (walnut, nine dimples)

DIMPLE_RS = (DIMPLE_A ** 2 + DIMPLE_D ** 2) / (2 * DIMPLE_D)
CENTRES = [(-PITCH, 0.0, PITCH)]


def rr_dist(x, z, half=QUAD / 2, r=CORNER_R):
    """Signed distance inside the rounded square (positive inside)."""
    qx, qz = abs(x) - (half - r), abs(z) - (half - r)
    ox, oz = max(qx, 0.0), max(qz, 0.0)
    outside = math.hypot(ox, oz) + min(max(qx, qz), 0.0) - r
    return -outside


def clamp_rr(x, z, half=QUAD / 2, r=CORNER_R):
    """Project a grid point outside the rounded square onto its outline."""
    if rr_dist(x, z, half, r) >= 0:
        return x, z
    cx = math.copysign(min(abs(x), half - r), x); cz = math.copysign(min(abs(z), half - r), z)
    if abs(x) > half - r and abs(z) > half - r:
        dx, dz = x - cx, z - cz; l = math.hypot(dx, dz) or 1.0
        return cx + dx / l * r, cz + dz / l * r
    return max(-half, min(half, x)), max(-half, min(half, z))


def top_height(x, z):
    y = 0.0
    for cx in (-PITCH, 0.0, PITCH):
        for cz in (-PITCH, 0.0, PITCH):
            d = math.hypot(x - cx, z - cz)
            if d < DIMPLE_A:
                y = min(y, (DIMPLE_RS - DIMPLE_D) - math.sqrt(DIMPLE_RS ** 2 - d * d))
    e = rr_dist(x, z)
    if e < CHAMFER:
        y = min(y, -(CHAMFER - max(e, 0.0)))
    return y


def top_normal(x, z):
    e = 0.0003
    hx = (top_height(x + e, z) - top_height(x - e, z)) / (2 * e)
    hz = (top_height(x, z + e) - top_height(x, z - e)) / (2 * e)
    n = (-hx, 1.0, -hz); l = math.sqrt(sum(v * v for v in n))
    return tuple(v / l for v in n)


def axis_coords():
    """Denser where the dimples are (3.2 mm), coarser between them (about 6 mm)."""
    pts = set()
    half = QUAD / 2
    for c in (-PITCH, 0.0, PITCH):
        a, b = c - DIMPLE_A - 0.002, c + DIMPLE_A + 0.002
        n = 15
        for i in range(n + 1):
            pts.add(round(a + (b - a) * i / n, 6))
    for v in (-half, -half + CHAMFER, half - CHAMFER, half, -0.088, 0.088, -0.03, 0.03):
        pts.add(round(v, 6))
    out = sorted(p for p in pts if -half <= p <= half)
    return out


def quad_texture(size=1024):
    """Walnut grain with the nine dimple floors shaded darker (AO, design 坑底暗影)."""
    im = wood_texture(WALNUT, size=size, seed=3, lines=170, amp=(1, 4), shade=(-20, 12))
    dr = ImageDraw.Draw(im)
    ppm = size / QUAD
    for cx in (-PITCH, 0.0, PITCH):
        for cz in (-PITCH, 0.0, PITCH):
            x, y = (cx + QUAD / 2) * ppm, (cz + QUAD / 2) * ppm
            steps = 18
            for i in range(steps):
                t = i / (steps - 1)
                rr = DIMPLE_A * ppm * (1.0 - t * 0.9)
                k = t ** 0.7
                col = tuple(int(WALNUT[j] + (WALNUT_FLOOR[j] - WALNUT[j]) * k) for j in range(3))
                dr.ellipse((x - rr, y - rr, x + rr, y + rr), fill=col)
    return im.filter(ImageFilter.GaussianBlur(2))


def build_quad():
    m = Mesh()
    xs = axis_coords()
    verts, norms, uvs, idx = [], [], [], []
    index = {}
    for i, x in enumerate(xs):
        for j, z in enumerate(xs):
            px, pz = clamp_rr(x, z)
            index[(i, j)] = len(verts)
            verts.append((px, top_height(px, pz), pz))
            norms.append(top_normal(px, pz))
            uvs.append(((px + QUAD / 2) / QUAD, (pz + QUAD / 2) / QUAD))
    n = len(xs)
    for i in range(n - 1):
        for j in range(n - 1):
            a, b, c, d = index[(i, j)], index[(i + 1, j)], index[(i + 1, j + 1)], index[(i, j + 1)]
            idx += [a, c, b, a, d, c]   # counter-clockwise seen from +Y (x right, z toward the viewer)
    m.add(0, verts, norms, uvs, idx)
    # sides: the rounded outline from the chamfer foot down to the bottom
    seg = 96
    outline = []
    half = QUAD / 2 - CORNER_R
    for k in range(4):
        cx = half if k in (0, 1) else -half
        cz = half if k in (1, 2) else -half
        a0 = -math.pi / 2 + k * math.pi / 2
        for s in range(seg // 4 + 1):
            a = a0 + (math.pi / 2) * s / (seg // 4)
            outline.append((cx + CORNER_R * math.cos(a), cz + CORNER_R * math.sin(a), math.cos(a), math.sin(a)))
    for k in range(len(outline)):
        p, q = outline[k], outline[(k + 1) % len(outline)]
        if abs(p[0] - q[0]) < 1e-9 and abs(p[1] - q[1]) < 1e-9:
            continue
        v = [(p[0], -QUAD_T, p[1]), (q[0], -QUAD_T, q[1]), (q[0], -CHAMFER, q[1]), (p[0], -CHAMFER, p[1])]
        nn = [(p[2], 0, p[3]), (q[2], 0, q[3]), (q[2], 0, q[3]), (p[2], 0, p[3])]
        u0, u1 = k / len(outline) * 4, (k + 1) / len(outline) * 4
        base = len(m.pos)
        m.add(0, v, nn, [(u0, 0), (u1, 0), (u1, 0.1), (u0, 0.1)], [0, 2, 1, 0, 3, 2] if (q[0] - p[0]) * p[3] - (q[1] - p[1]) * p[2] < 0 else [0, 1, 2, 0, 2, 3])
    hq = QUAD / 2
    m.face(0, [(-hq, -QUAD_T, -hq), (hq, -QUAD_T, -hq), (hq, -QUAD_T, hq), (-hq, -QUAD_T, hq)], (0, -1, 0))
    return m.write("quad", [mat("Walnut", tex=0, rough=0.5)], [quad_texture()])


def build_rim():
    """A 3 mm band 1 mm inside the top edge, 0.4 mm over the top (emissive amber): the "该转了" glow of S07."""
    m = Mesh()
    seg = 24
    def ring(inset):
        out = []
        half = QUAD / 2 - CORNER_R
        r = CORNER_R - inset
        for k in range(4):
            cx = half if k in (0, 1) else -half
            cz = half if k in (1, 2) else -half
            a0 = -math.pi / 2 + k * math.pi / 2
            for s in range(seg + 1):
                a = a0 + (math.pi / 2) * s / seg
                out.append((cx + r * math.cos(a), cz + r * math.sin(a)))
        return out
    outer, inner = ring(CHAMFER + 0.0005), ring(CHAMFER + 0.0035)
    y = 0.0004
    for k in range(len(outer)):
        a, b = outer[k], outer[(k + 1) % len(outer)]
        c, d = inner[(k + 1) % len(inner)], inner[k]
        m.face(0, [(a[0], y, a[1]), (b[0], y, b[1]), (c[0], y, c[1]), (d[0], y, d[1])], (0, 1, 0))
    return m.write("rim", [mat("Rim", color=AMBER, rough=0.4, emissive=0.9)])


# ---------------------------------------------------------------- marbles

def swirl_texture(base, swirl, seed):
    w, h = 256, 128
    im = Image.new("RGB", (w, h), base)
    dr = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    for band in range(2):
        ph = rnd.uniform(0, 2 * math.pi)
        a = rnd.uniform(15, 30)
        y0 = h * (0.35 + 0.3 * band)
        pts = [(x, y0 + a * math.sin(2 * math.pi * x / w + ph)) for x in range(0, w + 4, 4)]
        dr.line(pts, fill=swirl, width=rnd.randint(5, 9))
    return im.filter(ImageFilter.GaussianBlur(4))


def build_marble(name, base, swirl, seed, rough):
    m = Mesh()
    SEG, RINGS = 16, 10
    verts, norms, uvs, idx = [], [], [], []
    for i in range(RINGS + 1):
        th = math.pi * i / RINGS
        for j in range(SEG + 1):
            ph = 2 * math.pi * j / SEG
            n = (math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph))
            verts.append(tuple(MARBLE_R * c for c in n)); norms.append(n); uvs.append((j / SEG, i / RINGS))
    for i in range(RINGS):
        for j in range(SEG):
            a = i * (SEG + 1) + j; b = a + SEG + 1
            if i > 0:
                idx += [a, a + 1, b]
            if i < RINGS - 1:
                idx += [a + 1, b + 1, b]
    m.add(0, verts, norms, uvs, idx)
    return m.write(name, [mat(name, tex=0, rough=rough)], [swirl_texture(base, swirl, seed)])


# ---------------------------------------------------------------- tray

def build_tray():
    m = Mesh()
    hw, hd = TRAY_W / 2, TRAY_D / 2
    iw, idd = hw - TRAY_WALL, hd - TRAY_WALL
    # floor (inside), rim top, inner walls, outer walls, bottom
    box_faces(m, 0, -iw, iw, 0.0, TRAY_FLOOR, -idd, idd, skip=("bottom", "front", "back", "left", "right"))
    # rim top: four strips
    for (x0, x1, z0, z1) in ((-hw, hw, idd, hd), (-hw, hw, -hd, -idd), (-hw, -iw, -idd, idd), (iw, hw, -idd, idd)):
        v = [(x0, TRAY_H, z0), (x1, TRAY_H, z0), (x1, TRAY_H, z1), (x0, TRAY_H, z1)]
        m.face(0, v, (0, 1, 0), [(p[0] * 4, p[2] * 4) for p in v])
    for (a, b, n) in (((-iw, idd), (iw, idd), (0, 0, -1)), ((iw, -idd), (-iw, -idd), (0, 0, 1)), ((-iw, -idd), (-iw, idd), (1, 0, 0)), ((iw, idd), (iw, -idd), (-1, 0, 0))):
        w = [(a[0], TRAY_FLOOR, a[1]), (b[0], TRAY_FLOOR, b[1]), (b[0], TRAY_H, b[1]), (a[0], TRAY_H, a[1])]
        m.face(0, w, n, [((p[0] + p[2]) * 4, p[1] * 4) for p in w])
    box_faces(m, 0, -hw, hw, 0.0, TRAY_H, -hd, hd, skip=("top",))
    return m.write("tray", [mat("Walnut", tex=0, rough=0.5)], [wood_texture(WALNUT, seed=9, lines=150, amp=(1, 5))])


# ---------------------------------------------------------------- arrow handles

HANDLE_CX = QUAD / 2 + 0.01 + 0.02     # Layout.handle: outer edge + gap + half width
HANDLE_CZ = 0.06 / 2 + 0.005


def build_handle(side, dir_):
    """A curved arrow concentric with its block (radius 13 cm), 25° long, 1 cm band, arrowhead in the turn's direction.
    In (x, z) block coordinates an angle phi gives (r cos phi, r sin phi); counter-clockwise from above = phi decreasing."""
    m = Mesh()
    centre_phi = 180.0 if side == "l" else 0.0
    far = dir_ == "ccw"
    sign = -1 if far else 1                        # far half: z < 0
    a0, a1 = (centre_phi - sign * 3, centre_phi - sign * 28) if side == "l" else (centre_phi + sign * 3, centre_phi + sign * 28)
    lo, hi = min(a0, a1), max(a0, a1)
    head_at_lo = dir_ == "ccw"
    hx = (-1 if side == "l" else 1) * HANDLE_CX
    hz = (-1 if far else 1) * HANDLE_CZ
    T = HANDLE_T

    def P(phi, r):
        a = math.radians(phi)
        return (r * math.cos(a) - hx, r * math.sin(a) - hz)

    seg = 14
    head = 8.0                                     # degrees of arrowhead
    body_lo, body_hi = (lo + head, hi) if head_at_lo else (lo, hi - head)
    rin, rout = HANDLE_R - HANDLE_BAND / 2, HANDLE_R + HANDLE_BAND / 2
    pts = []
    for k in range(seg + 1):
        phi = body_lo + (body_hi - body_lo) * k / seg
        pts.append((P(phi, rin), P(phi, rout)))
    for k in range(seg):
        (i0, o0), (i1, o1) = pts[k], pts[k + 1]
        m.face(0, [(i0[0], T, i0[1]), (o0[0], T, o0[1]), (o1[0], T, o1[1]), (i1[0], T, i1[1])], (0, 1, 0))
        m.face(0, [(i0[0], 0, i0[1]), (o0[0], 0, o0[1]), (o1[0], 0, o1[1]), (i1[0], 0, i1[1])], (0, -1, 0))
        for (a, b) in ((i0, i1), (o0, o1)):
            mx, mz = (a[0] + b[0]) / 2 + hx, (a[1] + b[1]) / 2 + hz
            l = math.hypot(mx, mz)
            nn = (mx / l, 0, mz / l) if (a is o0) else (-mx / l, 0, -mz / l)
            m.face(0, [(a[0], 0, a[1]), (b[0], 0, b[1]), (b[0], T, b[1]), (a[0], T, a[1])], nn)
    # arrowhead: a triangle wider than the band
    base_phi = body_lo if head_at_lo else body_hi
    tip_phi = lo if head_at_lo else hi
    w = HANDLE_BAND * 1.1
    b1, b2, tip = P(base_phi, HANDLE_R - w), P(base_phi, HANDLE_R + w), P(tip_phi, HANDLE_R)
    for y, n in ((T, (0, 1, 0)), (0, (0, -1, 0))):
        m.face(0, [(b1[0], y, b1[1]), (b2[0], y, b2[1]), (tip[0], y, tip[1])], n)
    for (a, b) in ((b1, b2), (b2, tip), (tip, b1)):
        dx, dz = b[0] - a[0], b[1] - a[1]; l = math.hypot(dx, dz) or 1
        m.face(0, [(a[0], 0, a[1]), (b[0], 0, b[1]), (b[0], T, b[1]), (a[0], T, a[1])], (dz / l, 0, -dx / l))
    return m.write("handle_%s_%s" % (side, dir_), [mat("Handle", color=TEAL, rough=0.35, emissive=0.85)])


def build_marker():
    m = Mesh()
    seg, r, t = 16, 0.0025, 0.0015
    for k in range(seg):
        a0, a1 = 2 * math.pi * k / seg, 2 * math.pi * (k + 1) / seg
        p0, p1 = (r * math.cos(a0), r * math.sin(a0)), (r * math.cos(a1), r * math.sin(a1))
        m.face(0, [(0, t, 0), (p1[0], t, p1[1]), (p0[0], t, p0[1])], (0, 1, 0))
        m.face(0, [(p0[0], 0, p0[1]), (p1[0], 0, p1[1]), (p1[0], t, p1[1]), (p0[0], t, p0[1])], (math.cos((a0 + a1) / 2), 0, math.sin((a0 + a1) / 2)))
    return m.write("marker", [mat("Marker", color=MARKER, rough=0.6)])


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith(".glb"):
            os.remove(os.path.join(OUT, f))
    total = 0
    total += build_frame()
    total += build_quad() * 4
    total += build_rim()
    total += build_marble("marble_black", OBSIDIAN, (0x2A, 0x2A, 0x3A), 11, 0.15) * 18
    total += build_marble("marble_white", PEARL, (0xE8, 0xDC, 0xC6), 12, 0.2) * 18
    total += build_tray() * 2
    for side in ("l", "r"):
        for d in ("cw", "ccw"):
            build_handle(side, d)
    build_marker()
    print("scene triangles (frame + 4 blocks + 36 marbles + 2 trays): %d" % total)
