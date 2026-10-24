## Summary of Changes
Provide a clear, concise summary of the changes made in this pull request.

## Motivation & Context
Why is this change required? What issue does it fix, or what performance enhancement does it provide?

## Testing & Verification
Describe the tests you ran to verify your changes:
- [ ] Ran `./gradlew build` successfully.
- [ ] Ran `RegionTorture` stress harness (`tools/RegionTorture.java`) with **0 errors/torn reads**.
- [ ] Tested in a live Minecraft environment (Cleanroom / Forge).

```text
Include any benchmark or RegionTorture output here if applicable
```

## Checklist
- [ ] My code follows the project's code style and zero-garbage principles.
- [ ] I have verified that all `@Unique` mixin state is properly initialized and thread-safe.
- [ ] I have updated relevant documentation, comments, or `CHANGELOG.md` if applicable.
- [ ] No sensitive or temporary files are included in this PR.
