# Protected cloud releases

The manual **Protected Android release** workflow builds on a standard `ubuntu-24.04` GitHub-hosted runner. It does not require the Mac after initial credential migration. It uses the **existing** production key, not a new key, so installed Bold Keys builds retain update continuity.

## One-time setup (repository owner only)

1. Review and merge the workflow and release tooling onto `JayMandava/bold-keys` **main**. Full action commit SHAs are pinned; review dependency updates before changing them.
2. Create environment **`production-release`** with:
   - Required reviewer: **JayMandava** (user, not a team).
   - **Allow self-review**, explicitly chosen for this sole-owner repository. The owner can manually trigger and separately approve a run; this is an approval gate, not two-person control.
   - **Disable administrator bypass**.
   - Deployment branches/tags: **selected branches and tags**, one rule, **branch `main`** only. Do not select “protected branches only”: when no branch protection exists, that option allows all branches.
3. From the Mac holding the existing production keystore, upload these **environment secrets**, directly to GitHub's encrypted secret API for `JayMandava/bold-keys` / `production-release`:

   | Secret | Value |
   | --- | --- |
   | `BOLD_RELEASE_KEYSTORE_BASE64` | Base64 encoding of the existing production keystore bytes |
   | `BOLD_RELEASE_STORE_PASSWORD` | Existing keystore password |
   | `BOLD_RELEASE_KEY_ALIAS` | Existing production key alias |
   | `BOLD_RELEASE_KEY_PASSWORD` | Existing key password |

   Do not put them in repository/organization secrets, source code, shell history, logs, chat, artifacts, Library, or a cloud workspace. Use a process that reads private files/passwords locally and sends values via standard input or the encrypted-secret API, never command arguments or printed output. Base64 is **encoding, not encryption**; GitHub encrypts the secret after local encryption for its API. GitHub secrets have a 48 KB size limit, including base64 expansion. If the encoded key does not fit, stop and design another approved approach—do not split it into public files or invent a fallback.
4. Retain an offline backup. These credentials can sign updates as Bold Keys; compromise is serious. Restrict repository administrators and review code/workflow changes before approving a signing job. Environment approval cannot protect against an owner approving malicious workflow code or an administrator deliberately weakening settings. This implementation does **not** create or alter branch protection, other security settings, credentials, or agreements.
5. Confirm the production certificate SHA-256 is exactly:

   ```text
   b237e1aec0bfdae579caebe1a0d97517af1b7c066adc4b3d197b641adfa46c34
   ```

   The public certificate is pinned in the verifier. Never update it merely to make a failing run pass.

## First run: validation only

- Select **main** in Actions → Protected Android release → Run workflow.
- `operation`: **validate** (default).
- `version`: **0.6.1**, or the exact version currently in source.
- `commit_sha`: full lowercase 40-character SHA of the reviewed **current main**, including this workflow. The old `v0.6.1` SHA is not the workflow source SHA.
- Wait for tests/build to pass, inspect the exact commit, inputs and logs, then **JayMandava approves the `production-release` deployment**. Agents must not bypass or approve that gate on the owner's behalf.
- A successful run produces a one-day artifact containing the production-signed APK, checksum, provenance manifest and reviewed notes. **Validation never creates or changes a tag/release**; existing `v0.6.1` and its assets remain untouched. `publish` is skipped and no validation job has `contents:write`.
- Download and independently check the resulting APK's certificate and checksum before calling the migration verified. Positive signing is intentionally not tested with dummy/new private keys.

## A future release

1. On a separate feature branch, make the reviewed changes, increase both stable `versionName` and `versionCode`, and add reviewed `docs/releases/X.Y.Z.md`. Use single-line Conventional Commits. Merge normally; do not bypass protections.
2. Dispatch **publish** for that exact current-main SHA and version. The version must exceed the latest release, the code must increase from its tagged source, and neither tag nor release may exist. Reusing `0.6.1` is rejected.
3. The secret-free job runs release unit tests, lint and unsigned build. It checks package `ai.boldkeys`, min SDK 33/target SDK 36, non-debuggable status, arm64-only native libraries, 16 KB ZIP alignment and unsigned status.
4. After owner approval, a new isolated runner verifies the exact artifact ID and manifest/APK hashes, then signs without Gradle or repository scripts executing in the secret-bearing step. Missing/invalid secrets fail closed; there is no debug key fallback. The key and confidential tool output live only in a mode-700 temporary directory, removed by an EXIT trap and an always-run cleanup step; the hosted runner is ephemeral. No signing material is cached or uploaded.
5. A separate job with `contents:write` (no signing secrets) rechecks main, environment, provenance, certificate, package, version, alignment and checksum. It creates a **new** tag at the approved commit, creates a draft release, uploads and verifies server SHA-256 digests, then publishes. Asset names remain `bold-keys-X.Y.Z.apk` and `SHA256SUMS-X.Y.Z.txt`.
6. Inspect the public release and downloaded assets. If an API call fails after creating a tag/draft, **stop and inspect the actual remote outcome**. The workflow never forces tags, clobbers assets, deletes drafts, or automatically retries partial publication. Existing tags/releases cause a closed failure; manual recovery requires explicit authorization.

## Operational limits and verification

- Only `workflow_dispatch` on this exact repository's main is allowed. No PR, fork, push, tag, or scheduled trigger consumes secrets.
- A fixed concurrency group serializes release runs; active runs are not canceled. GitHub may replace an older **pending** run with a newer pending dispatch—do not queue multiple releases.
- SDK packages and the Gradle distribution checksum are pinned. JDK 17 is obtained through the SHA-pinned official setup action. Preaccepted hosted-runner SDK licenses are required; the workflow never runs license-acceptance commands. Missing tooling/licenses fail rather than accept new agreements.
- Standard GitHub-hosted runners are free for this **public** repository; this workflow uses no larger runners or paid services. It sets 60/20/15-minute job timeouts, no dependency caches, compression disabled for already-compressed APKs, and **one-day artifact retention**. Artifact storage still counts toward applicable account allowances; do not treat retention as unlimited/free storage. Public release assets persist intentionally. If repository visibility, runner class, plan or billing rules change, recheck cost before running.
- Secret-free local tests: `python3 -m unittest discover -s tests -p 'test_release_workflow.py' -v`; `python3 tests/test_release_signing_modes.py` (requires Linux, JDK17 and configured Android SDK). Validate workflow syntax with `actionlint`. Actual protected-environment admission, hosted-runner provisioning and real signing require the approved first validation run.
- F-Droid and voice typing remain out of scope.

## Official references

- [GitHub secure use: least privilege, pinning and secret handling](https://docs.github.com/en/actions/reference/security/secure-use)
- [Managing environments and required reviewers](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
- [Deployment branch policy semantics](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [GitHub workflow syntax and concurrency](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)
- [Android apksigner](https://developer.android.com/tools/apksigner) and [zipalign](https://developer.android.com/tools/zipalign)
