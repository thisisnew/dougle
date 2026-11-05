# Dougle diagram style

User-approved direction: retain the existing Dougle white background, blue focal color and clean sans-serif interface. This profile applies to the two README figures; it is not a global replacement for the installed diagram-design defaults.

## Tokens

| Role | Value |
|---|---|
| Paper | #ffffff |
| Ink | #292f3e |
| Muted text | #56647a |
| Default connectors / borders | #637087 |
| Accent | #2866c2 |
| Accent surface | #edf4ff |
| Sans | Geist, Apple SD Gothic Neo, Malgun Gothic, system-ui, sans-serif |
| Technical labels | Geist Mono, ui-monospace, monospace |
| Font source | System; no web requests |

Title28px, node names16px, supporting Korean labels12px, general introduction14px. Two focal nodes at most. Node geometry uses a4px grid, radius6px; flowchart terminal radius20px. Orthogonal connections use rounded8px bends. Arrow labels sit above or beside their paths on white masks.

## Layout and meaning

- knowledge-sharing: architecture overview,960×504; material, curated local knowledge, selected team package, recipient merge, recipient answer. Publish/fetch are explicit operations.
- ask-flow: flowchart,960×648; question, scoped retrieval, evidence/freshness decision, original verification, sourced answer. Unknown evidence remains unknown after failed access.
- HTML keeps a local horizontal scroller on narrow screens. GitHub embeds exported SVGs with image alt text; the original SVG can be opened separately for zooming.
- No scripts, external images, fonts, shadows or hidden content. SVGs include title/description accessibility labels.

Created using [diagram-design](https://github.com/cathrynlavery/diagram-design/tree/f24d98b05e2d7a5e68c2dedfe46e5b7173f50118/skills/diagram-design). [MIT notice](diagram-design-LICENSE).
