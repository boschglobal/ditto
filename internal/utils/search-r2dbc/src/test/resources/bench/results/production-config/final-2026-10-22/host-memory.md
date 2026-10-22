# Host memory per measured run

A sample right before and right after every measured run (macOS `vm_stat` `Swapouts`, 16 KiB pages, and
`memory_pressure -Q` free %). A run is **reliable** iff `Swapouts` grew by at most 1,000 pages and both samples
show ≥ 50 % free; before each run two samples 120 s apart had to show the same (quiet check). Swap macOS kept
allocated from earlier work is recorded, not gated. Times UTC, 2026-10-22.

| run | start (UTC) | end (UTC) | `Swapouts` before → after | free before → after | verdict |
|---|---|---|---|---|---|
| warm | 13:46:38 | 14:00:47 | 6,137,538 → 6,137,538 (+0) | 80 % → 73 % | reliable |
| cold | 14:03:03 | 14:08:44 | 6,137,538 → 6,137,538 (+0) | 74 % → 71 % | reliable |
| lone-range/A1-off | 14:11:41 | 14:20:41 | 6,137,538 → 6,137,538 (+0) | 74 % → 72 % | reliable |
| lone-range/B1-auto (first try: failed, statement timeout) | 14:22:49 | 14:24:21 | 6,137,538 → 6,137,538 (+0) | 74 % → 70 % | reliable |
| lone-range/B1-auto (repeat) | 14:28:41 | 14:37:34 | 6,137,538 → 6,137,538 (+0) | 74 % → 70 % | reliable |
| lone-range/B2-auto | 14:39:44 | 14:52:42 | 6,137,538 → 6,137,538 (+0) | 72 % → 58 % | reliable |
| lone-range/A2-off (first try: failed, statement timeout) | 14:54:59 | 14:56:32 | 6,137,538 → 6,137,538 (+0) | 71 % → 49 % | **unreliable** |
| lone-range/A2-off (repeat) | 14:59:05 | 15:06:32 | 6,137,538 → 6,137,538 (+0) | 73 % → 71 % | reliable |
| path-index/A1-without | 15:08:52 | 15:09:56 | 6,137,538 → 6,137,538 (+0) | 73 % → 73 % | reliable |
| path-index/B1-with | 15:13:01 | 15:13:47 | 6,137,538 → 6,137,538 (+0) | 72 % → 71 % | reliable |
| path-index/B2-with | 15:15:55 | 15:16:19 | 6,137,538 → 6,137,538 (+0) | 73 % → 73 % | reliable |
| path-index/A2-without | 15:18:32 | 15:19:16 | 6,137,538 → 6,137,538 (+0) | 75 % → 74 % | reliable |

Not timed (samples for the record): corpus bias, the per-path index build, `pc-invariance`:

| run | start (UTC) | end (UTC) | `Swapouts` before → after | free before → after | verdict |
|---|---|---|---|---|---|
| corpus-bias | 14:09:01 | 14:09:25 | 6,137,538 → 6,137,538 (+0) | 70 % → 71 % | reliable |
| index-build | 15:10:06 | 15:10:49 | 6,137,538 → 6,137,538 (+0) | 73 % → 47 % | **unreliable** |
| pc-invariance | 15:19:26 | 15:37:38 | 6,137,538 → 6,137,538 (+0) | 73 % → 58 % | reliable |
