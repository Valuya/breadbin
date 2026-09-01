#!/usr/bin/env python3
"""Draws the store artwork Play asks for, from the launcher icon's own vectors.

Two pictures, neither of which the Console will accept a vector for:

    store/art/icon-512.png      the listing icon
    store/art/feature-1024.png  the 1024x500 banner at the top of the listing

Crakinoku's `tools/make_store_art.py` in `Valuya/sdk` redraws its icon's grid in
Pillow and keeps the colours in step by hand. This one does not have to: an
Android `<vector>`'s `pathData` *is* SVG path data, and its `fillColor` is an
SVG fill, so the drawables are translated to SVG and rendered. The breadbin in
the listing is therefore the same drawing as the breadbin on the home screen,
and stays so without anybody remembering to edit this file.

    python3 tools/make_store_art.py

Needs Pillow — the feature graphic has to set type — and Inkscape, to rasterise.
Neither is part of a build; this is run by a person, once, before an upload.
"""
import pathlib
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ElementTree

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("This needs Pillow: pip install Pillow")

ANDROID = "{http://schemas.android.com/apk/res/android}"
RES = pathlib.Path("app/src/main/res/drawable")
OUT = pathlib.Path("store/art")

# The one colour this file states rather than reads: it is the launcher
# background's fill, and it is asserted against the drawable at run time below
# so the two cannot quietly disagree.
NAVY = "#2E2C9B"

# Off the badge stripe in ic_launcher_foreground.xml — the yellow of the four
# squares, used for the tagline so the banner's type belongs to the picture.
AMBER = (0xED, 0xF1, 0x71)
WHITE = (0xFF, 0xFF, 0xFF)

TAGLINE = "No ads. Disks, tapes and cartridges, on your phone."

FONTS = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-%s.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf",
]


def font(size, bold=False):
    for pattern in FONTS:
        if "Liberation" in pattern:
            weight = "Bold" if bold else "Regular"
        else:
            weight = "-Bold" if bold else ""
        path = pathlib.Path(pattern % weight)
        if path.exists():
            return ImageFont.truetype(str(path), size)
    sys.exit("No usable font found; install fonts-liberation or fonts-dejavu")


def read_vector(name):
    """An Android <vector> as (viewport-side, [(path-data, fill), ...]).

    Only the subset the launcher icon actually uses is understood: flat <path>
    children with a literal fillColor. A group, a gradient or a clip would be
    silently dropped, so anything unrecognised is refused loudly instead —
    a store icon missing a layer is not a thing to discover after upload.
    """
    root = ElementTree.parse(RES / name).getroot()
    width = float(root.get(f"{ANDROID}viewportWidth"))
    height = float(root.get(f"{ANDROID}viewportHeight"))
    if width != height:
        sys.exit(f"{name}: viewport is {width}x{height}, expected a square")

    paths = []
    for child in root:
        tag = child.tag.split("}")[-1]
        if tag != "path":
            sys.exit(f"{name}: <{tag}> is not understood by this script")
        data = child.get(f"{ANDROID}pathData")
        fill = child.get(f"{ANDROID}fillColor")
        if not data or not fill:
            sys.exit(f"{name}: a <path> has no pathData or no fillColor")
        paths.append((data, fill))
    if not paths:
        sys.exit(f"{name}: no paths found")
    return width, paths


def render(paths, viewport, pixels, background=None):
    """Rasterise paths from a square viewport into a `pixels` square RGBA PNG."""
    body = "".join(
        f'<path d="{data}" fill="{fill}"/>' for data, fill in paths
    )
    plate = f'<rect width="{viewport}" height="{viewport}" fill="{background}"/>' if background else ""
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{pixels}" height="{pixels}" '
        f'viewBox="0 0 {viewport} {viewport}">{plate}{body}</svg>'
    )
    with tempfile.TemporaryDirectory() as work:
        source = pathlib.Path(work) / "art.svg"
        target = pathlib.Path(work) / "art.png"
        source.write_text(svg)
        result = subprocess.run(
            ["inkscape", str(source), "--export-type=png", f"--export-filename={target}",
             f"--export-width={pixels}", f"--export-height={pixels}"],
            capture_output=True, text=True,
        )
        if result.returncode != 0 or not target.exists():
            sys.exit(f"inkscape failed: {result.stderr.strip() or result.stdout.strip()}")
        return Image.open(target).convert("RGBA")


