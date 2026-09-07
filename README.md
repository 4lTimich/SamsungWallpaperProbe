# Iridescent Wallpaper v0.19 — page-scoped icon cache

This build fixes the three layout-outline bugs reported in v0.18 without changing the manual glass grid.

## Fixes

1. **Rescan no longer stacks outlines.** A settled scan replaces the cached layout of the current page instead of blindly merging another copy into the global snapshot.
2. **Old-page icons no longer reappear on a new page.** Hidden launcher nodes are rejected with `isVisibleToUser()`, stale page entries are kept in their own page namespace, and dock entries are refreshed separately.
3. **Incoming-page outlines are prefetched during the swipe.** The service performs only a few full-tree scans at sparse real-position thresholds (roughly 7.5%, 20%, and 40% of a page). Those scans only cache layout; the high-rate motion source is still one refreshed anchor node.
4. **The manual grid/editor is untouched.** It remains available as a fallback while the bounds-based layout cache is validated.

The cached outline world coordinate is still:

`worldX = boundsCenterX + realPagePosition * screenWidth`

so all cached icons can be moved by the single real anchor position.
