# MapSafe Mobile paper results

This folder contains the paper-ready Results section and the Android screenshots used by its figures.

## Files

- `mapsafe_results.tex` is an `\input{}`-ready Results section.
- `mapsafe_results_preview.tex` is a small two-column wrapper for checking the section independently.
- `mapsafe-paper-screenshots.zip` contains the 24 numbered publication PNGs.
- `screenshots/paper-01-...png` through `screenshots/paper-24-...png` are the publication captures.
- `screenshots/raw/` contains the original test capture names, where retained.

The Results text uses all 24 numbered screenshots in seven grouped figures:

1. workflow selection and feature entry points;
2. anonymisation controls;
3. Spruill assessment, remasking, and map output;
4. identity creation and public-key exchange;
5. multi-recipient encryption and signing;
6. decryption, verification, and viewing; and
7. the current blockchain/notarisation boundary.

## Include in another manuscript

The parent manuscript needs `graphicx`, `subcaption`, `booktabs`, `tabularx`, and `amsmath`. If it is compiled from this folder, use:

```latex
\input{mapsafe_results.tex}
```

If the manuscript is compiled from the repository root, override the image directory before the input:

```latex
\newcommand{\mapsafefigdir}{paper/mapsafe-results/screenshots}
\input{paper/mapsafe-results/mapsafe_results.tex}
```

To build the supplied preview, run the LaTeX engine from `paper/mapsafe-results` so that the default `screenshots` path resolves correctly.

## Interpretation note

The encryption, signing, decryption, anonymisation, Spruill measurement, local key protection, and public-key trust workflow shown here are implemented application states. The production blockchain client is not implemented in the current build. Its screenshots are intentionally labelled unavailable/future work, and the Results section does not present a simulated transaction as a real notarisation.
