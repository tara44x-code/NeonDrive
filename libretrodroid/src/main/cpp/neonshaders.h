/*
 * NeonDrive additions to LibretroDroid's shader set (GLSL ES 1.00, single pass, any scale).
 *
 *  - xBRZ "freescale": port of Hyllian's xbrz-freescale.glsl (libretro/glsl-shaders), MIT,
 *    which uses code and concepts from xBRZ by Zenju (GPL-3.0).
 *  - xBR level 2: port of Hyllian's xbr-lv2.glsl (libretro/glsl-shaders), MIT.
 *    Edge blending follows the real screen scale (screenDensity) instead of a fixed 3x.
 *  - Colour adjust: brightness / contrast / saturation appended to the last pass of any chain.
 *
 * Uniforms provided by LibretroDroid: texture, textureSize, screenDensity.
 * Attributes: vPosition, vCoordinate.
 */

#ifndef LIBRETRODROID_NEONSHADERS_H
#define LIBRETRODROID_NEONSHADERS_H

#include <string>

namespace libretrodroid {
namespace neonshaders {

static const std::string precisionHeader = R"(
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
)";

// ---------------------------------------------------------------- xBRZ freescale
static const std::string xbrzVertex = R"(
attribute vec4 vPosition;
attribute vec2 vCoordinate;
varying vec2 vTexCoord;
void main() {
  vTexCoord = vCoordinate * 1.0001;
  gl_Position = vPosition;
}
)";

static const std::string xbrzFragment = precisionHeader + R"(
uniform sampler2D texture;
uniform vec2 textureSize;
uniform float screenDensity;
varying vec2 vTexCoord;

#define BLEND_NONE 0
#define BLEND_NORMAL 1
#define BLEND_DOMINANT 2
#define LUMINANCE_WEIGHT 1.0
#define EQUAL_COLOR_TOLERANCE (30.0 / 255.0)
#define STEEP_DIRECTION_THRESHOLD 2.2
#define DOMINANT_DIRECTION_THRESHOLD 3.6

float DistYCbCr(vec3 pixA, vec3 pixB) {
  const vec3 w = vec3(0.2627, 0.6780, 0.0593);
  const float scaleB = 0.5 / (1.0 - 0.0593);
  const float scaleR = 0.5 / (1.0 - 0.2627);
  vec3 diff = pixA - pixB;
  float Y = dot(diff.rgb, w);
  float Cb = scaleB * (diff.b - Y);
  float Cr = scaleR * (diff.r - Y);
  return sqrt(((LUMINANCE_WEIGHT * Y) * (LUMINANCE_WEIGHT * Y)) + (Cb * Cb) + (Cr * Cr));
}

bool IsPixEqual(vec3 pixA, vec3 pixB) {
  return (DistYCbCr(pixA, pixB) < EQUAL_COLOR_TOLERANCE);
}

float get_left_ratio(vec2 center, vec2 origin, vec2 direction, vec2 scale) {
  vec2 P0 = center - origin;
  vec2 proj = direction * (dot(P0, direction) / dot(direction, direction));
  vec2 distv = P0 - proj;
  vec2 orth = vec2(-direction.y, direction.x);
  float side = sign(dot(P0, orth));
  float v = side * length(distv * scale);
  return smoothstep(-sqrt(2.0) / 2.0, sqrt(2.0) / 2.0, v);
}

#define eq(a, b)  (a == b)
#define neq(a, b) (a != b)
#define P(x, y) texture2D(texture, coord + ps * vec2(x, y)).rgb

