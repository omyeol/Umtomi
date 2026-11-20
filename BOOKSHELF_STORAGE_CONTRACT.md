# Shared bookshelf storage contract

User decision, 2026-10-10: bookshelf changes must be developed together in Umtomi and Omyaru Browser (Android, Windows desktop and standalone bookshelf). The Android shared-storage implementation is present in Umtomi 5.13.0 / Omyaru 0.15.0. Windows folder hierarchy is present in desktop 0.4.40 / standalone 0.1.12. See validation limits below.

- Android has one device-wide library: `<selected volume>/Omyaru_Bookshelf/`.
- Default volume is internal shared storage; removable SD is the only alternative. No arbitrary directory selector.
- The UI hierarchy and real directories must match, e.g. `Omyaru Browser/책장1` and `Umtomi/책장2` beneath this root.
- Both installed apps use the same catalog and originals. Stable folder/image identifiers survive rename and move. A fixed directory name alone is not deduplication.
- Concurrent changes require cross-process locking and revision-aware merging; an old activity snapshot must never overwrite another app's edits.
- Original installation-specific catalogs are migrated only after verified copying and a committed catalog. Never interpret unavailable storage or revoked access as deleted content.
- Removing an SD card must stop writes/sync and request reinsertion, never silently create another internal library.
- A storage-volume change is coordinated through a shared location record; verify all copies before committing the switch. A second app must discover the same destination.
- Drive transfers must reuse existing image identities and verified content. Account tokens and private browsing data remain app-private.
- Windows follows the same folder and synchronization semantics, but its volume/location configuration is local to Windows, not synced from Android.
- Mandatory validation before release: two Android packages editing the same library; folder subtree rename/move; existing-data migration; repeated sync without duplicate originals; interruption/retry; unavailable/remounted SD; Windows compatibility.

Validation: both real Android packages passed a synthetic public-storage round trip on MuMu DEV, retaining one original across a folder move and metadata update. Separate instrumented tests cover app migration, stale edits across a volume switch, simulated media removal/reinsertion, and switching back. Actual SD hardware and real-account Drive round trips remain unverified. Shared storage requires explicit per-app Android 11+ all-files access and connection; pre-connection/older Android retains legacy private storage. Windows retains its existing independently configured storage location.
