# TensorSpeak brand assets

Source files for the app icon and documentation graphics.

| File | Use |
| --- | --- |
| [`icon-master.svg`](icon-master.svg) | Square mark (512×512 artboard); edit here first |
| [`logo-wordmark.svg`](logo-wordmark.svg) | Horizontal lockup for docs or slides |
| [`icon-512.png`](icon-512.png) | README / GitHub preview (export from master SVG) |

## Colors

| Name | Hex |
| --- | --- |
| Background | `#15182B` |
| Wave (start → end) | `#6EE7B7` → `#818CF8` |
| Nodes | `#F8FAFC`, `#A5B4FC` |

## Android launcher

Adaptive icon layers live under [`android/app/src/main/res/`](../../android/app/src/main/res/): `ic_launcher_foreground`, `ic_launcher_monochrome`, and `mipmap-anydpi-v26/ic_launcher.xml`. Keep foreground art inside the **66×66 dp** safe zone (centered in the 108×108 adaptive canvas).

Do not stretch non-square assets; scale uniformly.

## Regenerating `icon-512.png`

```bash
nix shell nixpkgs#librsvg --command rsvg-convert -w 512 -h 512 docs/branding/icon-master.svg -o docs/branding/icon-512.png
```
