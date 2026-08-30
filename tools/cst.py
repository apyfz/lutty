import numpy as np
np.set_printoptions(precision=10, suppress=True)

def npm(prim, wp):
    """RGB->XYZ matrix from chromaticities."""
    (xr,yr),(xg,yg),(xb,yb) = prim
    xw,yw = wp
    M = np.array([[xr/yr, xg/yg, xb/yb],
                  [1.0,   1.0,   1.0  ],
                  [(1-xr-yr)/yr, (1-xg-yg)/yg, (1-xb-yb)/yb]])
    W = np.array([xw/yw, 1.0, (1-xw-yw)/yw])
    S = np.linalg.solve(M, W)
    return M * S

BRADFORD = np.array([[ 0.8951,  0.2664, -0.1614],
                     [-0.7502,  1.7135,  0.0367],
                     [ 0.0389, -0.0685,  1.0296]])

def cat(src_wp, dst_wp, M=BRADFORD):
    def xyz(wp):
        x,y = wp; return np.array([x/y, 1.0, (1-x-y)/y])
    s = M @ xyz(src_wp); d = M @ xyz(dst_wp)
    return np.linalg.inv(M) @ np.diag(d/s) @ M

REC2020 = [(0.708,0.292),(0.170,0.797),(0.131,0.046)]
AP0     = [(0.7347,0.2653),(0.0000,1.0000),(0.0001,-0.0770)]
D65 = (0.3127,0.3290); D60 = (0.32168,0.33767)

M_2020_XYZ = npm(REC2020, D65)
M_AP0_XYZ  = npm(AP0, D60)

# BT.2020 (D65) -> AP0 (D60), Bradford
M_2020_AP0 = np.linalg.inv(M_AP0_XYZ) @ cat(D65, D60) @ M_2020_XYZ
print("BT.2020(D65) -> ACES AP0  [Bradford]"); print(M_2020_AP0)

# Apple Wide Gamut -> AP0, from OCIO issue 163
M_AWG_AP0 = np.array([
 [ 0.694961049318096, 0.241405268785364, 0.06363368189654 ],
 [ 0.0473627464149325,1.00429592505428, -0.0516586714692158],
 [-0.021989789359883,-0.0289891049714743,1.05097889433136 ]])
print("\nAP0 -> Apple Wide Gamut"); print(np.linalg.inv(M_AWG_AP0))

M_2020_AWG = np.linalg.inv(M_AWG_AP0) @ M_2020_AP0
print("\n>>> BT.2020 -> Apple Wide Gamut  (the O-Log -> Apple Log 2 matrix)")
print(M_2020_AWG)
print("\nrow sums (should be ~1.0 for a white-preserving matrix):", M_2020_AWG.sum(axis=1))

# ---- sanity: round-trip 18% grey through the full chain ----
def olog_decode(P): return np.exp((P-0.614)/0.139) - 0.019
def olog_encode(R): return 0.139*np.log(R+0.019) + 0.614

R_0,R_t,c,beta,gamma,delta = -0.05641088,0.01,47.28711236,0.00964052,0.08550479,0.69336945
P_t = c*(R_t-R_0)**2
def applelog_encode(R):
    R = np.asarray(R,dtype=float)
    return np.where(R>=R_t, gamma*np.log2(np.maximum(R+beta,1e-12))+delta,
           np.where(R>=R_0, c*(R-R_0)**2, 0.0))
def applelog_decode(P):
    P = np.asarray(P,dtype=float)
    return np.where(P>=P_t, 2.0**((P-delta)/gamma)-beta,
           np.where(P>=0.0, np.sqrt(np.maximum(P,0)/c)+R_0, R_0))

print("\n--- checks ---")
print("O-Log encode(0.18) =", olog_encode(0.18), " (white paper: 0.3895463)")
print("O-Log encode(0.39) =", olog_encode(0.39), " (white paper: 0.4901589)")
print("O-Log encode(0.00) =", olog_encode(0.0),  " (white paper: 0.0631271 -> code 64)")
print("O-Log encode(16.0) =", olog_encode(16.0), " (white paper: 1.0)")
print("AppleLog P_t       =", P_t)
print("AppleLog continuity at R_t: log-branch",
      gamma*np.log2(R_t+beta)+delta, " gamma-branch", c*(R_t-R_0)**2)
print("AppleLog encode(0.18) =", applelog_encode(0.18))
print("AppleLog roundtrip 0.18 ->", applelog_decode(applelog_encode(0.18)))

grey = np.array([0.18,0.18,0.18])
p_olog = olog_encode(grey)
lin2020 = olog_decode(p_olog)
lin_awg = M_2020_AWG @ lin2020
print("\n18% grey: O-Log code", p_olog[0], "-> linear", lin2020[0],
      "-> AWG", lin_awg, "-> AppleLog2", applelog_encode(lin_awg))
