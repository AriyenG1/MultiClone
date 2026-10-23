# Changelog

## v0.1.4 — 2026-10-23

- Settings update check against the public GitHub latest stable release.
- Bounded HTTPS downloads with progress, cancellation and SHA-256 verification.
- Package, higher versionCode and matching signing-certificate checks before Android installation.
- Android-confirmed in-place updates preserve the existing database, settings and signing keys.
- Adds INTERNET permission for these user-requested updates only.

## v0.1.3 — 2026-10-23

- Adaptive Material 3 tablet interface with separate Create and Library destinations.
- Navigation rail on wide windows and bottom navigation on narrow windows.
- Grouped, bounded Create form, dynamic light/dark colors and large-text support.
- Compatibility findings collapsed by default, with blocking issues summarized visibly.
- Installation dialog when clones finish generating, with Later and Review & install actions.
- Bounded status previews and full installation diagnostics in a details dialog.
- Saveable form and destination state during resizing and navigation.
- Two UI regressions, plus verified queue/backup and installer-feedback regressions.

## Earlier local versions

v0.1.2 improved clone-library states and Android-confirmed deletion. v0.1.1 improved installer feedback. v0.1.0 introduced the local cloning engine, signing, split support and persistent queues. Historical verification is recorded in docs/STATUS.md. The first GitHub release is v0.1.3.