void main() {
  vec2 ps = 1.0 / textureSize;
  vec2 scale = vec2(max(screenDensity, 1.0));
  vec2 pos = fract(vTexCoord * textureSize) - vec2(0.5, 0.5);
  vec2 coord = vTexCoord - pos * ps;

  vec3 A = P(-1.0, -1.0);
  vec3 B = P( 0.0, -1.0);
  vec3 C = P( 1.0, -1.0);
  vec3 D = P(-1.0,  0.0);
  vec3 E = P( 0.0,  0.0);
  vec3 F = P( 1.0,  0.0);
  vec3 G = P(-1.0,  1.0);
  vec3 H = P( 0.0,  1.0);
  vec3 I = P( 1.0,  1.0);

  ivec4 blendResult = ivec4(BLEND_NONE, BLEND_NONE, BLEND_NONE, BLEND_NONE);

  if (!((eq(E, F) && eq(H, I)) || (eq(E, H) && eq(F, I)))) {
    float dist_H_F = DistYCbCr(G, E) + DistYCbCr(E, C) + DistYCbCr(P(0.0, 2.0), I) + DistYCbCr(I, P(2.0, 0.0)) + (4.0 * DistYCbCr(H, F));
    float dist_E_I = DistYCbCr(D, H) + DistYCbCr(H, P(1.0, 2.0)) + DistYCbCr(B, F) + DistYCbCr(F, P(2.0, 1.0)) + (4.0 * DistYCbCr(E, I));
    bool dominantGradient = (DOMINANT_DIRECTION_THRESHOLD * dist_H_F) < dist_E_I;
    blendResult.z = ((dist_H_F < dist_E_I) && neq(E, F) && neq(E, H)) ? ((dominantGradient) ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
  }

  if (!((eq(D, E) && eq(G, H)) || (eq(D, G) && eq(E, H)))) {
    float dist_G_E = DistYCbCr(P(-2.0, 1.0), D) + DistYCbCr(D, B) + DistYCbCr(P(-1.0, 2.0), H) + DistYCbCr(H, F) + (4.0 * DistYCbCr(G, E));
    float dist_D_H = DistYCbCr(P(-2.0, 0.0), G) + DistYCbCr(G, P(0.0, 2.0)) + DistYCbCr(A, E) + DistYCbCr(E, I) + (4.0 * DistYCbCr(D, H));
    bool dominantGradient = (DOMINANT_DIRECTION_THRESHOLD * dist_D_H) < dist_G_E;
    blendResult.w = ((dist_G_E > dist_D_H) && neq(E, D) && neq(E, H)) ? ((dominantGradient) ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
  }

  if (!((eq(B, C) && eq(E, F)) || (eq(B, E) && eq(C, F)))) {
    float dist_E_C = DistYCbCr(D, B) + DistYCbCr(B, P(1.0, -2.0)) + DistYCbCr(H, F) + DistYCbCr(F, P(2.0, -1.0)) + (4.0 * DistYCbCr(E, C));
    float dist_B_F = DistYCbCr(A, E) + DistYCbCr(E, I) + DistYCbCr(P(0.0, -2.0), C) + DistYCbCr(C, P(2.0, 0.0)) + (4.0 * DistYCbCr(B, F));
    bool dominantGradient = (DOMINANT_DIRECTION_THRESHOLD * dist_B_F) < dist_E_C;
    blendResult.y = ((dist_E_C > dist_B_F) && neq(E, B) && neq(E, F)) ? ((dominantGradient) ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
  }

  if (!((eq(A, B) && eq(D, E)) || (eq(A, D) && eq(B, E)))) {
    float dist_D_B = DistYCbCr(P(-2.0, 0.0), A) + DistYCbCr(A, P(0.0, -2.0)) + DistYCbCr(G, E) + DistYCbCr(E, C) + (4.0 * DistYCbCr(D, B));
    float dist_A_E = DistYCbCr(P(-2.0, -1.0), D) + DistYCbCr(D, H) + DistYCbCr(P(-1.0, -2.0), B) + DistYCbCr(B, F) + (4.0 * DistYCbCr(A, E));
    bool dominantGradient = (DOMINANT_DIRECTION_THRESHOLD * dist_D_B) < dist_A_E;
    blendResult.x = ((dist_D_B < dist_A_E) && neq(E, D) && neq(E, B)) ? ((dominantGradient) ? BLEND_DOMINANT : BLEND_NORMAL) : BLEND_NONE;
  }

  vec3 res = E;

  if (blendResult.z != BLEND_NONE) {
    float dist_F_G = DistYCbCr(F, G);
    float dist_H_C = DistYCbCr(H, C);
    bool doLineBlend = (blendResult.z == BLEND_DOMINANT ||
      !((blendResult.y != BLEND_NONE && !IsPixEqual(E, G)) || (blendResult.w != BLEND_NONE && !IsPixEqual(E, C)) ||
        (IsPixEqual(G, H) && IsPixEqual(H, I) && IsPixEqual(I, F) && IsPixEqual(F, C) && !IsPixEqual(E, I))));
    vec2 origin = vec2(0.0, 1.0 / sqrt(2.0));
    vec2 direction = vec2(1.0, -1.0);
    if (doLineBlend) {
      bool haveShallowLine = (STEEP_DIRECTION_THRESHOLD * dist_F_G <= dist_H_C) && neq(E, G) && neq(D, G);
      bool haveSteepLine = (STEEP_DIRECTION_THRESHOLD * dist_H_C <= dist_F_G) && neq(E, C) && neq(B, C);
      origin = haveShallowLine ? vec2(0.0, 0.25) : vec2(0.0, 0.5);
      direction.x += haveShallowLine ? 1.0 : 0.0;
      direction.y -= haveSteepLine ? 1.0 : 0.0;
    }
    vec3 blendPix = mix(H, F, step(DistYCbCr(E, F), DistYCbCr(E, H)));
    res = mix(res, blendPix, get_left_ratio(pos, origin, direction, scale));
  }

  if (blendResult.w != BLEND_NONE) {
    float dist_H_A = DistYCbCr(H, A);
    float dist_D_I = DistYCbCr(D, I);
    bool doLineBlend = (blendResult.w == BLEND_DOMINANT ||
      !((blendResult.z != BLEND_NONE && !IsPixEqual(E, A)) || (blendResult.x != BLEND_NONE && !IsPixEqual(E, I)) ||
        (IsPixEqual(A, D) && IsPixEqual(D, G) && IsPixEqual(G, H) && IsPixEqual(H, I) && !IsPixEqual(E, G))));
    vec2 origin = vec2(-1.0 / sqrt(2.0), 0.0);
    vec2 direction = vec2(1.0, 1.0);
    if (doLineBlend) {
      bool haveShallowLine = (STEEP_DIRECTION_THRESHOLD * dist_H_A <= dist_D_I) && neq(E, A) && neq(B, A);
      bool haveSteepLine = (STEEP_DIRECTION_THRESHOLD * dist_D_I <= dist_H_A) && neq(E, I) && neq(F, I);
      origin = haveShallowLine ? vec2(-0.25, 0.0) : vec2(-0.5, 0.0);
      direction.y += haveShallowLine ? 1.0 : 0.0;
      direction.x += haveSteepLine ? 1.0 : 0.0;
    }
    vec3 blendPix = mix(H, D, step(DistYCbCr(E, D), DistYCbCr(E, H)));
    res = mix(res, blendPix, get_left_ratio(pos, origin, direction, scale));
  }

  if (blendResult.y != BLEND_NONE) {
    float dist_B_I = DistYCbCr(B, I);
    float dist_F_A = DistYCbCr(F, A);
    bool doLineBlend = (blendResult.y == BLEND_DOMINANT ||
      !((blendResult.x != BLEND_NONE && !IsPixEqual(E, I)) || (blendResult.z != BLEND_NONE && !IsPixEqual(E, A)) ||
        (IsPixEqual(I, F) && IsPixEqual(F, C) && IsPixEqual(C, B) && IsPixEqual(B, A) && !IsPixEqual(E, C))));
    vec2 origin = vec2(1.0 / sqrt(2.0), 0.0);
    vec2 direction = vec2(-1.0, -1.0);
    if (doLineBlend) {
      bool haveShallowLine = (STEEP_DIRECTION_THRESHOLD * dist_B_I <= dist_F_A) && neq(E, I) && neq(H, I);
      bool haveSteepLine = (STEEP_DIRECTION_THRESHOLD * dist_F_A <= dist_B_I) && neq(E, A) && neq(D, A);
      origin = haveShallowLine ? vec2(0.25, 0.0) : vec2(0.5, 0.0);
      direction.y -= haveShallowLine ? 1.0 : 0.0;
      direction.x -= haveSteepLine ? 1.0 : 0.0;
    }
    vec3 blendPix = mix(F, B, step(DistYCbCr(E, B), DistYCbCr(E, F)));
    res = mix(res, blendPix, get_left_ratio(pos, origin, direction, scale));
  }

  if (blendResult.x != BLEND_NONE) {
    float dist_D_C = DistYCbCr(D, C);
    float dist_B_G = DistYCbCr(B, G);
    bool doLineBlend = (blendResult.x == BLEND_DOMINANT ||
      !((blendResult.w != BLEND_NONE && !IsPixEqual(E, C)) || (blendResult.y != BLEND_NONE && !IsPixEqual(E, G)) ||
        (IsPixEqual(C, B) && IsPixEqual(B, A) && IsPixEqual(A, D) && IsPixEqual(D, G) && !IsPixEqual(E, A))));
    vec2 origin = vec2(0.0, -1.0 / sqrt(2.0));
    vec2 direction = vec2(-1.0, 1.0);
    if (doLineBlend) {
      bool haveShallowLine = (STEEP_DIRECTION_THRESHOLD * dist_D_C <= dist_B_G) && neq(E, C) && neq(F, C);
      bool haveSteepLine = (STEEP_DIRECTION_THRESHOLD * dist_B_G <= dist_D_C) && neq(E, G) && neq(H, G);
      origin = haveShallowLine ? vec2(0.0, -0.25) : vec2(0.0, -0.5);
      direction.x -= haveShallowLine ? 1.0 : 0.0;
      direction.y += haveSteepLine ? 1.0 : 0.0;
    }
    vec3 blendPix = mix(D, B, step(DistYCbCr(E, B), DistYCbCr(E, D)));
    res = mix(res, blendPix, get_left_ratio(pos, origin, direction, scale));
  }

  gl_FragColor = vec4(res, 1.0);
}
)";

// ---------------------------------------------------------------- xBR level 2
static const std::string xbrVertex = R"(
attribute vec4 vPosition;
attribute vec2 vCoordinate;
uniform vec2 textureSize;
varying vec2 texCoord;
varying vec4 t1;
varying vec4 t2;
varying vec4 t3;
varying vec4 t4;
varying vec4 t5;
varying vec4 t6;
varying vec4 t7;
void main() {
  gl_Position = vPosition;
  float dx = 1.0 / textureSize.x;
  float dy = 1.0 / textureSize.y;
  texCoord = vCoordinate;
  texCoord.x *= 1.00000001;
  t1 = vCoordinate.xxxy + vec4(-dx, 0.0, dx, -2.0 * dy);
  t2 = vCoordinate.xxxy + vec4(-dx, 0.0, dx, -dy);
  t3 = vCoordinate.xxxy + vec4(-dx, 0.0, dx, 0.0);
  t4 = vCoordinate.xxxy + vec4(-dx, 0.0, dx, dy);
  t5 = vCoordinate.xxxy + vec4(-dx, 0.0, dx, 2.0 * dy);
  t6 = vCoordinate.xyyy + vec4(-2.0 * dx, -dy, 0.0, dy);
  t7 = vCoordinate.xyyy + vec4(2.0 * dx, -dy, 0.0, dy);
}
)";

static const std::string xbrFragment = precisionHeader + R"(
uniform sampler2D texture;
uniform vec2 textureSize;
uniform float screenDensity;
varying vec2 texCoord;
varying vec4 t1;
varying vec4 t2;
varying vec4 t3;
varying vec4 t4;
varying vec4 t5;
varying vec4 t6;
varying vec4 t7;

#define XBR_EQ_THRESHOLD 15.0
#define lv2_cf 2.0

const vec3 rgbw = vec3(14.352, 28.176, 5.472);
const vec4 Ao = vec4( 1.0, -1.0, -1.0, 1.0);
const vec4 Bo = vec4( 1.0,  1.0, -1.0, -1.0);
const vec4 Co = vec4( 1.5,  0.5, -0.5, 0.5);
const vec4 Ax = vec4( 1.0, -1.0, -1.0, 1.0);
const vec4 Bx = vec4( 0.5,  2.0, -0.5, -2.0);
const vec4 Cx = vec4( 1.0,  1.0, -0.5, 0.0);
const vec4 Ay = vec4( 1.0, -1.0, -1.0, 1.0);
const vec4 By = vec4( 2.0,  0.5, -2.0, -0.5);
const vec4 Cy = vec4( 2.0,  0.0, -1.0, 0.5);
const vec4 Ci = vec4(0.25, 0.25, 0.25, 0.25);

vec4 df(vec4 A, vec4 B) { return vec4(abs(A - B)); }
vec4 diff(vec4 A, vec4 B) { return vec4(notEqual(A, B)); }
vec4 eq(vec4 A, vec4 B) { return step(df(A, B), vec4(XBR_EQ_THRESHOLD)); }
vec4 neq(vec4 A, vec4 B) { return vec4(1.0) - eq(A, B); }
vec4 wd(vec4 a, vec4 b, vec4 c, vec4 d, vec4 e, vec4 f, vec4 g, vec4 h) {
  return (df(a, b) + df(a, c) + df(d, e) + df(d, f) + 4.0 * df(g, h));
}
float c_df(vec3 c1, vec3 c2) {
  vec3 d = abs(c1 - c2);
  return d.r + d.g + d.b;
}

void main() {
  // Blend width follows the real scale on screen.
  float s = max(screenDensity, 1.0);
  vec4 delta   = vec4(1.0 / s);
  vec4 delta_l = vec4(0.5 / s, 1.0 / s, 0.5 / s, 1.0 / s);
  vec4 delta_u = delta_l.yxwz;

  vec2 fp = fract(texCoord * textureSize);

  vec3 A1 = texture2D(texture, t1.xw).xyz;
  vec3 B1 = texture2D(texture, t1.yw).xyz;
  vec3 C1 = texture2D(texture, t1.zw).xyz;
  vec3 A  = texture2D(texture, t2.xw).xyz;
  vec3 B  = texture2D(texture, t2.yw).xyz;
  vec3 C  = texture2D(texture, t2.zw).xyz;
  vec3 D  = texture2D(texture, t3.xw).xyz;
  vec3 E  = texture2D(texture, t3.yw).xyz;
  vec3 F  = texture2D(texture, t3.zw).xyz;
  vec3 G  = texture2D(texture, t4.xw).xyz;
  vec3 H  = texture2D(texture, t4.yw).xyz;
  vec3 I  = texture2D(texture, t4.zw).xyz;
  vec3 G5 = texture2D(texture, t5.xw).xyz;
  vec3 H5 = texture2D(texture, t5.yw).xyz;
  vec3 I5 = texture2D(texture, t5.zw).xyz;
  vec3 A0 = texture2D(texture, t6.xy).xyz;
  vec3 D0 = texture2D(texture, t6.xz).xyz;
  vec3 G0 = texture2D(texture, t6.xw).xyz;
  vec3 C4 = texture2D(texture, t7.xy).xyz;
  vec3 F4 = texture2D(texture, t7.xz).xyz;
  vec3 I4 = texture2D(texture, t7.xw).xyz;

  vec4 b = vec4(dot(B, rgbw), dot(D, rgbw), dot(H, rgbw), dot(F, rgbw));
  vec4 c = vec4(dot(C, rgbw), dot(A, rgbw), dot(G, rgbw), dot(I, rgbw));
  vec4 d = b.yzwx;
  vec4 e = vec4(dot(E, rgbw));
  vec4 f = b.wxyz;
  vec4 g = c.zwxy;
  vec4 h = b.zwxy;
  vec4 i = c.wxyz;

  vec4 i4 = vec4(dot(I4, rgbw), dot(C1, rgbw), dot(A0, rgbw), dot(G5, rgbw));
  vec4 i5 = vec4(dot(I5, rgbw), dot(C4, rgbw), dot(A1, rgbw), dot(G0, rgbw));
  vec4 h5 = vec4(dot(H5, rgbw), dot(F4, rgbw), dot(B1, rgbw), dot(D0, rgbw));
  vec4 f4 = h5.yzwx;

  vec4 fx   = (Ao * fp.y + Bo * fp.x);
  vec4 fx_l = (Ax * fp.y + Bx * fp.x);
  vec4 fx_u = (Ay * fp.y + By * fp.x);

  vec4 irlv0 = diff(e, f) * diff(e, h);
  // corner detection C
  vec4 irlv1 = (irlv0 * (neq(f, b) * neq(f, c) + neq(h, d) * neq(h, g) +
                eq(e, i) * (neq(f, f4) * neq(f, i4) + neq(h, h5) * neq(h, i5)) + eq(e, g) + eq(e, c)));

  vec4 irlv2l = diff(e, g) * diff(d, g);
  vec4 irlv2u = diff(e, c) * diff(b, c);

  vec4 fx45i = clamp((fx   + delta   - Co - Ci) / (2.0 * delta),   0.0, 1.0);
  vec4 fx45  = clamp((fx   + delta   - Co)      / (2.0 * delta),   0.0, 1.0);
  vec4 fx30  = clamp((fx_l + delta_l - Cx)      / (2.0 * delta_l), 0.0, 1.0);
  vec4 fx60  = clamp((fx_u + delta_u - Cy)      / (2.0 * delta_u), 0.0, 1.0);

  vec4 wd1 = wd(e, c, g, i, h5, f4, h, f);
  vec4 wd2 = wd(h, d, i5, f, i4, b, e, i);

  vec4 edri  = step(wd1, wd2) * irlv0;
  vec4 edr   = step(wd1 + vec4(0.1), wd2) * step(vec4(0.5), irlv1);
  vec4 edr_l = step(lv2_cf * df(f, g), df(h, c)) * irlv2l * edr;
  vec4 edr_u = step(lv2_cf * df(h, c), df(f, g)) * irlv2u * edr;

  fx45  = edr   * fx45;
  fx30  = edr_l * fx30;
  fx60  = edr_u * fx60;
  fx45i = edri  * fx45i;

  vec4 px = step(df(e, f), df(e, h));
  vec4 maximos = max(max(fx30, fx60), max(fx45, fx45i));   // smooth tips

  vec3 res1 = E;
  res1 = mix(res1, mix(H, F, px.x), maximos.x);
  res1 = mix(res1, mix(B, D, px.z), maximos.z);

  vec3 res2 = E;
  res2 = mix(res2, mix(F, B, px.y), maximos.y);
  res2 = mix(res2, mix(D, H, px.w), maximos.w);

  vec3 res = mix(res1, res2, step(c_df(E, res1), c_df(E, res2)));
  gl_FragColor = vec4(res, 1.0);
}
)";

