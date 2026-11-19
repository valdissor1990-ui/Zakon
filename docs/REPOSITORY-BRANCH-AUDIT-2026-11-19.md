# GitHub repository and branch audit — 2026-11-19

This is an inventory snapshot, not authorization to delete work. Branch counts are from GitHub's branch API, paginated where necessary. Pull-request state is from the repository API at the time of audit.

## Repository inventory

| Repository | Default | Branches observed | Open PRs | Notes |
|---|---|---:|---:|---|
| vladossVPN | main | 204 | 52 | Large active feature/fix/dependabot history; do not bulk-delete |
| vladossVPN-patches | main | 1 | 0 | Single-branch private repository |
| PRAVORF | main | 22 | 4 | One dependent PR chain; several already merged design changes |
| Zakon | main | 11 | 6 | Main content architecture and security work split across dependent PRs |
| GALACTIC-FRONTIER | main | 115 | 16 | Large integration graph; PRs use non-main base branches |
| NewRoad | main | 1 | 0 | Single-branch repository |
| cargo-master | main | 7 | 0 | Four closed-but-unmerged PRs; their branches may still contain useful work |

All branch and open-PR totals were obtained from paginated GitHub API responses.

## Zakon: current branch graph

Default branch: `main`.

| PR | Head | Base | Commits ahead of base | Commits behind base | Status |
|---:|---|---|---:|---:|---|
| #3 | feature/separate-content-database | main | 197 | 26 | Draft, mergeable=false |
| #9 | content/populate-core-acts | feature/separate-content-database | 61 | 0 | Draft, mergeable |
| #10 | feature/unified-hybrid-legal-search | feature/separate-content-database | 16 | 0 | Draft, mergeable |
| #8 | fix/cabinet-keystore-auth-flow | main | 10 | 17 | Draft, mergeable=false |
| #2 | design/premium-code-icons | main | 2 | 117 | Draft, mergeable=false |
| #1 | security/cabinet-encryption | main | 23 | 157 | Draft, mergeable=false |

Additional branch `security/fix-zak-11-12-14-18` is not an open PR head in the current list; it contains overlapping security, content import and validation changes and must be compared with PR #8/#1/#3 before disposition.

### Required integration order

1. Review and update `feature/separate-content-database` against current `main` first. It is 26 commits behind and 197 commits ahead; GitHub currently reports it as not mergeable. The two histories overlap in 30+ files, including Android build/content workflows, CabinetCrypto, CabinetData, ContentUpdate, LegalStore, UtilityScreens, manifest and registry files. Do not merge dependent PRs until this parent is reconciled and CI is green.
2. Keep PR #9 and PR #10 based on `feature/separate-content-database` until step 1 is complete. They are independent children of PR #3; do not retarget either directly to main without inspecting their full diffs.
3. After PR #3 is accepted, rebase/update PR #9 and PR #10 against the updated base, rerun CI, then review and merge separately.
4. Review PR #8 and security branches as a separate security track. Do not merge PR #1 and PR #8 blindly: they both touch cabinet encryption/storage and utility screens.
5. PR #2 is only 2 commits ahead but 117 behind; compare its two unique commits with current main and port only the icon changes if still needed. Do not merge the stale branch as-is.

## Other repositories: branch handling

