# Third-party distribution review — owner decisions still required

This is an evidence ledger, not a legal clearance or a new license grant. Existing data assets are unchanged. Do not approve distribution/signing as a Play-ready release until the unresolved corpus rights/attribution below are resolved. The separate AAB workflow is prepared locally, not dispatched.

## Verified grants and packaged notices

- **Nunito ExtraLight 3.602:** the bundled TTF name table identifies “Copyright 2014 The Nunito Project Authors (https://github.com/googlefonts/nunito)” and SIL OFL 1.1. The full matching copyright/OFL text from `google/fonts/ofl/nunito/OFL.txt` is now packaged offline. The font is not renamed, relicensed or modified.
- **Unicode emoji data:** canonical https://www.unicode.org/license.txt grants data reuse under Unicode License V3 with its copyright/permission notice in the copies or associated documentation. The full current notice is packaged. `tools/build_emoji.py` filters fully-qualified entries, groups emoji and associates tone variants; this processing is disclosed. The original input file version/hash was not retained, so exact snapshot reproducibility remains unknown.
- **llama.cpp / ggml:** the pinned submodule `748d4225b9016b17ce4bcfa69fdc2c39f473a965` has an MIT license, copyright 2023–2026 the ggml authors. Its exact license file is copied into offline notices. Native inference code is unchanged. The separate vendor hash target is absent from the app's final link.
- **AndroidX:** Apache-2.0 notices remain in existing META-INF entries; a full Apache-2.0 text and source credits for graphics-path are accessible offline. The published graphics-path 1.0.1 Kotlin source JAR and inspected native source headers identify Apache-2.0/AOSP notices. This does not replace upstream notices or assert an exhaustive third-party legal audit.
- **Kotlin runtime:** resolved stdlib 2.0.21 JetBrains-owned code and kotlinx.coroutines 1.8.1 use Apache-2.0 per their tagged upstream license documentation (`JetBrains/kotlin/v2.0.21/license/README.md`, `Kotlin/kotlinx.coroutines/1.8.1/LICENSE.txt`). The stdlib ledger also identifies JVM/common GWT/Guava-derived Apache portions and Boost-licensed `MathJVM.kt` (Eric Ford & Hubert Holin 2001). Exact upstream Boost/GWT/Guava texts and credits are now packaged in `Kotlin-runtime-NOTICES.txt`. Compiler/test-only, JS and Wasm dependencies are not presented as bundled application libraries.
- **FrequencyWords:** upstream README explicitly distinguishes MIT for code from CC BY-SA 4.0 for content. See https://github.com/hermitdave/FrequencyWords/blob/master/README.md and https://creativecommons.org/licenses/by-sa/4.0/ . The app's three word/count tables are data, not MIT-licensed code. New offline credits identify Hermit Dave, FrequencyWords/OpenSubtitles, the content license/source URI, and the filtering/format transformations. Data stay subject to their existing upstream terms; the app's Apache license is not substituted. CC BY-SA 4.0 requires appropriate retained attribution/notices and change indications; shared adapted material must satisfy its share-alike conditions, with no additional restrictions on the licensed material. Owner review must ensure the data distribution and store terms satisfy those conditions. Original FrequencyWords snapshot hashes and the original English transformation procedure are not recorded; French/German transformation scripts are present. Rebuild from pinned upstream inputs if exact provenance is required.

The app exposes Settings > About > Licenses & credits, with selectable text and complete offline Apache/OFL/Unicode/MIT license texts plus source/data notices. This improves notice availability; it does not cure unresolved rights by itself.

## Source snapshots verified for notices

- Google Fonts commit `6157dfaa640ca5738b4d1a7070fe389c3036d7fc`, `ofl/nunito/OFL.txt`: SHA-256 `580df76c95a1ec5ab878ceb25bb3d85c6a076804e9c970c8c6972aea775fdf65`; matches the packaged OFL file and the bundled font's copyright/license metadata.
- FrequencyWords commit `525f9b560de45753a5ea01069454e72e9aa541c6`, `README.md`: SHA-256 `a92fcc50e27f7b2d5860ec1c1ce794dc4c892d945bfa4c8c3136cdfd213ee710`; verifies the upstream code/content license distinction. This pins the license evidence, not the unknown original corpus snapshot used to generate app assets.
- Unicode license notice read from the canonical URL on 2026-10-30; the complete current V3 text is packaged. The different 2001–2024 copyright in unicodetools' repository LICENSE was not silently substituted for the canonical 1991–2026 notice.

## Unresolved English next-word corpus grant — stop affected change

`NOTICE` identifies `app/src/main/assets/pairs.tsv` as derived from Peter Norvig's n-gram counts. Git commit `96c2bfbb962c7f4326356537907962d93954cbd5` introduced this asset; no retained source snapshot/hash or English pair generator was found.

https://www.norvig.com/ngrams/ says its data files derive from the Google Web Trillion Word Corpus, distributed by the Linguistic Data Consortium, and lists `count_2w.txt`. Its explicit MIT permission is for **code**. That statement cannot safely be treated as a redistribution grant for corpus data or Bold Keys' derived table. No suitable separate corpus-data grant was verified. Do not label this table MIT or Apache, and do not treat the new attribution as legal clearance.

Owner options:
1. Supply a verified data redistribution grant and original input/provenance sufficient for this derived table, including any applicable upstream corpus conditions.
2. Remove `pairs.tsv` and the English next-word-table behavior in a separately reviewed change; dictionary corrections/completions can remain.
3. Replace it with an independently licensed or owner-authored next-word dataset, preserving its grant, provenance and required notices. Do not automatically substitute another web corpus with assumed permissions.

No removal, replacement or relicensing of this asset is performed in this review.

## Tatoeba contributor attribution — stop affected change

https://tatoeba.org/en/terms_of_use states that textual sentences default to CC BY 2.0 FR and require author attribution; the legal code is https://creativecommons.org/licenses/by/2.0/fr/legalcode . `tools/build_fr.py` and `tools/build_de.py` read a three-column sentence input, tokenize/count adjacent words, and retain ranked followers. They do not preserve author identities, sentence IDs in the output, input snapshot/license records or a mapping back to contributors.

The actual source exports are absent. Generic “Tatoeba contributors” credit and a license URI do not establish that all required attribution for these exact derivatives has been supplied. Do not invent contributor names or assume that aggregate counts remove every source condition. This is an unresolved provenance/attribution question, not a declaration that the tables necessarily infringe.

Owner options:
1. Recover the exact exports and author/license metadata, determine applicable attribution, and rebuild with a retained contributor/source ledger and appropriate notices.
2. Rebuild from a verified compatible corpus or a confirmed CC0 subset with preserved provenance, then review changed prediction behavior.
3. Remove `pairs_fr.tsv` and `pairs_de.tsv` and their next-word-table behavior in a separate reviewed change while retaining dictionaries/corrections.

The existing French/German tables are unchanged pending that decision; no new rights or contributor identities are asserted.

## Optional Gemma models

No weights are bundled. Catalogue URLs reference Unsloth's GGUF conversions of Gemma 3 270M. Google's current Gemma Terms of Use (https://ai.google.dev/gemma/terms , modified 2026-04-01) explicitly include Gemma 3. They impose distribution conditions, provision of terms/notices and use restrictions (https://ai.google.dev/gemma/prohibited_use_policy). A permissive conversion-repository code license would not replace the base-model terms.

The app now displays model terms/use-restriction information and an external terms link before the download description. No agreement is accepted on the owner's or user's behalf, no model is downloaded by this task, and no model grant is changed. Owner/legal review must decide how direct third-party downloads and any future model distribution meet the applicable terms. Imported models keep their own licenses and are not licensed by the app.

## Scope and limits

Public license pages and public source repositories were read; no private rights agreements were inspected. This review does not attest export compliance, resolve all underlying OpenSubtitles rights, certify store-license compatibility, or approve distribution. Corpus rights/provenance decisions remain with the owner. Continue code and unsigned validation while those affected data decisions are pending.
