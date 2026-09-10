# Enroly integration maintenance

This repository is Enroly's controlled integration fork of [Floci](https://github.com/floci-io/floci). It provides a reviewed boundary for emulator behavior used by Enroly and a reproducible source for Enroly-controlled images.

This guide covers the public fork workflow. Registry destinations, credentials, image publication and promotion, consumer rollout and rollback, and environment-specific configuration belong in consumer-owned private documentation. Do not add those details to this public repository.

## Current integration source

Do not infer the current integration source from the default branch name. Check the selected branch and exact commits before testing or building:

```bash
git status --short --branch
git rev-parse HEAD
git log --oneline --decorate -5
```

The current tested integration line is:

| Purpose | Source |
|---|---|
| Integration branch | `fix/clean-create-regression-fix` |
| Runtime candidate | `59459031b71d1ef0d47d7bf2d0ebaa471cc29ea2` |
| Validation head | `404cd659a2b4a7748435a1e7f537322e41099c8d` |
| Candidate platform | `linux/arm64` |

The validation commit changes test source only. It strengthens coverage without changing the runtime candidate, so it does not require a new image build. Record both commits: the runtime commit identifies the image source, while the validation commit identifies the test state used to accept it.

The fork's current `main` branch is not the current integration source. The long-term default branch and integration branch policy are pending maintainer decision. Until that policy is recorded, use the explicit branch and commits above. Do not merge, rename, rebase, or redirect consumers based on an assumed branch policy.

## Diagnose from a minimal emulator reproducer

Start with the smallest sanitized example that demonstrates an emulator difference. Keep the reproducer in Floci's terms rather than copying an Enroly application or its configuration into this public repository.

1. Identify the AWS service, operation, request shape, expected behavior, and actual behavior.
2. Reproduce the problem with an AWS SDK client when practical. Use a focused protocol-level test only when the SDK cannot expose the behavior clearly.
3. Remove customer data, internal hostnames, account details, credentials, and consumer topology from the reproducer.
4. Confirm the reproducer fails against the selected baseline before changing runtime code.
5. Identify the affected protocol and layers, such as controller, service, storage, configuration, or Docker integration. Make the architectural boundary explicit in the pull request.

A consumer failure is evidence, not the patch specification. First isolate whether the mismatch is in Floci, consumer configuration, or the consumer itself. Change this fork only when the minimal reproducer demonstrates emulator behavior that should change.

## Develop with Maven and tests first

Follow the repository's existing Java and AWS protocol patterns in `AGENTS.md`. Add the narrowest realistic regression test before implementation, observe it fail for the expected reason, then make the smallest runtime change that passes it.

Run a focused test while iterating:

```bash
./mvnw test -Dtest=ClassName
./mvnw test -Dtest=ClassName#methodName
```

Run the complete Maven test suite before proposing a runtime change:

```bash
./mvnw test
```

When a change affects generated service action tables or service registration, run the existing documentation gates:

```bash
make docs-test
make docs-check
```

For documentation changes, build the site with the repository requirements:

```bash
python3 -m pip install -r docs/requirements.txt
python3 -m mkdocs build --strict
```

A commit that changes only tests and documentation does not require a candidate image rebuild. Any change to runtime source, runtime resources, dependencies, or image inputs does require a new candidate image and new provenance.

## Build an architecture-specific local candidate

Build candidates from the exact runtime commit, not from an uncommitted worktree and not from a moving branch name. Use an explicit platform and include the runtime SHA and architecture in the local tag. The following example preserves the current worktree by building from a detached temporary worktree:

```bash
runtime_ref=59459031b71d1ef0d47d7bf2d0ebaa471cc29ea2
runtime_sha=$(git rev-parse "$runtime_ref")
short_sha=$(git rev-parse --short=12 "$runtime_sha")
platform=linux/arm64
architecture_tag=linux-arm64
candidate_tag="enroly-floci:candidate-${short_sha}-${architecture_tag}"
build_dir="../floci-candidate-${short_sha}"

git worktree add --detach "$build_dir" "$runtime_sha"
docker build \
  --platform "$platform" \
  --build-arg VERSION="$runtime_sha" \
  --tag "$candidate_tag" \
  --file "$build_dir/docker/Dockerfile" \
  "$build_dir"
git worktree remove "$build_dir"

docker image inspect "$candidate_tag" \
  --format 'image-id={{.Id}} os={{.Os}} architecture={{.Architecture}}'
```

The current candidate is `linux/arm64`. Do not describe it as multi-architecture or use it on `linux/amd64`. Build and validate a separate architecture-specific candidate when another platform is required.

For each candidate, retain this provenance with the consumer's private release record:

- Full runtime source SHA
- Full validation SHA
- Target operating system and architecture
- Local candidate tag and image ID
- Commands and test results used for acceptance
- Immutable image reference and registry digest after authorized publication

Publication is a separate, consumer-owned process and is intentionally not documented here. After authorized publication, the registry digest can be read without exposing registry credentials in this repository:

```bash
docker buildx imagetools inspect "$published_image_ref"
```

Use the digest-qualified image reference for immutable consumer selection. A mutable tag alone is not sufficient provenance.

## Review changes in the fork

All integration changes require a reviewed pull request in this fork against the branch selected by the fork maintainers. Do not push a runtime change directly into the integration line.

The pull request should state:

- The sanitized minimal reproducer and incorrect emulator behavior
- The expected AWS-compatible behavior
- The protocol and architectural layers affected
- The failing test added before the implementation
- The focused and full Maven test results
- The runtime source SHA and validation SHA
- Whether an image rebuild is required
- The candidate architecture and local image ID when an image was built

Keep fork-specific policy additive and narrow. Do not rewrite inherited upstream documentation merely to restate this guide.

This fork inherits contribution rules, ownership declarations, pull request templates, and metadata checks from upstream. Those policies may not yet match the fork's intended maintenance model. Treat them as active until maintainers explicitly reconcile them. Do not disable checks, suppress rules, remove owners, forge approvals, or alter metadata merely to make a pull request pass. If a rule conflicts with the selected fork workflow or a required owner cannot review, stop and ask the fork maintainers to resolve the policy explicitly in a separate reviewed change.

## Handle upstream deliberately

An upstream sync is a reviewed maintenance change, not an automatic mirror operation. Select an exact upstream ref, inspect its changes, preserve or intentionally replace Enroly-specific behavior, and validate the combined result through a fork pull request. A sync must not silently move the integration baseline.

Do not open a pull request against `floci-io/floci` unless a maintainer explicitly requests it. If a fork fix is accepted upstream, that acceptance does not automatically change this fork, publish a new image, or alter any consumer. Import the upstream change deliberately, rerun the fork and consumer acceptance process, and retain the Enroly-controlled image path as the consumer boundary.

The controlled image path remains permanent even when all relevant changes exist upstream. This preserves review, provenance, architecture selection, and rollout control. The actual image destination and promotion procedure remain in consumer-owned private documentation.
