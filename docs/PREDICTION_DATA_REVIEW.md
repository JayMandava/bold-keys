> Historical review of candidate `73430654a9e6667d446ccb432f6ece91a22dbc81`. Its seeded/FrequencyWords-informed predictions were superseded by [the corpus-only coverage review](PREDICTION_COVERAGE_REVIEW.md). Metrics below describe that previous candidate, not current assets.

# Prediction-data replacement review — no shipping approval

This local candidate replaces all three unresolved next-word tables. It preserves offline English/French/German prediction, the existing TSV schema/runtime, and byte-for-byte identical spelling dictionaries. It does **not** establish equivalent real-user quality. The rare-context and German trade-offs below require review before shipping; no push, merge, signing, dispatch or release occurred.

## Selected sources and actual grants

Common Voice commit `70dde553e48d7d0da452ba4ba070c001f65db88c`; only its three `sentence-collector.txt` files are used. Large Wiki/Europarl/other bulk files, voice recordings and personal voice metadata are excluded. These bounded text inputs total 9,255,831 raw bytes.

| Language | Raw bytes | SHA-256 | Exact immutable URL |
|---|---:|---|---|
| en | 2803252 | `31ac8e200449ebd7aee4a8c6bd16d4dafd076cf11f7c3efe1035f0283712d136` | https://raw.githubusercontent.com/common-voice/common-voice/70dde553e48d7d0da452ba4ba070c001f65db88c/server/data/en/sentence-collector.txt |
| fr | 4471382 | `214f3d5b0110e86e3f2018ca6971b918db8ad5cc4c5612ebab58cd0849fb0715` | https://raw.githubusercontent.com/common-voice/common-voice/70dde553e48d7d0da452ba4ba070c001f65db88c/server/data/fr/sentence-collector.txt |
| de | 1981197 | `de6bd33fc5742350690fa4be6197aafd9be2c6c9fe4b70b8c5c8670971acc117` | https://raw.githubusercontent.com/common-voice/common-voice/70dde553e48d7d0da452ba4ba070c001f65db88c/server/data/de/sentence-collector.txt |

The data grant is CC0 1.0 Universal from the pinned `server/data/LICENSE`, with the same local grant in `server/data/en/LICENSE`. Both are SHA-256 `bf474cdfd02e162603358e4e3615182018cad27593d2bf5e75ca13e4a5a6af0c`; the complete text is packaged offline. This is a data-specific grant, not the repository code license. CC0 has no attribution/share-alike requirement. Offline notices voluntarily credit Mozilla Common Voice Sentence Collector contributors, without inventing individual authors or pretending a contributor ledger was retained. Exact license/guidance URLs and hashes are in `tools/data/sources.json` and packaged `data-provenance.json`.

Pinned project guidance describes human validation of Sentence Collector submissions and warns about older bulk imports that may contain unsuitable or restricted text. Narrow source selection does not create a warranty about every donation's rights, bias or accuracy; no universal legal-clearance claim is made. Raw public repository access required no new explicit agreement, account or acceptance. No voice/data portal agreement was accepted.

The small synthetic seed file was newly prepared for this review (AI-assisted), with no copied old rows or undocumented outside examples. These new project data contributions use CC BY-SA 4.0, while generator/application code remains Apache-2.0. Generation uses the CC BY-SA FrequencyWords dictionaries to select/rank the CC0 text, so the generated tables conservatively retain CC BY-SA 4.0 conditions rather than assuming that influence is exempt. Common Voice material remains CC0; it is not relicensed. The seed has 45/45/46 contexts; only 18/15/15 identified conversational contexts take priority over observed ranks. Elsewhere it backfills observed choices. These are explicit priors, not fabricated corpus counts.

## Reproducible FrequencyWords dictionaries

All three current word/count dictionaries reconstruct byte-for-byte from pinned FrequencyWords 2018 50k files at `525f9b560de45753a5ea01069454e72e9aa541c6`:

| Language | Raw SHA-256 | Exact immutable URL |
|---|---|---|
| en | `5351ff405b1126ef555791dd4d9798a48e3e9a501a9fc481a9da957752cfb458` | https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/en/en_50k.txt |
| fr | `f81f7c570b6433764da99aa30f4dfb08d81c5926301377b709af23a13b1f9596` | https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/fr/fr_50k.txt |
| de | `d9e50546fd7e8b6fe6542a2b33c51d1331092b2a3916ec09f80d97856068705b` | https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/de/de_50k.txt |

French/German retain the production letter/accent/umlaut filtering and top-46,000 rules. English is reproduced using the retained two-letter allowlist, 16 removed incomplete contraction stems, 67 existing contraction entries and 29 existing supplemental words, recorded in `english_word_adjustments.json`. That adjustment file derives from public v0.6.1's dictionary, not the unresolved prediction tables. The historical acquisition date remains unknown, but current builds no longer depend on an unidentified snapshot or unrecoverable script.

FrequencyWords README at the same immutable commit explicitly licenses **content** CC BY-SA 4.0 and **code** MIT. The derived dictionaries keep the content terms. Hermit Dave/FrequencyWords/OpenSubtitles attribution, source/license URIs and modification descriptions are retained in offline notices. The project's Apache code license does not override data terms. Owner review still needs to ensure distribution/store terms respect applicable share-alike/no-additional-restriction requirements and consider underlying-source issues; a byte hash is not legal clearance.

## Build and confidence policy

`data_pipeline.py` verifies every raw data/grant/guidance hash before generation. It normalizes NFC, case and apostrophes and deduplicates prompts. It preserves language-specific letters and English contractions, filters adjacent pairs against the dictionaries, and never bridges an unknown token. Each pair and context counts at most once per unique prompt.

