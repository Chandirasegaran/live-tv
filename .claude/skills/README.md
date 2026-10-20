# Claude Code skills

Project skills that Claude Code loads when working in this repository. They're copies of
third-party skills, kept here so anyone working on the project gets the same guidance.

| Skill | Source | License |
|---|---|---|
| `adaptive`, `agp-9-upgrade`, `android-profiler`, `jetpack-compose-performance`, `leanback-to-compose-tv-migration`, `navigation-3`, `r8-analyzer`, `testing-setup` | Official Android skills, [android/skills](https://github.com/android/skills), installed with the [Android CLI](https://developer.android.com/tools/agents) (`android skills add --agent=claude-code`) | Apache-2.0 (`LICENSE.txt` in each folder) |
| `android-development` | [dpconde/claude-android-skill](https://github.com/dpconde/claude-android-skill) | MIT (`LICENSE`) |
| `claude-android-ninja` | [Drjacky/claude-android-ninja](https://github.com/Drjacky/claude-android-ninja) | Apache-2.0 (`LICENSE.md`) |

The app deliberately doesn't follow all of their defaults (Compose, Hilt, multi-module): it
follows [docs/design-language.md](../../docs/design-language.md) to stay light on 1 GB TVs.
The skills are still used for media, performance and build guidance.

To update the official ones:

```bash
android skills update --agent=claude-code --project=.
```