def machine(pixels):
    """Just the breadbin, cropped to itself and rendered `pixels` wide.

    The foreground drawable draws the machine into the middle of a 108 viewport
    and leaves a wide margin, because an adaptive icon's outer ring is bled off
    by the launcher. Here that margin is unwanted twice over — the store icon
    sets its own, and the banner places the machine against type — so the paths
    are re-framed onto the case's own bounding box, which
    `ic_launcher_foreground.xml` draws from (20,38) to (88,76).
    """
    viewport, paths = read_vector("ic_launcher_foreground.xml")
    left, top, width, height = 20.0, 38.0, 68.0, 38.0
    body = "".join(f'<path d="{data}" fill="{fill}"/>' for data, fill in paths)
    tall = round(pixels * height / width)
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{pixels}" height="{tall}" '
        f'viewBox="{left} {top} {width} {height}">{body}</svg>'
    )
    with tempfile.TemporaryDirectory() as work:
        source = pathlib.Path(work) / "machine.svg"
        target = pathlib.Path(work) / "machine.png"
        source.write_text(svg)
        result = subprocess.run(
            ["inkscape", str(source), "--export-type=png", f"--export-filename={target}",
             f"--export-width={pixels}", f"--export-height={tall}"],
            capture_output=True, text=True,
        )
        if result.returncode != 0 or not target.exists():
            sys.exit(f"inkscape failed: {result.stderr.strip() or result.stdout.strip()}")
        return Image.open(target).convert("RGBA")


def check_background():
    """The navy above is the drawable's, and this is what says so."""
    _, paths = read_vector("ic_launcher_background.xml")
    fills = {fill.upper() for _, fill in paths}
    if fills != {NAVY.upper()}:
        sys.exit(
            f"ic_launcher_background.xml is {', '.join(sorted(fills))}, "
            f"but this script draws {NAVY}. Update NAVY."
        )


def icon():
    """512x512, full bleed. Play rounds the corners itself, so this must not."""
    size = 512
    image = Image.new("RGBA", (size, size), NAVY)
    # 72% of the width, which is about what the launcher's mask leaves visible
    # of the 108 viewport, and optically centred: the machine reads as
    # bottom-heavy, so its box sits a shade above the middle.
    art = machine(round(size * 0.72))
    image.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2 - round(size * 0.02)))
    save(image, "icon-512.png")


def feature():
    """1024x500. Play crops the edges on some surfaces, so nothing lives there."""
    width, height = 1024, 500
    image = Image.new("RGBA", (width, height), NAVY)

    art = machine(360)
    image.alpha_composite(art, (96, (height - art.height) // 2))

    draw = ImageDraw.Draw(image)
    title = font(96, bold=True)
    tagline = font(34)
    # Kept well inside the right edge: Play crops a feature graphic differently
    # on different surfaces, and a name with its last letter shaved off is the
    # one mistake in this picture nobody would forgive.
    draw.text((516, 178), "Breadbin", font=title, fill=WHITE)
    for index, line in enumerate(wrap(TAGLINE, tagline, draw, 412)):
        draw.text((520, 290 + index * 44), line, font=tagline, fill=AMBER)
    save(image, "feature-1024.png")


def wrap(text, typeface, draw, limit):
    """Greedy wrap, because a tagline is a handful of words and never more."""
    lines, line = [], ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if draw.textlength(candidate, font=typeface) <= limit or not line:
            line = candidate
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def save(image, name):
    OUT.mkdir(parents=True, exist_ok=True)
    # Play takes a 32-bit PNG but the alpha is meaningless on an opaque
    # rectangle, and a store icon with a stray transparent pixel is rejected.
    image.convert("RGB").save(OUT / name)
    print(f"  {name}  {image.width}x{image.height}")


if __name__ == "__main__":
    check_background()
    print("Drawing store art into store/art:")
    icon()
    feature()
