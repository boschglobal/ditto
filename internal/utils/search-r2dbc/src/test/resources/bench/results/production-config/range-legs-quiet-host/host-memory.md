# Host memory per measured run

One line per run: a sample right before and right after it (macOS `vm_stat` `Swapouts` in 16 KiB pages and
`memory_pressure -Q` free %). A run is **reliable** iff `Swapouts` grew by at most 1,000 pages and both samples show
≥ 50 % free. Swap that macOS kept allocated from earlier work (1.7–2.0 GB in both series) is recorded, not gated.
Before each run two samples 120 s apart had to show the same (quiet check); every run passed it on its first try.
Times are UTC, 2026-10-22.

| series | run | start | end | `Swapouts` before → after | free before → after | verdict |
|---|---|---|---|---|---|---|
| before | A1-off | 00:21:29 | 00:34:46 | 6,137,538 → 6,137,538 (+0) | 82 % → 85 % | reliable |
| before | B1-auto | 00:37:03 | 01:10:11 | 6,137,538 → 6,137,538 (+0) | 85 % → 87 % | reliable |
| before | B2-auto | 01:12:18 | 01:45:25 | 6,137,538 → 6,137,538 (+0) | 87 % → 86 % | reliable |
| before | A2-off | 01:47:41 | 02:00:18 | 6,137,538 → 6,137,538 (+0) | 87 % → 86 % | reliable |
| after | A1-off | 11:26:50 | 11:41:27 | 6,137,538 → 6,137,538 (+0) | 79 % → 73 % | reliable |
| after | B1-auto | 11:43:38 | 11:53:20 | 6,137,538 → 6,137,538 (+0) | 74 % → 73 % | reliable |
| after | B2-auto | 11:55:30 | 12:04:57 | 6,137,538 → 6,137,538 (+0) | 74 % → 73 % | reliable |
| after | A2-off | 12:07:05 | 12:20:23 | 6,137,538 → 6,137,538 (+0) | 76 % → 73 % | reliable |
| after | A3-off | 12:23:14 | 12:23:57 | 6,137,538 → 6,137,538 (+0) | 74 % → 74 % | reliable |
| after | B3-auto | 12:25:57 | 12:26:28 | 6,137,538 → 6,137,538 (+0) | 76 % → 75 % | reliable |
| after-pairs | A1-start-auto | 12:29:12 | 12:32:13 | 6,137,538 → 6,137,538 (+0) | 79 % → 72 % | reliable |
| after-pairs | B1-final-auto | 12:34:13 | 12:35:02 | 6,137,538 → 6,137,538 (+0) | 72 % → 71 % | reliable |
| after-pairs | B2-final-auto | 12:37:02 | 12:37:51 | 6,137,538 → 6,137,538 (+0) | 74 % → 74 % | reliable |
| after-pairs | A2-start-auto | 12:39:51 | 12:42:04 | 6,137,538 → 6,137,538 (+0) | 74 % → 71 % | reliable |
| probe | probe (warm EXPLAINs) | 12:42:26 | 12:42:58 | 6,137,538 → 6,137,538 (+0) | 71 % → 73 % | reliable |
| probe | probe repeats | 12:43:48 | 12:43:57 | 6,137,538 → 6,137,538 (+0) | 73 % → 73 % | reliable |
