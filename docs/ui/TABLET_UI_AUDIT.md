# Tablet UI audit and redesign — 2026-10-23

Target: Samsung Galaxy Tab devices, particularly the Galaxy Tab S9 FE+, using Kotlin/Jetpack Compose and Material 3. Also account for portrait, landscape, split-screen and DeX windows.

## Skills installed and configured

Installed using Codex's built-in skill-installer script, after checking both repository trees and reading the actual SKILL.md and available installation guidance. Pinned source commits make this installation reproducible:

| Skill | Verified repository path | Source commit | Installed directory |
| --- | --- | --- | --- |
| mobile-android-design | `wshobson/agents/plugins/ui-design/skills/mobile-android-design` | `0874a27b17a22e2b7c2e2bcd725768e0803cad06` | `/Users/ariyen/.codex/skills/mobile-android-design` |
| ui-ux-pro-max | `Hitbullets/codex-skills/codex-frontend-design/skills/ui-ux-pro-max` | `56b113571f1e67c25f9867bded2408f30ba1c07a` | `/Users/ariyen/.codex/skills/ui-ux-pro-max` |

The requested Hitbullets skill is its self-contained adaptation; its repository does not contain the search/database scripts from other similarly named skill distributions. No extra package manager or scripts were required. The Android skill's reference files were installed with its directory.

Both skill bodies were read and applied to this work. They become automatically discoverable in the next Codex turn. The root `AGENTS.md` configures their continued use for this project.

Google's documentation is an authoritative guidance source, not an installable Codex skill. Its official links are configured in `AGENTS.md`; no third-party package is presented as a Google skill.

