# Final re-run vs the production-config baseline

Baseline: `../summary.md` and `../shape-*.md` (2026-09-30). Final: this folder. Numbers are copied
verbatim from the two `summary.md` headline tables. SQL = the iteration-0 statement text; plan = the
node lines (type, relation, index) and their conditions (`Index Cond`, `Filter`, …) of the iteration-0
EXPLAIN, costs, timings and row counts stripped. "–" = no shape file on one side.

| shape | baseline p50 / p95 (all rounds) | final p50 / p95 (all rounds) | baseline gate | final gate | SQL identical | plan identical |
|---|---:|---:|---|---|:---:|:---:|
| 1 | 895.4 / 1364.6 | 123.5 / 267.1 | MISS (did not settle) | MISS (did not settle) | no | no |
| 2 | 120.4 / 398.8 | 109.7 / 422.8 | MISS (did not settle) | MISS (did not settle) | yes | yes |
| 3-low | 46.7 / 50.3 | 41.9 / 53.4 | PASS (settled) | PASS (did not settle) | yes | yes |
| 3-high | 199.0 / 391.5 | 207.8 / 356.7 | MISS (did not settle) | MISS (settled) | yes | no |
| 4-page1 | 578.4 / 599.5 | 314.0 / 557.7 | MISS (settled) | MISS (did not settle) | no | no |
| 4-offset | 611.1 / 653.4 | 324.8 / 446.8 | MISS (settled) | MISS (did not settle) | no | no |
| 4-keyset | 734.6 / 772.7 | 657.7 / 720.0 | MISS (settled) | MISS (did not settle) | no | no |
| 4-offset-5000 | 622.0 / 647.7 | 347.1 / 374.7 | MISS (settled) | MISS (settled) | no | no |
| 4-keyset-5000 | 286.3 / 299.9 | 257.9 / 461.9 | MISS (settled) | MISS (did not settle) | no | no |
| 5 | 988.0 / 1641.0 | 138.3 / 205.4 | MISS (did not settle) | MISS (did not settle) | no | no |
| ce-a | – (not in the baseline) | 1.6 / 1.7 | – | PASS (settled) | – | – |
| ce-a-sudo | – (not in the baseline) | 1.3 / 1.5 | – | PASS (settled) | – | – |
| ce-b | – (not in the baseline) | 16.6 / 41.5 | – | PASS (did not settle) | – | – |
| ce-c | – (not in the baseline) | 125.9 / 314.0 | – | MISS (did not settle) | – | – |
| lone-wide | – (not in the baseline) | 114.4 / 129.6 | – | PASS (did not settle) | – | – |
| lone-wide-sudo | – (not in the baseline) | 1.1 / 1.2 | – | PASS (settled) | – | – |
| lone-wide-count | – (not in the baseline) | 72.4 / 107.7 | – | PASS (did not settle) | – | – |
| lone-wide-sudo-count | – (not in the baseline) | 5403.8 / 6076.2 | – | MISS (settled) | – | – |
| lone-mid | – (not in the baseline) | 130.3 / 491.0 | – | MISS (did not settle) | – | – |
| lone-mid-sudo | – (not in the baseline) | 4.4 / 6.0 | – | PASS (settled) | – | – |

### 1: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -1,8 +1,42 @@
 Limit
+CTE sel_pick
+Subquery Scan on pc
+Aggregate
+Limit
+Index Only Scan using sf_text on search_flat sfc_2
+    Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
+SubPlan 1
+Aggregate
+Limit
+Append
+Index Only Scan using sf_num on search_flat sfc
+    Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
+Index Only Scan using sf_multi on search_flat sfc_1
+    Index Cond: (wpath = '/features/env/properties/temperature'::text)
 CTE sel_leg
-Bitmap Heap Scan on search_flat sfc
+HashAggregate
+Append
+Result
+    One-Time Filter: $2
+InitPlan 3 (returns $2)
+CTE Scan on sel_pick sp
+Bitmap Heap Scan on search_flat sfc_3
     Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
 Bitmap Index Scan on sf_text
     Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
+Result
+    One-Time Filter: (NOT $3)
+InitPlan 4 (returns $3)
+CTE Scan on sel_pick sp_1
+Bitmap Heap Scan on search_flat sfc_4
+    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
+Bitmap Index Scan on sf_num
+    Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
+Result
+    One-Time Filter: (NOT $4)
+InitPlan 5 (returns $4)
+CTE Scan on sel_pick sp_2
+Index Only Scan using sf_multi on search_flat sfc_5
+    Index Cond: (wpath = '/features/env/properties/temperature'::text)
 Sort
 Nested Loop Semi Join
