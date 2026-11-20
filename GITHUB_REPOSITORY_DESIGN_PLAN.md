# GitHub Repository Design and Contributor Plan

This document explains how to make the public Control X repository look professional and ensure GitHub attributes new work to Vivek Kumar.

## Repository identity

- Repository: `vivekoffical08-jpg/Control-X`
- Maintainer: `VIVEK KUMAR`
- GitHub account: `vivekoffical08-jpg`
- Recommended description: "Native Android voice and screen-control agent with fast-path commands, accessibility automation, and safety-first execution."
- Recommended topics: `android`, `kotlin`, `jetpack-compose`, `android-accessibility`, `voice-assistant`, `groq`, `mysql`

## GitHub contributor attribution

Recent commits already use:

- Name: `VIVEK KUMAR`
- Email: `vivekoffical08@gmail.com`

GitHub can link a commit to the account when the author email belongs to and is verified on that account.

Earlier commits have the GitHub Copilot App identity. They remain in public Git history and may continue appearing under Copilot. The `Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>` trailer is co-author metadata, not a replacement for the main Git author.

To prevent Copilot from becoming the future main author:

1. Keep this local Git identity:
   - `git config user.name "VIVEK KUMAR"`
   - `git config user.email "vivekoffical08@gmail.com"`
2. Never use the Copilot noreply address as the Git author.
3. Add an explicit co-author trailer only if team attribution is desired.

Removing Copilot from existing history requires rewriting every commit and force-pushing the rewritten branch. That changes all commit IDs and can break open pull requests, tags, releases, and local clones. It should not be done without explicit approval.

## README presentation

The README should lead with:

- A short product description.
- A direct latest-release download link.
- A demo screenshot or short GIF.
- Supported Fast-Path commands.
- Required Android permissions and reasons.
- Security and privacy boundaries.
- Build/development instructions.
- Maintainer attribution.

Do not claim universal automation where Android policy, secure apps, accessibility restrictions, or OEM controls prevent it.

## Repository settings

Configure GitHub Settings:

- Add the public description.
- Add topics.
- Enable Issues.
- Enable Discussions only if support is intended.
- Keep Releases public.
- Add a social-preview banner.
- Add branch protection for `main`:
  - Require pull request review.
  - Require passing status checks.
  - Prevent force pushes.
  - Prevent branch deletion.

## Release presentation

Each release should include:

- A semantic version.
- A unique Android `versionCode`.
- A unique Android `versionName`.
- Release notes covering features, fixes, known limitations, and required permissions.
- A descriptive APK name such as `Control-X-v0.2.0.apk`, not `app-debug.apk`.
- The SHA-256 checksum.

## Quality gate

No Android release should be published until:

1. Unit tests pass.
2. Backend/server builds if backend files change.
3. APK assembly passes.
4. APK signature and version metadata are inspectable.
5. Cold-start works on a physical Android device.
6. Permissions and Accessibility onboarding are tested.
7. Voice/text/Fast-Path smoke tests are completed.
8. Crash logs are clear.

## History decision

Recommended first step: preserve history and continue future commits as Vivek.

History rewrite and force-push should be a separate approved operation because of public-repository side effects.

## Immediate order

1. Finish and verify the Android startup and Fast-Path experience.
2. Verify future commits are authored by Vivek.
3. Update repository description and topics.
4. Add README screenshots and release badge.
5. Add a social preview.
6. Configure branch protection.
7. Decide separately on public-history rewriting.