Sources: [Android design skill](https://github.com/wshobson/agents/tree/0874a27b17a22e2b7c2e2bcd725768e0803cad06/plugins/ui-design/skills/mobile-android-design), [UI/UX skill](https://github.com/Hitbullets/codex-skills/tree/56b113571f1e67c25f9867bded2408f30ba1c07a/codex-frontend-design/skills/ui-ux-pro-max).

## Findings in the existing application

| Priority | Source evidence before change | User impact | Redesign |
| --- | --- | --- | --- |
| High | `MultiCloneScreen` used `maxWidth >= 900.dp` to render CreatePanel and DashboardPanel together, each at weight 1 | Landscape unexpectedly showed two independent destinations, with two scrolling panes and squeezed controls | One destination per window size. Side rail at 600dp+, bottom navigation in smaller windows |
| High | Create form filled all available width below that branch | Controls and text became excessively wide in portrait; inconsistent proportions across orientations | Centered Create form capped at 840dp including its gutters; Library capped at 1040dp |
| High | `source.findings.forEach` rendered every warning unconditionally | Long warnings displaced the name, quantity and generation controls | Collapsed details with a count. Blocking status and generation eligibility always remain visible |
| High | Install actions existed only on Dashboard cards | User had to leave Create and locate the generated copy | Generation event opens a Material 3 install dialog with per-copy actions. Later leaves a Review & install action on Create |
| Medium | Navigation was three unselected-style text buttons under a tall dark header | Weak selected state, unnecessary vertical chrome on tablets | Material navigation rail/bar with labels, selection indicators and locally drawn line icons |
| Medium | Single flat Create column mixed import, warnings, configuration and primary action | Weak grouping and dense text made the workflow hard to scan | Numbered app-selection/customization cards, separate compatibility summary, one prominent Create action |
| Medium | Primary/secondary action rows used fixed `Row` placement | Narrow windows and enlarged text could squeeze or clip controls | Wrapping FlowRows, full-width form fields and minimum 48dp action heights |
| Medium | Hardcoded light palette and white/navy components | Poor system dark-mode behavior and inconsistent Material surfaces | Dynamic Material 3 colors on Android 12+, fallback palettes on older Android, semantic component colors and system-bar contrast |
| Medium | Long activity messages expanded above the form | Persistent diagnostics competed with task content | Two-line status preview, explicit Details dialog and Dismiss action |
| Medium | Tab content was removed from composition without a state holder | Returning to Create could reset text and scroll | SaveableStateHolder for destinations; saveable source/name/quantity/disclosure state |

## Material 3 layout proposal implemented

Use calm tonal surfaces, consistent 8dp spacing, 20–24dp card corners, and the system's Material typography and colors. The header identifies the product and on-device processing; it no longer competes with the task heading.

Create follows this order: task heading → ready-copy action when relevant → choose app/source card → compact compatibility summary → customization card → Create action → active queue status. The library remains a distinct management screen. Tablet width creates breathing room around the form rather than stretching fields or introducing an unrelated destination.

The navigation breakpoint uses the actual root layout constraints after system insets. It uses the official 600dp compact/medium boundary; content decisions do not assume that a Samsung device is always full-screen. This implementation uses existing Compose measurement APIs and Material 3 components, retaining the project's current locked dependencies. It does not add the Material adaptive library merely to implement a rail/bar switch. A future list-detail library could use the official adaptive scaffolds if clone-management requirements grow.

The install window is an app dialog, followed by Android's package-installer confirmation when the user taps Install. It is not a separate Android activity or a forced automatic installation. It stays open when returning from installation-permission settings. Long installation messages are capped with an Installation details action for the complete text. Generated and installed versions remain distinct.

A generation event adds each successfully published new clone to the session's ready list. Dismissing the window suppresses further automatic openings until the next user-submitted generation request; later copies remain accessible through Review & install. Rotation preserves the ViewModel's dialog state. After a full process restart, existing outputs remain available in Library; old outputs do not trigger a fresh modal on launch.

## Official adaptive guidance

Google recommends decisions based on the app window's available space, retaining state during configuration changes and testing multiple sizes and text configurations. The design applies those principles to a single task-focused form rather than assuming every expanded screen needs two panes.

- [Adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive)
- [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)

## Verification

See `VERIFICATION.md` for commands, results and screenshots from the implemented UI. Emulator results validate layout and app behavior; physical Samsung One UI, S Pen input, keyboard/trackpad behavior and device-specific installation policies still need a device pass.

## Library and installation refinement (v0.1.5)

Removed the successful-analysis banner, manual Mark tested working action and routine runtime-untested wording. Existing TESTED records are normalized during reconciliation without a database reset. Detailed source compatibility findings and blocked generation remain intact.

A never-installed clone has Install; an installed clone has one Update action. Update checks the installed original's version, imports its complete current base/split APK set, validates its package and original signing certificates through the existing queue, and rebuilds with the clone's existing plan and signing identity. It then opens Android's confirmation directly. The original must first be updated through its store; this action does not query Play or download arbitrary third-party APKs. An already prepared newer clone is reused after cancellation. Equal/older originals do not enqueue redundant work.

Each clone shows a Material progress bar during verification, APK transfer and the pending Android confirmation. Repeated taps are disabled until completion, cancellation or failure; PackageInstaller callbacks and session reconciliation restore/reset the visible operation state. Progress uses a labelled polite live region, theme colors and wrapping action rows. Create and Library remain separate and all new primary actions have 48dp minimum height.

Verification includes an on-device version 1→2 original/clone update, cancelled native confirmation and cached retry, unchanged package/certificate, and retained counter/provider/mock account data. See VERIFICATION.md for results and screenshots. Android's [PackageInstaller callbacks](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionCallback) provide completion signals; the layout follows [official adaptive guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/adaptive-dos-and-donts).

## Navigation and Create refinement (v0.1.6)

Removed the repeated product header, On-device tag and decorative top rail mark. The rail now separates top task destinations from the Settings cog at its bottom. Settings has a 48dp+ hit target, selected state and accessibility name without a visible title; narrow app windows keep bottom navigation. The locally drawn line icon is now a cog.

Create no longer restores or exposes import history. Source/name/count/icon selection starts empty on fresh launch and resets after queue submission. Existing source records remain available internally for updating clones and backups. In-progress drafts retain saveable state through resizing and destination changes. Two to five copies have separate bounded name fields; six or more use a prefix. Only a single copy offers an icon override. All batch jobs discard icon overrides and individual names are stored in a migrated jobs column so interruption/recovery does not rename copies.

Install success no longer creates a banner or redundant card message. Update checks, readiness and errors use a compact floating Material Snackbar surface with a fixed five-second lifetime, a dismiss control and a separate Details dialog. The timer belongs to the ViewModel so resizing does not restart it. The Details dialog holds its own text while open; expiry does not interrupt reading. Row-level work progress remains visible for long operations. This follows Google’s [brief non-interrupting Snackbar pattern](https://developer.android.com/develop/ui/compose/components/snackbar), with the explicit five-second duration requested for this app.

Verification covers source-history suppression, empty fields, bottom cog placement, the five/six naming boundary, exact generated labels, draft preservation when resizing, reset after generation, named-job recovery, old-database migration, no success banner, and automatic expiry without dismissal. Test scrolling clears Android’s accessibility cache after movement; screenshot diagnostics confirmed the form itself scrolled while old accessibility bounds remained cached.
