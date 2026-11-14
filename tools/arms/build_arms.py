"""Poses the driver's arms for the life-size cockpit view and writes them for the game.

Source: the MakeHuman base mesh, skeleton and skin weights (CC0 1.0, makehumancommunity/
makehuman: makehuman/data/3dobjs/base.obj, data/rigs/default.mhskel, default_weights.mhw).

The arms and hands are cut from the body, scaled to adult proportions and posed with the
skeleton (linear-blend skinning): the shoulder sits where Scenery.SHOULDER_* says, each hand
grips the steering wheel rim (Scenery.WHEEL / WHEEL_TILT / WHEEL_R / RIM_T) at "ten to two",
the elbow is solved from the arm's bone lengths, and the fingers curl round the rim. One pose
is baked for each wheel angle from -60 to +60 degrees in 10 degree steps; the game blends
between neighbours. Dressing: fire-suit sleeve, knitted cuff, gauntlet
glove with a darker palm (material ids, coloured by the game).

Usage: python3 build_arms.py MH_DIR OUT_FILE [DEBUG_DIR]
"""
import json, struct, sys, math
import numpy as np

# ---- cockpit geometry (must match Scenery.java) ----
WHEEL = np.array([0.0, 1.24, -2.47])
WHEEL_TILT = 62.0
WHEEL_R = 0.165
GRIP_T = 0.019 * 1.18
SHOULDER = {+1: np.array([0.2, 1.15, -1.9]), -1: np.array([-0.2, 1.15, -1.9])}
HAND_ANGLE = {+1: 20.0, -1: 160.0}
ANGLES = list(range(-60, 61, 10))
SCALE = 0.1 * 1.1 
STRETCH = 1.2          # the base mesh has short arms; lengthen upper arm and forearm only
KNUCK = (1.0, 2.0)     # knuckle offset from the rim centre: out, forward (in rim radii)
FLEX = 1.4
M_TILT = 0.3
         # MakeHuman units are decimetres; scale to a large adult's arm

# material ids
SUIT, STRIPE, CUFF, GLOVE, PALM = 0, 1, 2, 3, 4


def load(mh):
    V, F, cur = [], [], None
    for line in open(mh + "/base.obj"):
        if line.startswith("v "):
            V.append([float(x) for x in line.split()[1:4]])
        elif line.startswith("g "):
            cur = line.split()[1]
        elif line.startswith("f ") and cur == "body":
            F.append([int(t.split("/")[0]) - 1 for t in line.split()[1:]])
    V = np.array(V)
    sk = json.load(open(mh + "/default.mhskel"))
    W = json.load(open(mh + "/default_weights.mhw"))["weights"]
    J = {k: V[v].mean(0) for k, v in sk["joints"].items()}
    return V, F, sk["bones"], J, W