### vladossVPN — 204 branches
There are 52 open PRs. Active recent work includes subscription-cache encryption (#179), recommendation-first connection (#178), lifecycle/state ownership (#176), URI query normalization (#175), scan-cache reuse (#174), network bucket stability (#173), privacy-safe subscription logging (#170), service teardown (#169), and CI/security docs (#168), plus older open PRs and dependency updates. The first 10 PR comparisons show most are only 1 commit behind main, but have diverged; #179 is 2 commits ahead and clean. These are active, distinct changes. Keep them; group by feature area and merge only after individual CI and overlap review. Merged PR branches can be deleted only after checking whether any other open PR or downstream branch uses them.

### PRAVORF — 22 branches
- PR #1 (`feat/android-offline-legal-reference`) is the parent of PR #2 (`feat/yandex-account-sync-foundation`).
- PR #19 (`feat/three-imperial-themes`) and PR #12 (`fix/sunset-design-consistency`) target main independently.
- PR #18, #17, #16, #15, #14, #13, #11 and #10 are already merged. Their head branches are cleanup candidates only after checking whether they are referenced by another open PR or used as a base.

### GALACTIC-FRONTIER — 115 branches
There are 16 open PRs. The active PR graph includes the main integration/vertical-slice branch and dependent ship, cockpit, planet and space-rendering branches. Examples: #111 targets `chatgpt/grok-completion-full-2026-11`; #112 targets `chatgpt/3d-space-volume-pass`; #98/#97/#96 target the Grok completion branch; #94 targets ship UV quality; #93 targets the player-fighter hero branch. This is a staged integration graph, not a flat list. Comparisons show #113, #112 and #111 are ahead-only relative to their stated bases; #101, #98, #97, #96, #93 and #92 have diverged and need explicit conflict review. Preserve all active heads and all bases until the graph is mapped. PR #110 and #104 are already merged into main; their branches are possible cleanup candidates after reference checks.

### cargo-master — 7 branches
PRs #2–#5 are closed without merging. Their branches still exist and include implementation, Android CI, art production and design documentation. Comparison against main: `grok/implementation` is 0 ahead / 243 behind; `art/greenfield-production-pass-2` is 0 ahead / 155 behind; neither contains commits unique to main's current history. They are the strongest deletion candidates, provided no external workflow or local clone still depends on them. `art/greenfield-living-district` is identical to main but was used by the latest successful APK and Godot runs, so retain it for now. `build/android-apk-ci` (20 ahead / 283 behind) and `docs/design` (56 ahead / 313 behind) contain unique commits and must be preserved.

### Single-branch repositories
- `vladossVPN-patches`: only main.
- `NewRoad`: only main.

## Cleanup policy

- Never delete a default branch.
- Never delete an open PR head or a branch used as the base of an open PR.
- Never delete a branch with unique commits not present in its destination.
- Merged branches are eligible only after confirming no downstream PR depends on them and their commits are reachable from the intended integration branch.
- Closed-but-unmerged branches are retained until their content is either recovered, explicitly rejected, or proven duplicated.
- Prefer closing/relabeling obsolete PRs and documenting replacement branches before deleting refs.
- No branch or repository was deleted or merged during this audit.

## Next execution steps

1. Finish full-page PR enumeration for repositories with more than 100 PRs.
2. Compare all active PR heads with their base branches; map dependencies and conflicts.
3. Run CI on each proposed integration target.
4. Integrate the Zakon parent branch first, then its two dependent child branches.
5. Prepare explicit safe-delete candidates with proof of merged/duplicate commits; obtain confirmation before destructive deletion.


## Additional PR comparison findings

- `vladossVPN`: 52 open PRs. The newest 10 have mostly diverged by one commit from main; PR #179 is 2 commits ahead with no behind commits. No bulk retargeting is safe.
- `PRAVORF`: PR #19 is 17 commits ahead of main with no behind commits; PR #12 is 1 ahead / 35 behind; PR #2 is 62 ahead of PR #1's branch; PR #1 is 146 ahead / 52 behind main. Preserve the parent-child chain and update the parent first.
- `GALACTIC-FRONTIER`: PR #113 is 5 ahead of main; #112 is 21 ahead of #111's base; #111 is 39 ahead of its base. These are merge-order candidates after CI. #101 (59 ahead / 80 behind), #98 (20/21), #97 (52/23), #96 (23/21), #93 (3/7), and #92 (13/21) have diverged and require diff-level integration.
- `Zakon`: PR #3 is 197 ahead / 26 behind main; #9 and #10 are clean children of #3. PR #8 (10/17), #2 (2/117), and #1 (23/157) need separate treatment.


## Safe-cleanup candidates (not yet deleted)

- `cargo-master:grok/implementation`: 0 commits ahead of main, 243 behind; no open PR uses it as a base. Closed PR references remain in GitHub history.
- `cargo-master:art/greenfield-production-pass-2`: 0 ahead, 155 behind; no open PR uses it as a base. Closed PR references remain in GitHub history.

These two refs appear redundant with current main by commit reachability. They can be removed after explicit confirmation. No delete-ref action is available through the connected GitHub interface in this session, so no destructive branch deletion has been attempted.

## Not safe to delete based on current evidence

- All active PR heads and all branches used as bases by open PRs.
- Branches in Zakon/PRAVORF/GALACTIC-FRONTIER whose heads still contain commits not reachable from main, even when a PR was previously marked merged.
- `cargo-master:build/android-apk-ci` and `cargo-master:docs/design`, which contain unique commits.
- `cargo-master:art/greenfield-living-district`, which currently matches main but remains the latest validated build source.
