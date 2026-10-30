# Prediction coverage review — corpus-only candidate

Continues local candidate `73430654a9e6667d446ccb432f6ece91a22dbc81` on `review/play-api36-0.6.2`. No private branch, colours, debug evaluation features, signing, release, workflow dispatch or external posting. Existing API36/incognito/privacy/protected signing controls are retained. This is a review option, not a decision to ship the trade-offs.

## Chosen source and recipe

Training remains the three bounded Common Voice Sentence Collector files at `70dde553e48d7d0da452ba4ba070c001f65db88c`, explicitly covered by the data-subtree CC0 waiver/license (English local license identical). No additional bulk corpus or click-through agreement. Exact URLs/hashes and license evidence remain in `tools/data/sources.json` and packaged provenance. Contributor attribution is Mozilla Common Voice Sentence Collector contributors; individual identities or rights warranties are not invented. CC0 has no attribution/share-alike requirement; voluntary credit and full CC0 text remain offline. The collection guidance warns about historical bulk-import rights/quality; no Wiki/Europarl input was added.

`prediction_coverage.py` normalizes/deduplicates prompts, counts each eligible pair/unigram once per prompt, allows an attested one-prompt context when its follower recurs in at least two prompts, ranks by pair then corpus-unigram counts, and caps four hints/24,000 contexts per language. Length/alphabet rules retain accents/umlauts/English apostrophes and do not bridge invalid intervening tokens. No spelling dictionary, FrequencyWords weight/membership, prior CC BY-SA seed/table or held-out input enters generation. Project rights in these newly generated tables are CC0-dedicated, without changing prior material's license. Dictionaries remain byte-identical and CC BY-SA. Runtime dialect filtering still uses dictionary information. See [distribution review](DATA_DISTRIBUTION_REVIEW.md).

The development-only grid (follower minimum 2/3/5, rarity exponent 0/.2) used the prior known 80/20 Sentence Collector split. Minimum 2 / exponent 0 won development top-two rates across languages. The candidate was sealed before final test results. No parameters were changed after opening the test. `coverage_model_seal.json` binds model code, plan and outputs; regression tests verify it. `docs/data/coverage-development-search.json` records the development search, not an independent score.

## Independent evaluation design and limits

`coverage_evaluation_plan.json` was frozen before model search. Final test uses separate pinned CC0 contributor files: English/German `jf99.txt`; French `global-sprint-fr.txt` and `vfvv1.1`, `vfvv2`–`vfvv6`. None enter training, seeds or ranking selection. Source URLs, byte hashes, license source and deterministic selection hash are recorded in the plan/result JSON. These are source-separated contributor texts, not an independently collected messaging population. Common Voice project/domain overlap remains possible.

Normalized exact, token-exact and any shared five-token-window overlaps with all known prior/current training prompts and prior diagnostic full sentences are excluded. No deletion of unseen words to inflate denominators. Remaining prompts are SHA-256 ordered, capped at 2,000 per language; all eligible adjacent non-self transitions count, including out-of-dictionary contexts/followers. Natural short collocations can overlap, and legacy raw training corpora are unavailable, so complete historical contamination exclusion is impossible. All test sentences and legacy rows stay outside tracked output; only aggregate metrics/hashes are exported.

Raw TSV top-k lookup is evaluated, without learned hints, optional LLM, capitalization UI, full-sentence context or English dialect filtering. Real app behavior can differ; this is not typing accuracy, user acceptance, correction accuracy or proof of universal prediction quality. Confidence intervals resample prompt clusters; they quantify this source sample, not general-population uncertainty. French is small and the residual-after-overlap-removal distribution can be unusual. No tuning after this test.

## Held-out results

Each cell is legacy / prior licensed / new corpus-only candidate. Percentages use identical per-language transitions. Legacy is local public-lineage `5f46a180d8cbd6b9ddca18bc104827cc94612dd1`, retaining old rows; prior is `73430654a9e6667d446ccb432f6ece91a22dbc81`. Unresolved legacy rows/strings are not redistributed.

| Language | Prompts / transitions | Top 1 % | Top 2 % | Top 4 % | Context coverage % |
|---|---:|---:|---:|---:|---:|
| en | 1080 / 9125 | 11.85 / 10.52 / 11.01 | 16.88 / 15.21 / 15.76 | 22.47 / 19.84 / 20.39 | 89.19 / 86.62 / 92.96 |
| fr | 550 / 4718 | 7.99 / 7.91 / 8.63 | 12.84 / 12.36 / 13.31 | 18.14 / 17.49 / 18.80 | 87.90 / 89.11 / 94.26 |
| de | 2000 / 14095 | 7.09 / 7.27 / 7.68 | 10.90 / 10.92 / 11.49 | 15.74 / 15.38 / 15.96 | 83.50 / 82.65 / 89.64 |

Candidate-minus-prior top-two changes (percentage points), 400 paired prompt-cluster bootstrap descriptive 95% intervals:

- en: +0.548 [+0.379, +0.736]; versus legacy -1.118 [-1.671, -0.604].
- fr: +0.954 [+0.563, +1.294]; versus legacy +0.466 [-0.318, +1.226].
- de: +0.568 [+0.443, +0.727]; versus legacy +0.582 [+0.122, +1.007].

Known contamination/exclusion counts and rare-context results:

| Language | Input unique | Exact / token / shared-5 / no-transition exclusions | Clean / selected | Rare context (1–4 training prompts) top-two %, prior → new | Rare coverage %, prior → new |
|---|---:|---:|---:|---:|---:|
| en | 1102 | 0 / 1 / 20 / 1 | 1080 / 1080 | 4.77 → 9.54 | 39.63 → 98.65 |
| fr | 764 | 55 / 48 / 111 / 0 | 550 / 550 | 6.45 → 11.14 | 35.78 → 87.10 |
| de | 4620 | 2 / 15 / 88 / 5 | 4510 / 2000 | 3.26 → 7.25 | 29.12 → 87.55 |

English improves over the prior licensed candidate but still trails legacy top-two. French's improvement over legacy has an interval spanning zero; German gains are modest. Rare-context coverage improves much more than rare-target hits. Completely unseen contexts still receive no hint. This table does not establish uncommon-phrase equivalence: the model knows only one previous word.

## Size, costs and explicit quality trade-offs

| Language | Contexts prior → new | Follower slots prior → new | One-hint contexts prior → new | Raw bytes prior → new | Retained legacy contexts |
|---|---:|---:|---:|---:|---:|
| en | 9415 → 24000 | 33474 → 52590 | 426 → 11096 | 225882 → 456550 | 9081 / 10777 |
| fr | 10297 → 24000 | 36746 → 59178 | 461 → 7980 | 244061 → 484658 | 7313 / 8705 |
| de | 6663 → 24000 | 23135 → 45458 | 402 → 13254 | 180843 → 511048 | 6843 / 8992 |

Raw prediction bytes rise from 650,786 to 1,452,256 (+801,470, about 0.76 MiB); per-language context cap bounds packaging. Many extra contexts have only one observed hint. German legacy context retention is not complete even though total contexts grow. Corpus-only generation avoids share-alike ranking dependence but removing explicit conversational priors causes a material diagnostic regression: seed-overlapping chat top-two hits prior → new are EN 9→4, FR 8→4, DE 12→7 out of 12. General cases are EN 6→6, FR 6→6, DE 2→3 out of 8; all five tail cases per language remain 0/5. These synthetic examples were known beforehand and are deliberately reported separately, never as independent accuracy. For example, greeting/request choices can lose a hand-authored suggestion; specialized botanical/musical phrases remain weak. No eight-phrase optimization or new seed repair occurred after scores were seen.

Local measurements use the actual release `SpellEngine` parser/lookup, OpenJDK 17 on Linux with 256 MiB heap, eight warmups and medians of 21 iterations. They isolate followers using a dummy spelling dictionary, excluding dictionary indexing, UI, phone CPU and source-string cost. GC-based retained memory is approximate. Runtime benchmark JSON and Java harness are included.

| Language | Parse median ms prior → new | Approx follower heap MiB prior → new | Full-context lookup sweep ms prior → new |
|---|---:|---:|---:|
| en | 20.83 → 40.90 | 2.39 → 4.93 | 5.47 → 12.61 |
| fr | 25.34 → 28.36 | 2.44 → 4.83 | 8.73 → 12.22 |
| de | 30.33 → 26.58 | 1.52 → 4.50 | 5.11 → 12.41 |

Sweep lengths differ with table sizes and are not a per-keystroke latency comparison. Parse timings are noisy (German's lower median does not establish a speed improvement). Follower heap roughly doubles/triples. The service retains one active language engine and rebuilds on language changes; source strings, dictionary indexes and transient old/new allocations add costs beyond this isolated follower measurement. One local full incremental app validation/build took 75 seconds with cached native/toolchain inputs; this is not a controlled before/after build-speed study. Corpus generation took 0.49–1.06 seconds per language locally excluding source I/O and asset verification; exact timings are in result JSON. Device memory, cold launch, frame time and accessibility/navigation checks remain untested and must precede shipping.

Reproduction: `python3 tools/data_pipeline.py fetch --source-dir <cache>` (explicit network), then `build`, `check` and `audit`; evaluation additionally needs the hash-verified files in the frozen plan and `python3 tools/evaluate_coverage.py --source-dir <cache> --heldout-dir <test-cache> --output <aggregates.json>`. The old exploratory evaluation/report are retained and marked historical. Current production builds never call their seeded generator.

## Owner decision before shipping

This local option improves the requested German/rare-context coverage and held-out rates over the prior licensed candidate while using a technically independent CC0 generator. It does not recover legacy English quality or good specialized-phrase prediction and sacrifices curated chat behavior for source independence. Review those trade-offs and added memory/size before choosing it for closed testing. No further model was selected using this held-out test. Any future alternative needs a new untouched evaluation population or a clearly labeled development-only comparison. Remaining spelling-data distribution rights/Play questions, production signing and genuine device QA remain separate gates; no release is authorized here.
