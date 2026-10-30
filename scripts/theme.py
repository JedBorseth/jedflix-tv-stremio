#!/usr/bin/env python3
"""Apply the same black/red palette to XML resources and Compose colors."""
import colorsys
import re
import sys
from pathlib import Path

RED = "e53945"


def recolor(value, name=""):
    rgb = value[-6:]
    alpha = value[1:-6]
    r, g, b = (int(rgb[i:i + 2], 16) / 255 for i in (0, 2, 4))
    hue, lightness, saturation = colorsys.rgb_to_hls(r, g, b)
    if re.fullmatch(r"background(?:_(?:dark|light)[1-5])?(?:_\d+)?", name) or name in {
        "primaryColor", "secondaryColor", "tertiaryColor",
    }:
        # Remove the violet tint while preserving the surface hierarchy and alpha.
        grey = min(int(rgb[i:i + 2], 16) for i in (0, 2, 4))
        rgb = f"{grey:02x}" * 3
    elif name in {"accentColor", "accentColorSecondary"}:
        rgb = RED
    elif 0.62 < hue < 0.93 and saturation > 0.12:
        red_hue = colorsys.rgb_to_hls(229 / 255, 57 / 255, 69 / 255)[0]
        rgb = "".join(f"{round(c * 255):02x}" for c in
                      colorsys.hls_to_rgb(red_hue, lightness, saturation))
    return "#" + alpha + rgb


def apply(root):
    palette = {}
    changes = 0
    for resource in (root / "res").rglob("*.xml"):
        source = resource.read_text()
        if resource.name == "colors.xml":
            def color(match):
                name, value = match[2], match[3]
                updated = recolor(value, name)
                if resource.parent.name == "values":
                    palette[name] = updated
                return match[1] + updated + match[4]
            updated = re.sub(r'(<color name="([^"]+)">)(#[0-9a-fA-F]{6,8})(</color>)', color, source)
        else:
            updated = re.sub(r"#[0-9a-fA-F]{6,8}\b", lambda m: recolor(m[0], "background" if resource.name == "ic_background.xml" else ""), source)
        if updated != source:
            resource.write_text(updated)
            changes += 1

    colors = root / "smali_classes6/com/stremio/colors/Colors.smali"
    source = colors.read_text()
    # Match only color initializers, never arbitrary bytecode constants or IDs.
    pattern = (r"(    const(?:-wide)?(?:/\w+)? v0, )([^\n]+)"
               r"(\n\s*(?:\.line \d+\s*)?invoke-static \{v0(?:, v1)?\}, "
               r"Landroidx/compose/ui/graphics/ColorKt;->Color\(([IJ])\)J"
               r".*?sput-wide v0, Lcom/stremio/colors/Colors;->([^:]+):J)")
    count = 0
    def initializer(match):
        nonlocal count
        name = match[5]
        if name not in palette:
            raise ValueError(f"Missing XML palette entry for Compose color: {name}")
        value = int(palette[name][1:], 16)
        if match[4] == "I" and value >= 0x80000000:
            value -= 0x100000000
        literal = (f"-0x{-value:x}" if value < 0 else f"0x{value:x}")
        if match[4] == "J":
            literal += "L"
        count += 1
        return match[1] + literal + match[3]
    updated = re.sub(pattern, initializer, source, flags=re.S)
    if count != len(palette.keys() & set(re.findall(r"sput-wide v0, Lcom/stremio/colors/Colors;->([^:]+):J", source))):
        raise ValueError("Upstream Compose color initializer format changed")
    if count == 0:
        raise ValueError("No Compose colors found")
    colors.write_text(updated)
    tv = root / "smali_classes5/com/stremio/tv/Colors.smali"
    source = tv.read_text()
    tv_colors = {
        "primaryBackground": "ff0b0b0b",
        "tertiaryBackground": "1fe8e8e8",
        "primaryAccent": "ffe53945",
    }
    for name, value in tv_colors.items():
        pattern = (r"(    const(?:-wide)? v0, )[^\n]+"
                   r"(\n\s*\.line \d+\s*invoke-static [^\n]+\n\s*move-result-wide v0\s*"
                   r"sput-wide v0, Lcom/stremio/tv/Colors;->" + name + r":J)")
        literal = "0x" + value + ("L" if value.startswith("ff") else "")
        source, matched = re.subn(pattern, lambda m: m[1] + literal + m[2], source, flags=re.S)
        if matched != 1:
            raise ValueError(f"Upstream TV palette changed: {name}")
    tv.write_text(source)
    print(f"Applied black/red theme: {changes} XML files, {count} Compose colors")


if __name__ == "__main__":
    apply(Path(sys.argv[1]))
