# MultiClone Android UI work

For Android UI/UX changes, read and apply these installed Codex skills:
- `~/.codex/skills/mobile-android-design/SKILL.md`
- `~/.codex/skills/ui-ux-pro-max/SKILL.md`

Use Google's official Android adaptive guidance as the Android-specific authority when generic web breakpoints or examples in a skill differ:
- https://developer.android.com/develop/ui/compose/layouts/adaptive
- https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
- https://developer.android.com/develop/ui/compose/layouts/adaptive/adaptive-dos-and-donts

See `docs/ui/TABLET_UI_AUDIT.md` for the audit, source provenance, design decisions and verification.

Product requirements:
- Create and Library remain separate destinations at every window size. Do not embed Library alongside Create in landscape.
- Use available app window dimensions, including Samsung multi-window and DeX, rather than device model or orientation checks.
- Keep forms bounded and centered, with 48dp minimum touch targets and wrapping action rows.
- Detailed compatibility warnings start collapsed; show blocked status without expanding and prevent blocked generation.
- Offer installation in a dialog as clones finish generating. Retain explicit user initiation and Android's confirmation for each install.
- Preserve form, warning disclosure and navigation state across resizing and rotation. Support system text size and light/dark themes.

Use the project-local toolchain: `scripts/gradle-local.sh`. Keep dependency locks and verification metadata intact. Never print or alter signing credentials under `private/` for UI work.
