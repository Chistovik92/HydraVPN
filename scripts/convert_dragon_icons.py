#!/usr/bin/env python3
"""
Convert dragon PNG artwork into SVG drawable resources with color-theme variants.

Outputs (per source image NAME):
  <out>/ic_dragon_<NAME>.svg            original colors
  <out>/ic_dragon_<NAME>_light.svg      light-theme variant (reads on light backgrounds)
  <out>/ic_dragon_<NAME>_dark.svg       dark-theme variant  (reads on dark backgrounds)
  <out>/ic_dragon_<NAME>_mono.svg       monochrome silhouette (currentColor / tintable)

Original source PNGs are never modified. Old icon variants are never deleted.
"""
import argparse
import colorsys
import os
import re
import sys

try:
    import vtracer
except ImportError:
    sys.stderr.write("vtracer is required: python -m pip install vtracer\n")
    raise

HEX_RE = re.compile(r"#[0-9a-fA-F]{3,8}")
FILL_ATTR_RE = re.compile(r'(fill\s*=\s*")(#[0-9a-fA-F]{3,8})(")')
STOP_RE = re.compile(r'(stop-color\s*=\s*")(#[0-9a-fA-F]{3,8})(")')


def clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, x))


def expand_hex(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    r = int(h[0:2], 16)
    g = int(h[2:4], 16)
    b = int(h[4:6], 16)
    return r, g, b


def to_hex(r: int, g: int, b: int) -> str:
    return "#%02x%02x%02x" % (r, g, b)


def adjust_lightness(hex_color: str, d_lightness: float, sat_scale: float = 1.0) -> str:
    r, g, b = expand_hex(hex_color)
    h, l, s = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
    l = clamp(l + d_lightness)
    s = clamp(s * sat_scale)
    rr, gg, bb = colorsys.hls_to_rgb(h, l, s)
    return to_hex(round(rr * 255), round(gg * 255), round(bb * 255))


def recolor_svg(svg: str, d_lightness: float, sat_scale: float = 1.0) -> str:
    def repl_fill(m: re.Match) -> str:
        return m.group(1) + adjust_lightness(m.group(2), d_lightness, sat_scale) + m.group(3)

    out = FILL_ATTR_RE.sub(repl_fill, svg)
    out = STOP_RE.sub(repl_fill, out)
    return out


def mono_svg(svg: str) -> str:
    """Flatten every fill to currentColor for tinting; keep geometry."""
    out = FILL_ATTR_RE.sub(lambda m: m.group(1) + "currentColor" + m.group(3), svg)
    out = STOP_RE.sub(lambda m: m.group(1) + "currentColor" + m.group(3), out)
    return out


def trace_png(png_path: str, svg_path: str) -> None:
    vtracer.convert_image_to_svg_py(
        png_path,
        svg_path,
        colormode="color",
        hierarchical="stacked",
        mode="spline",
        filter_speckle=4,
        color_precision=6,
        layer_difference=16,
        corner_threshold=60,
        length_threshold=4.0,
        max_iterations=10,
        splice_threshold=45,
        path_precision=3,
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("sources", nargs="+", help="Input PNG files")
    ap.add_argument("--out", required=True, help="Output directory for SVGs")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    for src in args.sources:
        if not os.path.isfile(src):
            sys.stderr.write(f"skip (not a file): {src}\n")
            continue
        name = os.path.splitext(os.path.basename(src))[0].lower()
        name = re.sub(r"[^a-z0-9]+", "_", name).strip("_")

        base_svg_path = os.path.join(args.out, f"ic_dragon_{name}.svg")
        print(f"tracing {src} -> {base_svg_path}")
        trace_png(src, base_svg_path)

        with open(base_svg_path, "r", encoding="utf-8") as fh:
            svg = fh.read()

        variants = {
            f"ic_dragon_{name}_light.svg": recolor_svg(svg, d_lightness=-0.12, sat_scale=1.05),
            f"ic_dragon_{name}_dark.svg": recolor_svg(svg, d_lightness=+0.12, sat_scale=0.95),
            f"ic_dragon_{name}_mono.svg": mono_svg(svg),
        }
        for fname, content in variants.items():
            with open(os.path.join(args.out, fname), "w", encoding="utf-8") as fh:
                fh.write(content)

    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