// ---------------------------------------------------------------- colour adjust
// Wraps the last pass: its main() becomes ndMain() writing into ndColor, then the
// adjustment is applied. ND_BRIGHTNESS (-0.5..0.5), ND_CONTRAST (0.5..1.5), ND_SATURATION (0..2).
static std::string wrapWithColorAdjust(
    std::string fragment, const std::string& brightness, const std::string& contrast, const std::string& saturation
) {
    auto replaceAll = [](std::string& s, const std::string& from, const std::string& to) {
        size_t pos = 0;
        while ((pos = s.find(from, pos)) != std::string::npos) { s.replace(pos, from.size(), to); pos += to.size(); }
    };
    size_t mainPos = fragment.find("void main()");
    if (mainPos == std::string::npos) return fragment;

    replaceAll(fragment, "gl_FragColor", "ndColor");
    mainPos = fragment.find("void main()");
    fragment.replace(mainPos, std::string("void main()").size(), "mediump vec4 ndColor;\nvoid ndMain()");

    std::string defines =
        "#define ND_BRIGHTNESS " + brightness + "\n" +
        "#define ND_CONTRAST " + contrast + "\n" +
        "#define ND_SATURATION " + saturation + "\n";

    return defines + fragment + R"(
void main() {
  ndColor = vec4(0.0, 0.0, 0.0, 1.0);
  ndMain();
  mediump vec3 ndc = (ndColor.rgb - 0.5) * ND_CONTRAST + 0.5 + ND_BRIGHTNESS;
  mediump float ndl = dot(ndc, vec3(0.2126, 0.7152, 0.0722));
  ndc = mix(vec3(ndl), ndc, ND_SATURATION);
  gl_FragColor = vec4(clamp(ndc, 0.0, 1.0), 1.0);
}
)";
}

} // namespace neonshaders
} // namespace libretrodroid

#endif // LIBRETRODROID_NEONSHADERS_H
