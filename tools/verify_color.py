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
print("\n=== 7. RED Log3G10 v3 curve: mine vs colour-science ===")
# RED "White Paper on REDWideGamutRGB and Log3G10" (2017); .R3D / IPP2 log curve.
g10a, g10b, g10c, g10g = 0.224282, 155.975327, 0.01, 15.1927
def g10_enc(x):
    x = np.asarray(x, float) + g10c
    return np.where(x < 0.0, x*g10g, g10a*np.log10(x*g10b + 1.0))
def g10_dec(y):
    y = np.asarray(y, float)
    return np.where(y < 0.0, y/g10g - g10c, (10.0**(y/g10a) - 1.0)/g10b - g10c)
xs = np.concatenate([np.linspace(-0.02, 0.02, 40), np.logspace(-2, 1.2, 60)])
check("Log3G10 v3 encode vs colour-science", g10_enc(xs), colour.models.log_encoding_Log3G10(xs, method='v3'), 1e-9)
ps = np.linspace(0.0, 1.0, 200)
check("Log3G10 v3 decode vs colour-science", g10_dec(ps), colour.models.log_decoding_Log3G10(ps, method='v3'), 1e-9)

print("\n=== 8. Nikon N-Log curve: mine vs colour-science ===")
# Nikon "N-Log Specification Document" v1.0; reflection input, normalised code-value output.
n_cut1, n_cut2 = 0.328, 0.4418377321603128
n_a, n_b, n_c, n_d = 0.635386119257087, 0.0075, 0.1466275659824047, 0.6050830889540567
def nlog_enc(y):
    y = np.asarray(y, float)
    return np.where(y < n_cut1, n_a*np.cbrt(y + n_b), n_c*np.log(np.maximum(y, 1e-30)) + n_d)
def nlog_dec(x):
    x = np.asarray(x, float)
    return np.where(x < n_cut2, (x/n_a)**3 - n_b, np.exp((x - n_d)/n_c))
ys = np.logspace(-3, 1.2, 120)
check("N-Log encode vs colour-science", nlog_enc(ys), colour.models.log_encoding_NLog(ys), 1e-9)
check("N-Log decode vs colour-science", nlog_dec(ps), colour.models.log_decoding_NLog(ps), 1e-9)

print("\n=== 9. REDWideGamutRGB -> Apple Wide Gamut, computed like section 5, vs repo constant ===")
M_AP0_AWG = np.linalg.inv(M_AWG_AP0)
M_RED_2065 = colour.matrix_RGB_to_RGB(colour.RGB_COLOURSPACES['REDWideGamutRGB'],
                                      colour.RGB_COLOURSPACES['ACES2065-1'],
                                      chromatic_adaptation_transform='Bradford')
M_RED_AWG = M_AP0_AWG @ M_RED_2065
repo_red = np.array([[ 1.1455528684, -0.2293505398, 0.0837960327],
                     [-0.0333742668,  1.0799487937, -0.0465739624],
                     [-0.0471347145, -0.2743408983, 1.3214758140]])
print("colour-science:\n", M_RED_AWG)
check("REDWideGamut->AWG vs repo constant", repo_red, M_RED_AWG, 5e-6)
check("REDWideGamut->AWG row sums ~ 1", M_RED_AWG.sum(axis=1), [1, 1, 1], 5e-6)
print("  N-Gamut primaries == BT.2020?",
      np.allclose(colour.RGB_COLOURSPACES['N-Gamut'].primaries,
                  colour.RGB_COLOURSPACES['ITU-R BT.2020'].primaries))

print("\n=== 10. Fujifilm F-Log2 curve: mine vs colour-science ===")
# Fujifilm "F-Log2 Data Sheet" (2022); reflection input, normalised code-value output.
f2_cut1, f2_cut2 = 0.000889, 0.100686685370811
f2_a, f2_b, f2_c, f2_d, f2_e, f2_f = 5.555556, 0.064829, 0.245281, 0.384316, 8.799461, 0.092864
def f2_enc(r):
    r = np.asarray(r, float)
    return np.where(r < f2_cut1, f2_e*r + f2_f, f2_c*np.log10(f2_a*r + f2_b) + f2_d)
def f2_dec(p):
    p = np.asarray(p, float)
    return np.where(p < f2_cut2, (p - f2_f)/f2_e, (10**((p - f2_d)/f2_c) - f2_b)/f2_a)
rs = np.logspace(-3, 1.2, 120)
check("F-Log2 encode vs colour-science", f2_enc(rs), colour.models.log_encoding_FLog2(rs), 1e-9)
check("F-Log2 decode vs colour-science", f2_dec(ps), colour.models.log_decoding_FLog2(ps), 1e-9)
print("  F-Gamut primaries == BT.2020?",
      np.allclose(colour.RGB_COLOURSPACES['F-Gamut'].primaries,
                  colour.RGB_COLOURSPACES['ITU-R BT.2020'].primaries))

print("\n" + ("ALL CHECKS PASSED" if not FAIL else "FAILURES: "+", ".join(FAIL)))
