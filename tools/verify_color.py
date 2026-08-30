# Cross-checks the colour constants used by Lutty against colour-science.
# O-Log figures come from the OPPO O-Log White Paper V1:
#   https://www.oppo.com/content/dam/oppo_com/en/mkt/footer/OPPO_O-Log_Profile_WhitePaper_V1.pdf
# Apple Log constants are those implemented in OpenColorIO (AppleCameras.cpp).

import numpy as np, colour
np.set_printoptions(precision=10, suppress=True)
FAIL=[]
def check(name, mine, ref, tol):
    mine=np.asarray(mine,float); ref=np.asarray(ref,float)
    d=np.max(np.abs(mine-ref)); ok = d<=tol
    print(f"{'PASS' if ok else 'FAIL'}  {name:52s} maxdiff={d:.3e} (tol {tol:.0e})")
    if not ok: FAIL.append(name)

print("=== 1. Apple Log curve: mine vs colour-science ===")
R_0,R_t,c,beta,gamma,delta = -0.05641088,0.01,47.28711236,0.00964052,0.08550479,0.69336945
P_t = c*(R_t-R_0)**2
def mine_enc(R):
    R=np.asarray(R,float)
    return np.where(R>=R_t, gamma*np.log2(np.maximum(R+beta,1e-30))+delta,
           np.where(R>=R_0, c*(R-R_0)**2, 0.0))
def mine_dec(P):
    P=np.asarray(P,float)
    return np.where(P>=P_t, 2.0**((P-delta)/gamma)-beta,
           np.where(P>=0.0, np.sqrt(np.maximum(P,0)/c)+R_0, R_0))
xs=np.concatenate([np.linspace(-0.05,0.02,40), np.logspace(-2,1.2,60)])
check("AppleLog encode vs colour-science", mine_enc(xs), colour.models.log_encoding_AppleLogProfile(xs), 1e-9)
ps=np.linspace(0.0,1.0,200)
check("AppleLog decode vs colour-science", mine_dec(ps), colour.models.log_decoding_AppleLogProfile(ps), 1e-9)
print("  colour-science encode(0.18) =", colour.models.log_encoding_AppleLogProfile(0.18), " mine =", float(mine_enc(0.18)))

print("\n=== 2. BT.2020 -> ACES AP0 (Bradford): mine vs colour-science ===")
ref = colour.matrix_RGB_to_RGB(colour.RGB_COLOURSPACES['ITU-R BT.2020'],
                               colour.RGB_COLOURSPACES['ACES2065-1'],
                               chromatic_adaptation_transform='Bradford')
mine = np.array([[ 0.6790856347, 0.1577009146, 0.1632134506],
                 [ 0.0460020031, 0.859054673 , 0.0949433239],
                 [-0.0005739432, 0.0284677684, 0.9721061748]])
print("colour-science:\n", ref)
check("BT.2020->AP0 Bradford", mine, ref, 5e-6)

print("\n=== 3. O-Log white paper Table 1, all four rows ===")
def olog_enc(Rn): return 0.139*np.log(np.asarray(Rn,float)*16+0.019)+0.614
rows=[("0%",0.0,0.0631271,64),("18%",0.01125,0.3895463,399),
      ("39%",0.0244,0.4901589,502),("1600%",1.0,1.0,1023)]
for lbl,lin,pub,code in rows:
    got=float(olog_enc(lin))
    print(f"  {lbl:6s} lin={lin:<8} computed={got:.7f}  published={pub:.7f}  diff={abs(got-pub):.2e}  code {round(got*1023)} vs {code}")

print("\n=== 4. O-Log decode is exact inverse of encode ===")
def olog_dec(P): return (np.exp((np.asarray(P,float)-0.614)/0.139)-0.019)/16.0
lin=np.linspace(0,1,500); check("O-Log encode->decode roundtrip", olog_dec(olog_enc(lin)), lin, 1e-12)

print("\n=== 5. AWG->AP0 matrix sanity (OCIO issue #163) ===")
M_AWG_AP0=np.array([[ 0.694961049318096,0.241405268785364,0.06363368189654],
                    [ 0.0473627464149325,1.00429592505428,-0.0516586714692158],
                    [-0.021989789359883,-0.0289891049714743,1.05097889433136]])
print("  row sums:", M_AWG_AP0.sum(axis=1), "(white-preserving => all 1.0)")
check("AWG->AP0 white preservation", M_AWG_AP0.sum(axis=1), [1,1,1], 1e-9)
# recover AWG primaries from the matrix, as an independent read of the gamut
M_AP0_XYZ = colour.RGB_COLOURSPACES['ACES2065-1'].matrix_RGB_to_XYZ
M_AWG_XYZ = M_AP0_XYZ @ M_AWG_AP0
prim = M_AWG_XYZ / M_AWG_XYZ.sum(axis=0)
print("  implied Apple Wide Gamut primaries (x,y):")
for n,i in zip("RGB",range(3)):
    print(f"    {n}: x={prim[0,i]:.6f}  y={prim[1,i]:.6f}")
wp = M_AWG_XYZ.sum(axis=1); wp = wp/wp.sum()
print(f"    white point: x={wp[0]:.6f} y={wp[1]:.6f}   (D65 = 0.3127, 0.3290 / D60 = 0.32168, 0.33767)")

print("\n=== 6. Final chain: 18% grey stays neutral ===")
M_2020_AWG = np.linalg.inv(M_AWG_AP0) @ ref
grey=np.array([0.18,0.18,0.18])
awg = M_2020_AWG @ olog_dec(olog_enc(grey/16*16))*1  # decode gives normalized; scale below
lin2020 = np.exp((olog_enc(np.array([0.18,0.18,0.18])/16)-0.614)/0.139)-0.019
print("  O-Log code for 18% grey:", float(olog_enc(0.18/16)))
print("  decoded reflectance    :", lin2020)
awg2 = M_2020_AWG @ lin2020
print("  in Apple Wide Gamut    :", awg2)
print("  Apple Log 2 code       :", mine_enc(awg2))
check("neutral preserved through chain", awg2, [0.18,0.18,0.18], 1e-9)
print("\nBT.2020 -> Apple Wide Gamut matrix:\n", M_2020_AWG)
print("\n" + ("ALL CHECKS PASSED" if not FAIL else "FAILURES: "+", ".join(FAIL)))
