# WiFiBar brand

## Concept: "Sweep"
The mark is WiFiBar's direction sweep, plotted rather than drawn. When you turn in a circle, the app smooths the signal around you with a circular Gaussian (σ = 25°). In polar form, one strong direction becomes the beam r(θ) = exp(−(θ−θ₀)²/2σ²). The beam is aimed (θ₀ = 45°) at a dot, the router, which sits on the sweep circle. On the app icon, a faint arc of that circle and a cyan glow round the dot show the turn and the source.

All artwork comes from `make_brand.py`. The icon body is the superellipse |x|⁵ + |y|⁵ = 1.

## Palette
| Name | Hex | Used for |
|---|---|---|
| Ink Navy | `#0A1A3A` | Icon gradient (bottom); the mark on light backgrounds; text |
| Signal Blue | `#1B4F9E` | Icon gradient (top) |
| Beam Cyan | `#46C8FF` | Glow at the router dot; sparing accents only |
| Beam Pale | `#A6DCFF` | Tail of the beam gradient (it fades to white at the tip) |
| White | `#FFFFFF` | The mark on the icon and on dark backgrounds |
| Graphite | `#1C1C1E` | Dark neutral background (light neutral: `#F5F5F7`) |

Typeface: SF Pro, the system font. The wordmark is SF Pro Semibold. In the app, use the system font and never ship SF Pro files.

## Clear space and minimum size
- **Clear space:** keep one router-dot diameter (D) free on every side of the mark, and between the mark and the wordmark.
- **Minimum sizes:** 16 px for the app icon, 16 px across for the mark.
- **Small drawing:** the 16 and 32 px icons use a bolder drawing, with a bigger dot and gap and no glow or arc. Don't shrink the 1024 artwork by hand for those sizes.

## Do
- Keep the beam aimed up and to the right (45°), with the dot on its axis.
- Use the mark in one colour: Ink Navy on light, White on dark. `out/mark-monochrome.png` is black-on-transparent for template use.
- Regenerate everything with the script; don't edit the PNGs by hand.

## Don't
- Don't rotate, stretch, outline or add a drop shadow to the mark.
- Don't put the dot inside the beam (that reads as a map pin).
- Don't add Wi-Fi arcs or radar rings.
- Don't put the mark on busy photos, or use Beam Cyan for large areas.

## Files
Everything is generated into `Branding/out/`:
- `AppIcon-1024.png`, `AppIcon.iconset/` (10 sizes) and `AppIcon.icns`
- `mark-monochrome.png`
- `brand-sheet.png`
- Android adaptive-icon layers (432 px each, mark inside the 264 px safe circle) in `android/`:
  - `ic_launcher_foreground.png`
  - `ic_launcher_background.png`
  - `ic_launcher_monochrome.png`
  - `preview.png`

## Regenerate
```sh
python3 "Branding/make_brand.py"
```
- **Requirements:** Python 3 with matplotlib, numpy and Pillow, plus `/usr/bin/iconutil`. The system `python3` on this Mac already has these Python packages.
- **No system packages?** Create a virtualenv outside the project and run the script with it:
  ```sh
  python3 -m venv /private/tmp/claude-501/-Users-charantejapampana-Projects-WIFI-widget/48eaa0a2-2488-43ca-ac79-881c3274174a/scratchpad/brand-venv
  …/brand-venv/bin/pip install matplotlib numpy pillow
  …/brand-venv/bin/python "Branding/make_brand.py"
  ```
- **Deterministic:** the script uses no randomness, so re-running gives byte-identical files.

## Rejected concepts
- **A path-loss "W"** (two access points sharing one name): rejected because it reads as Microsoft Word.
- **Tangent quality-threshold circles:** rejected because they read as an eyeball.
- **A halftone "hotspot" dot grid:** rejected because it was muddy at 16 px.
