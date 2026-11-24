# ShaleDB Engineering Guidelines

## Project overview

ShaleDB is a persistent, log-structured key-value database
written in Java 25.

Modules:
- storage: storage engine and persistence mechanisms
- api: Protocol Buffers and gRPC service contract
- server: application lifecycle and network interface

## Build and testing

- Use Bazel, not Gradle or Maven.
- Run storage tests: bazel test //storage:storage_tests
- Run all tests: bazel test //:tests
- Use JUnit 5, AssertJ and Mockito.
- Follow existing package and test naming conventions.

## Engineering principles

- Prioritize correctness over performance.
- Preserve existing architectural boundaries.
- Prefer minimal, focused changes.
- Do not introduce unnecessary abstractions.
- Do not introduce dependencies without approval.
- Do not change the on-disk record format without approval.

## Storage invariants

- The newest record for a key determines its logical state.
- Tombstones must shadow older values across segments.
- Empty values are distinct from missing keys.
- Invalid operations must not modify persistent state.
- Index entries must reference valid records.
- Successful writes must satisfy the configured durability policy.
- Ordinary restarts must preserve the logical database state.

## Testing requirements

- Use real temporary files for persistence tests.
- Mock dependencies only when isolation or fault injection
  is necessary.
- Tests must be deterministic and independent.
- Randomized tests must use reproducible seeds.
- Bug fixes must include regression tests.
- Never weaken assertions to make tests pass.
- Do not silently change production code during test-only tasks.

## Git workflow

- Never commit, push, merge or create pull requests.
- Never overwrite existing user changes.
- Keep changes restricted to the assigned task.
- Leave all changes uncommitted for human review.

## Definition of done

Before completing a task:
1. Run the relevant tests.
2. Run the complete test suite.
3. Review the resulting diff.
4. Report changes, test results and unresolved problems.
5. Stop and wait for human review.
