# Dougle diagram style

User-guide illustrations use visual-explainer. Structural/design diagrams use native Excalidraw scenes and PNG exports. Both preserve Dougle white and blue.

- Background #ffffff; text #292f3e; secondary #56647a; accent #2866c2; tint #edf4ff.
- Korean fonts: Apple SD Gothic Neo, Malgun Gothic, sans-serif. Phrase-based wrapping.
- Titles32px; stage headings24–26px; body19–20px; supporting text18px; commands16px.
- Sharing: left-to-right three stages, stacking below650px. Other diagrams: top-to-bottom three steps with conditions below each action; phrase-based wrapping on narrow screens.
- Sources: *.src.html; skill renderer produces *.html. README/docs embed2x PNG exports at1000px CSS width to preserve Korean typography. Static PNG scales on mobile; it does not reflow.
- Renderer390/1280 light/dark inspection and PNG visual review. Do not claim screenshots verify product behavior.
- No reverse arrows, English category tags or small diamond labels. Keep source access failures, evidence limits and refresh conditions explicit.

See [production notes and attribution](README.md).

## Structure/design scenes

- Hand-authored `.excalidraw` source; high-resolution PNG for GitHub. No Mermaid.
- `roughness: 0`, white/blue palette above; sans-serif Korean fallback (fontFamily 2).
- Measure actual Korean text widths, use explicit phrase breaks, never truncate labels.
- Use only three or four ideas per figure, short labels and recognizable icons. Put record fields and cardinality in the developer reference.
- Native source keeps full element IDs, arrow bindings and measurable text bounds.
- Each figure answers one question. Keep implementation references in adjacent Markdown.
- Inspect actual PNGs, labels, routes and a README-sized preview before publishing.
- Current diagrams describe implemented behavior; channel-wide inquiry detection remains a separate proposal.
