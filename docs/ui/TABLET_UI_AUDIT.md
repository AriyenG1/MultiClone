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

A generation event adds each successfully published clone/update to the session's ready list. Dismissing the window suppresses further automatic openings until the next user-submitted generation request; later copies remain accessible through Review & install. Rotation preserves the ViewModel's dialog state. After a full process restart, existing outputs remain available in Library; old outputs do not trigger a fresh modal on launch.

## Official adaptive guidance

Google recommends decisions based on the app window's available space, retaining state during configuration changes and testing multiple sizes and text configurations. The design applies those principles to a single task-focused form rather than assuming every expanded screen needs two panes.

- [Adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive)
- [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)

## Verification

See `VERIFICATION.md` for commands, results and screenshots from the implemented UI. Emulator results validate layout and app behavior; physical Samsung One UI, S Pen input, keyboard/trackpad behavior and device-specific installation policies still need a device pass.