def rot_x(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_z(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def axis_angle(axis, ang):
    axis = axis / np.linalg.norm(axis)
    x, y, z = axis; c, s = math.cos(ang), math.sin(ang); C = 1 - c
    return np.array([[c + x*x*C, x*y*C - z*s, x*z*C + y*s],
                     [y*x*C + z*s, c + y*y*C, y*z*C - x*s],
                     [z*x*C - y*s, z*y*C + x*s, c + z*z*C]])


def frame(a, b):
    """Orthonormal frame from a primary direction a and a secondary b."""
    x = a / np.linalg.norm(a)
    z = np.cross(x, b); z /= np.linalg.norm(z)
    y = np.cross(z, x)
    return np.stack([x, y, z], 1)


def align(a0, b0, a1, b1):
    """Rotation taking direction a0 to a1, with b0 turned as close to b1 as possible."""
    return frame(a1, b1) @ frame(a0, b0).T


def to_quat(R):
    t = np.trace(R)
    if t > 0:
        s = math.sqrt(t + 1) * 2
        return np.array([0.25 * s, (R[2, 1] - R[1, 2]) / s, (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s])
    i = int(np.argmax(np.diag(R)))
    j, k = (i + 1) % 3, (i + 2) % 3
    s = math.sqrt(R[i, i] - R[j, j] - R[k, k] + 1) * 2
    q = np.zeros(4); q[0] = (R[k, j] - R[j, k]) / s; q[1 + i] = 0.25 * s
    q[1 + j] = (R[j, i] + R[i, j]) / s; q[1 + k] = (R[k, i] + R[i, k]) / s
    return q


def from_quat(q):
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([[1 - 2*(y*y + z*z), 2*(x*y - z*w), 2*(x*z + y*w)],
                     [2*(x*y + z*w), 1 - 2*(x*x + z*z), 2*(y*z - x*w)],
                     [2*(x*z - y*w), 2*(y*z + x*w), 1 - 2*(x*x + y*y)]])


def slerp(R0, R1, t):
    q0, q1 = to_quat(R0), to_quat(R1)
    if np.dot(q0, q1) < 0: q1 = -q1
    d = min(1.0, float(np.dot(q0, q1)))
    if d > 0.9995: q = q0 + t * (q1 - q0)
    else:
        a = math.acos(d); q = (math.sin((1 - t) * a) * q0 + math.sin(t * a) * q1) / math.sin(a)
    return from_quat(q)


class Arm:
    """One arm (side +1 = right, -1 = left) in boat coordinates at rest."""

    def __init__(self, V, F, B, J, W, side):
        sfx = ".R" if side > 0 else ".L"
        self.side, self.B = side, B
        # body -> boat: the body faces +z, the boat's forward is -z: turn 180 degrees round y
        self.to_boat = lambda p: np.stack([-p[..., 0], p[..., 1], -p[..., 2]], -1) * SCALE
        chain = ["shoulder01", "upperarm01", "upperarm02", "lowerarm01", "lowerarm02", "wrist"]
        fingers = ["metacarpal1", "metacarpal2", "metacarpal3", "metacarpal4"] + \
                  ["finger%d-%d" % (f, k) for f in range(1, 6) for k in range(1, 4)]
        self.bones = [b + sfx for b in chain + fingers]
        self.head = {b: self.to_boat(J[B[b]["head"]]) for b in self.bones}
        self.tail = {b: self.to_boat(J[B[b]["tail"]]) for b in self.bones}
        # vertices that belong to the arm (by skin weight), and their weights
        wsum = np.zeros(len(V)); wts = {}
        for b in self.bones:
            for vi, w in W.get(b, []):
                wsum[vi] += w; wts.setdefault(vi, []).append((b, w))
        keep = wsum > 0.35
        # drop the shoulder cap so the cut sits under the sleeve's top
        keep &= ~np.array([any(b.startswith("shoulder01") and w > 0.6 for b, w in wts.get(i, [])) for i in range(len(V))])
        faces = [f for f in F if all(keep[i] for i in f)]
        used = sorted({i for f in faces for i in f})
        remap = {o: n for n, o in enumerate(used)}
        self.rest = self.to_boat(V[used])
        self.faces = [[remap[i] for i in f] for f in faces]
        self.weights = []
        for o in used:
            ws = wts[o]; s = sum(w for _, w in ws)
            self.weights.append([(b, w / s) for b, w in ws])
        # dressing: which bone mostly drives each vertex
        hand_bones = {b for b in self.bones if b.startswith(("wrist", "metacarpal", "finger"))}
        self.mat = np.zeros(len(used), np.int32)
        wr = self.head["wrist" + sfx]
        el = self.head["lowerarm01" + sfx]
        fa = (wr - el) / np.linalg.norm(wr - el)
        for n, ws in enumerate(self.weights):
            hand = sum(w for b, w in ws if b in hand_bones)
            p = self.rest[n]
            if hand > 0.5:
                self.mat[n] = GLOVE
            else:
                d = np.dot(p - wr, -fa)   # distance back from the wrist along the forearm
                self.mat[n] = GLOVE if d < 0.075 else (CUFF if d < 0.095 else SUIT)
        self.sfx = sfx
        m0, k0, n0 = self.palm_side()
        RN = normals(self.rest, self.faces)
        for n, ws in enumerate(self.weights):
            hand = sum(w for b, w in ws if b in hand_bones)
            if hand > 0.5 and np.dot(RN[n], n0) < -0.15:
                self.mat[n] = PALM

    def h(self, b): return self.head[b + self.sfx]
    def t(self, b): return self.tail[b + self.sfx]

    def palm_side(self):
        """Rest hand frame: m = wrist -> middle knuckle, k = index -> pinky knuckles, n = back."""
        w = self.h("wrist")
        km = self.h("finger3-1")
        ki, kp = self.h("finger2-1"), self.h("finger5-1")
        m = km - w; m /= np.linalg.norm(m)
        k = kp - ki; k -= np.dot(k, m) * m; k /= np.linalg.norm(k)
        n = np.cross(m, k); n /= np.linalg.norm(n)
        # the back of the hand is the side the fingers don't curl towards
        tip = self.t("finger3-3") - self.h("finger3-1")
        if np.dot(tip, n) > 0: n = -n
        return m, k, n


def pose(arm, wheel_angle):
    s = arm.side
    # wheel frame and the hand's frame on the rim
    Wf = rot_x(-(90 - WHEEL_TILT))
    a = wheel_angle + HAND_ANGLE[s]
    Hf = Wf @ rot_z(a)
    X, Y, Z = Hf[:, 0], Hf[:, 1], Hf[:, 2]      # radial out, tangent (CCW), towards driver
    grip = WHEEL + Hf @ np.array([WHEEL_R, 0, 0])
    up_t = Y if s > 0 else -Y                    # along the rim towards twelve o'clock
    # target hand frame: back of the hand faces the driver and a little inwards; the
    # metacarpals point in across the rim; knuckles line up along the rim (thumb side up)
    # a real grip: the palm wraps the outside of the rim, so the metacarpals run from the
    # wrist (out and towards the driver) to the knuckles on the far, outer edge of the rim
    m_t = -X * M_TILT - Z * 1.0; m_t /= np.linalg.norm(m_t)
    k_t = -up_t
    m0, k0, n0 = arm.palm_side()
    Rh = align(m0, k0, m_t, k_t)
    n_t = Rh @ n0
    # knuckles sit just outside and in front of the rim
    knuck = grip + X * (GRIP_T * KNUCK[0]) - Z * (GRIP_T * KNUCK[1])
    W = knuck - Rh @ (arm.h("finger3-1") - arm.h("wrist"))
    # arm: shoulder -> elbow -> wrist with the rest bone lengths
    S0, E0, W0 = arm.h("upperarm01"), arm.h("lowerarm01"), arm.h("wrist")
    la, lb = np.linalg.norm(E0 - S0) * STRETCH, np.linalg.norm(W0 - E0) * STRETCH
    S = SHOULDER[s]
    d = W - S; dl = np.linalg.norm(d); u = d / dl
    dc = min(max(dl, 0.05), la + lb - 1e-4)
    along = (la * la - lb * lb + dc * dc) / (2 * dc)
    hgt = math.sqrt(max(0.0, la * la - along * along))
    pole = np.array([0.8 * s, -1.0, 0.25]); pole -= np.dot(pole, u) * u; pole /= np.linalg.norm(pole)
    if dl > (la + lb) * 0.96:                    # out of reach: the shoulder leans in
        S = S + u * (dl - (la + lb) * 0.96)
        d = W - S; dl = np.linalg.norm(d); dc = dl
        along = (la * la - lb * lb + dc * dc) / (2 * dc)
        hgt = math.sqrt(max(0.0, la * la - along * along))
    E = S + u * along + pole * hgt
    arm.reach = dl / (la + lb)
    up0, fo0 = E0 - S0, W0 - E0
    up1, fo1 = E - S, W - E
    bend0, bend1 = np.cross(up0, fo0), np.cross(up1, fo1)
    Ru = align(up0, bend0, up1, bend1)
    # forearm: point along the forearm and twist so the wrist lines up with the hand
    Rl_bend = align(fo0, bend0, fo1, bend1)
    Rl_twist = align(fo0, k0, fo1, k_t)
    Rl1 = slerp(Rl_bend, Rl_twist, 0.45)
    Rl2 = Rl_twist
    def stretch(v):
        v = v / np.linalg.norm(v)
        return np.eye(3) + (STRETCH - 1) * np.outer(v, v)
    Su, Sl = stretch(up0), stretch(fo0)
    Ru_s, Rl1_s, Rl2_s = Ru @ Su, Rl1 @ Sl, Rl2 @ Sl
    R = {"shoulder01": Ru, "upperarm01": Ru_s, "upperarm02": Ru_s, "lowerarm01": Rl1_s, "lowerarm02": Rl2_s,
         "wrist": Rh}
    P = {"shoulder01": S + Ru @ (arm.h("shoulder01") - S0), "upperarm01": S,
         "upperarm02": S + Ru_s @ (arm.h("upperarm02") - S0), "lowerarm01": E,
         "lowerarm02": E + Rl1_s @ (arm.h("lowerarm02") - E0), "wrist": W}
    # fingers: each joint flexes about the knuckle line, towards the palm
    flex = {2: (62, 72, 48), 3: (66, 78, 50), 4: (68, 80, 50), 5: (70, 82, 48)}
    for f in range(2, 6):
        mc = "metacarpal%d" % (f - 1)
        R[mc] = Rh; P[mc] = W + Rh @ (arm.h(mc) - W0)
        parentR, parentP, parentH = Rh, W, W0
        axis = np.cross(n_t, m_t)              # flexing turns the finger from m towards -n
        for k in range(1, 4):
            b = "finger%d-%d" % (f, k)
            hp = parentP + parentR @ (arm.h(b) - parentH) if k > 1 else W + Rh @ (arm.h(b) - W0)
            ang = math.radians(FLEX * sum(flex[f][:k]))
            Rb = axis_angle(axis, ang) @ Rh
            # make sure the flex goes towards the palm
            tip_dir = Rb @ (arm.t(b) - arm.h(b))
            if k == 1 and np.dot(tip_dir, n_t) > 0:
                axis = -axis; Rb = axis_angle(axis, ang) @ Rh
            R[b], P[b] = Rb, hp
            parentR, parentP, parentH = Rb, hp, arm.h(b)
    # thumb: along the front of the rim towards twelve o'clock, tip curling over it
    th0 = arm.t("finger1-1") - arm.h("finger1-1")
    t_dir = up_t * 0.75 - X * 0.45 + Z * 0.2; t_dir /= np.linalg.norm(t_dir)
    Rt1 = align(Rh @ th0, n_t, t_dir, Z) @ Rh
    parentR, parentP, parentH = Rt1, W + Rh @ (arm.h("finger1-1") - W0), arm.h("finger1-1")
    R["finger1-1"], P["finger1-1"] = Rt1, parentP
    taxis = np.cross(t_dir, Z)
    for k, ang in ((2, 18), (3, 30)):
        b = "finger1-%d" % k
        hp = parentP + parentR @ (arm.h(b) - parentH)
        Rb = axis_angle(taxis, math.radians(ang)) @ Rt1
        if np.dot(Rb @ (arm.t(b) - arm.h(b)), Z) > np.dot(Rt1 @ (arm.t(b) - arm.h(b)), Z):
            Rb = axis_angle(taxis, -math.radians(ang)) @ Rt1
        R[b], P[b] = Rb, hp
        parentR, parentP, parentH = Rb, hp, arm.h(b)
    # skin
    out = np.zeros_like(arm.rest)
    for i, ws in enumerate(arm.weights):
        acc = np.zeros(3)
        for b, w in ws:
            bb = b[:-2]
            acc += w * (R[bb] @ (arm.rest[i] - arm.head[b]) + P[bb])
        out[i] = acc
    return out


def normals(P, faces):
    N = np.zeros_like(P)
    for f in faces:
        a, b, c, d = (P[i] for i in f)
        n = np.cross(c - a, d - b)
        for i in f: N[i] += n
    N /= np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-9)
    return N


def dress(arm, P, N):
    """Suit over the skin (a little baggier), gauntlet gloves with a knitted cuff above."""
    Q = P.copy()
    mat = arm.mat.copy()
    for i in range(len(P)):
        if mat[i] == SUIT:
            Q[i] += N[i] * 0.005
        elif mat[i] == CUFF:
            Q[i] += N[i] * 0.007
        else:
            Q[i] += N[i] * 0.0025
    return Q, mat


def main():
    mh, out = sys.argv[1], sys.argv[2]
    dbg = sys.argv[3] if len(sys.argv) > 3 else None
    V, F, B, J, W = load(mh)
    arms = [Arm(V, F, B, J, W, s) for s in (+1, -1)]
    blob = bytearray()
    blob += struct.pack("<4i", len(ANGLES), len(arms), 0, 0)
    blob += struct.pack("<%df" % len(ANGLES), *ANGLES)
    for arm in arms:
        tris = []
        for f in arm.faces:
            tris += [f[0], f[1], f[2], f[0], f[2], f[3]]
        nv = len(arm.rest)
        poses = []
        mats = None
        for ang in ANGLES:
            P = pose(arm, ang)
            N = normals(P, arm.faces)
            Q, mats = dress(arm, P, N)
            N2 = normals(Q, arm.faces)
            poses.append((Q, N2))
        blob += struct.pack("<2i", nv, len(tris))
        blob += struct.pack("<%dH" % len(tris), *tris)
        blob += struct.pack("<%dB" % nv, *[int(m) for m in mats])
        if len(blob) % 4: blob += b"\0" * (4 - len(blob) % 4)
        for Q, N2 in poses:
            blob += np.concatenate([Q, N2], 1).astype("<f4").tobytes()
        if dbg:
            col = {SUIT: (0.55, 0.08, 0.06), STRIPE: (0.95, 0.95, 0.94), CUFF: (0.12, 0.12, 0.13),
                   GLOVE: (0.24, 0.24, 0.26), PALM: (0.07, 0.07, 0.08)}
            for ai, ang in enumerate(ANGLES):
                Q, N2 = poses[ai]
                data = []
                for t in tris:
                    data += list(Q[t]) + list(N2[t]) + list(col[int(mats[t])])
                np.array(data, "<f4").tofile("%s/arm%s_%d.v" % (dbg, "R" if arm.side > 0 else "L", ang))
        print("arm", "R" if arm.side > 0 else "L", "verts", nv, "tris", len(tris) // 3)
    open(out, "wb").write(bytes(blob))
    print("wrote", out, len(blob), "bytes")


if __name__ == "__main__":
    main()