A follower normally requires at least two distinct prompts. To preserve coverage, a context observed in at least three distinct prompts can use single-observation followers as a bounded backoff. Followers rank by observed support, dictionary frequency and lexical tie-break; at most four are retained. Explicit preferred chat priors precede observed choices only at the listed seed contexts. Contexts are capped at 12,000 per language. The existing runtime offers at most two hints and may show one or none. Next-word hints are not automatically applied as spelling corrections.

The old French/German builders now reject legacy sentence/pair arguments and accept only a hash-verified word-list input. There is no old-table fallback. Both protected workflows run data-provenance tests, and APK/AAB verification binds all data and license bytes to the reviewed source manifest. Changing a table, recipe or grant without rebuilding fails the checks.

```
python3 tools/data_pipeline.py fetch --source-dir /workspace/prediction-inputs
python3 tools/data_pipeline.py build --source-dir /workspace/prediction-inputs
python3 tools/data_pipeline.py check --source-dir /workspace/prediction-inputs
python3 tools/data_pipeline.py audit
```

Network access occurs only in explicit fetch mode; build/check/audit use local pinned inputs. Generated tables remain ordinary bundled offline assets. No raw source prompts or old prediction rows are needed at runtime.

## Coverage and size

Baseline is local public-production-lineage commit `5f46a180d8cbd6b9ddca18bc104827cc94612dd1`. Old rows/prediction strings were read locally for comparison and are not reproduced in this report or evaluation output.

| Language | Contexts old → new | Follower slots old → new | Bytes old → new | New one-follower contexts | Old-context dictionary-frequency weight retained |
|---|---:|---:|---:|---:|---:|
| en | 10,777 → 9,415 | 32,144 → 33,474 | 224,643 → 225,882 | 426 | 97.70% |
| fr | 8,705 → 10,297 | 28,846 → 36,746 | 195,388 → 244,061 | 461 | 97.81% |
| de | 8,992 → 6,663 | 30,294 → 23,135 | 233,113 → 180,843 | 402 | 96.03% |

The retained-weight column uses dictionary frequencies as a proxy; it is not measured typing frequency, prediction precision or proof of quality. French gains contexts, English loses roughly 13%, and German loses roughly 26%; new contexts do not necessarily replace the useful old ones. Some rare contexts lose all hints, and some offer only one. Dictionaries, corrections/completions, personal learning and optional on-device model inference are unchanged by this data replacement.

## Exploratory prompt-split comparison

Normalized prompts are split deterministically by SHA-256: 80% train and 20% held out. The replacement model for this comparison is built from train prompts only; the shipped candidate is rebuilt from all pinned prompts afterward. Eligible held-out adjacent transitions are evaluated with top-two hints, including uncovered contexts as misses. This split was used to compare count/backoff choices, so it is **not an untouched model-selection benchmark**. Common Voice read-aloud/narrative style differs from messaging, and overlap with old source material cannot be excluded.

| Language | Eligible held-out transitions | Top-two hit rate old → replacement | Context coverage old → replacement |
|---|---:|---:|---:|
| en | 83,288 | 16.30% → 18.12% | 93.61% → 94.82% |
| fr | 107,240 | 13.18% → 16.02% | 92.85% → 95.93% |
| de | 44,953 | 13.40% → 13.93% | 92.39% → 90.62% |

Slightly better hit rates on this exploratory source-specific split do not establish equal or better real typing quality. German still covers fewer held-out contexts. The corpus is smaller and stylistically different; single-observation backoff is weaker evidence than a repeatedly observed follower.

## Synthetic language diagnostics

Twenty-five newly authored cases per language cover chat (12), general phrases (8) and tail topics (5). Chat cases intentionally overlap seed design and are not a generalization score. Full prefixes are explanatory: the runtime model uses only the preceding word. Results include real losses, not only favorable examples.

| Language | Chat top-two hits old → new / 12 | General old → new / 8 | Tail old → new / 5 |
|---|---:|---:|---:|
| en | 3 → 9 | 6 → 6 | 1 → 0 |
| fr | 4 → 8 | 4 → 6 | 2 → 0 |
| de | 8 → 12 | 4 → 2 | 0 → 0 |

Representative project-created cases: `Good …` → morning; `Merci …` → beaucoup; `Guten …` → morgen. The new tables support these chat choices. Examples such as “The train reached the …”, “Le train arrive à la …” and “Der Zug erreicht den …” expose the one-word model's inability to infer the entire sentence. Tail cases such as volcanic/botanical/musical topics remain weak. No old prediction strings are exported. Detailed synthetic cases and hit flags are in `docs/data/prediction-evaluation.json`; the regeneration/evaluation script is `tools/evaluate_prediction_data.py`.

## Shipping decision and remaining work

This is a prepared **review candidate**, not permission to ship. Review the loss of rare contexts, weaker German general-phrase coverage in this small diagnostic, single-follower/empty cases, narrative-domain bias and newly authored seed priorities. Native-speaker/device typing QA and representative real-use evaluation remain unperformed. If the tail/German trade-off is unacceptable, obtain additional verified conversational data or expand independently authored targeted seeds and re-evaluate; do not restore the unresolved tables as a fallback.

The historical Norvig data grant and Tatoeba author-ledger issues were not retroactively resolved; those inputs have been removed from current generation and packages. Existing Git history is unchanged. Source/store-term interpretation, optional Gemma terms, export declarations, device QA, actual production signing and final owner quality acceptance remain outside this unsigned preparation.