```

### 3-high: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -10,6 +10,8 @@
 Hash
 Bitmap Heap Scan on search_flat a
-    Recheck Cond: ((val_text)::text ~~* '%234%'::text)
-    Filter: (wpath = '/features/*/properties/prop0'::text)
+    Recheck Cond: (((val_text)::text ~~* '%234%'::text) AND (wpath = '/features/*/properties/prop0'::text))
+BitmapAnd
 Bitmap Index Scan on sf_trgm
     Index Cond: ((val_text)::text ~~* '%234%'::text)
+Bitmap Index Scan on sf_exists
+    Index Cond: (wpath = '/features/*/properties/prop0'::text)
```

### 4-page1: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -9,3 +9,3 @@
 Sort
 Index Scan using search_flat_pkey on search_flat s
-    Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
+    Index Cond: ((thing_id = st.thing_id) AND (path = '/features/env/properties/temperature'::text COLLATE "C") AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
```

### 4-offset: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -9,3 +9,3 @@
 Sort
 Index Scan using search_flat_pkey on search_flat s
-    Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
+    Index Cond: ((thing_id = st.thing_id) AND (path = '/features/env/properties/temperature'::text COLLATE "C") AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
```

### 4-keyset: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -23,3 +23,3 @@
 Sort
 Index Scan using search_flat_pkey on search_flat s
-    Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
+    Index Cond: ((thing_id = st.thing_id) AND (path = '/features/env/properties/temperature'::text COLLATE "C") AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
```

### 4-offset-5000: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -9,3 +9,3 @@
 Sort
 Index Scan using search_flat_pkey on search_flat s
-    Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
+    Index Cond: ((thing_id = st.thing_id) AND (path = '/features/env/properties/temperature'::text COLLATE "C") AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
```

### 4-keyset-5000: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -17,3 +17,3 @@
 Sort
 Index Scan using search_flat_pkey on search_flat s
-    Index Cond: ((thing_id = st.thing_id) AND (wpath = '/features/env/properties/temperature'::text))
+    Index Cond: ((thing_id = st.thing_id) AND (path = '/features/env/properties/temperature'::text COLLATE "C") AND (wpath = '/features/env/properties/temperature'::text COLLATE "C"))
```

### 5: iteration-0 plan, baseline → final

```diff
--- baseline
+++ final
@@ -1,8 +1,42 @@
 Aggregate
+CTE sel_pick
+Subquery Scan on pc
+Aggregate
+Limit
+Index Only Scan using sf_text on search_flat sfc_2
+    Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
+SubPlan 1
+Aggregate
+Limit
+Append
+Index Only Scan using sf_num on search_flat sfc
+    Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
+Index Only Scan using sf_multi on search_flat sfc_1
+    Index Cond: (wpath = '/features/env/properties/temperature'::text)
 CTE sel_leg
-Bitmap Heap Scan on search_flat sfc
+HashAggregate
+Append
+Result
+    One-Time Filter: $2
+InitPlan 3 (returns $2)
+CTE Scan on sel_pick sp
+Bitmap Heap Scan on search_flat sfc_3
     Recheck Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
 Bitmap Index Scan on sf_text
     Index Cond: ((wpath = '/attributes/location/city'::text) AND (val_text = 'Stuttgart'::text))
+Result
+    One-Time Filter: (NOT $3)
+InitPlan 4 (returns $3)
+CTE Scan on sel_pick sp_1
+Bitmap Heap Scan on search_flat sfc_4
+    Recheck Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
+Bitmap Index Scan on sf_num
+    Index Cond: ((wpath = '/features/env/properties/temperature'::text) AND (val_num >= '-15.0'::numeric) AND (val_num <= '-14.01'::numeric))
+Result
+    One-Time Filter: (NOT $4)
+InitPlan 5 (returns $4)
+CTE Scan on sel_pick sp_2
+Index Only Scan using sf_multi on search_flat sfc_5
+    Index Cond: (wpath = '/features/env/properties/temperature'::text)
 Nested Loop Semi Join
     Join Filter: (sl.thing_id = sf_2.thing_id)
@@ -13,5 +47,4 @@
 HashAggregate
 CTE Scan on sel_leg sl
-Memoize
 Index Scan using search_things_pkey on search_things st
     Index Cond: (thing_id = sl.thing_id)
```
