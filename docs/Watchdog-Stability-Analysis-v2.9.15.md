# TooMuchZombies Watchdog Stability Analysis (v2.9.15)

## Summary
This document tracks the 60s watchdog freeze pattern where the server thread waits on chunk lighting/generation and defines the hardening changes shipped in `v2.9.15`.

Observed log pattern:
- `Timed out waiting for world statistics`
- `Server has not responded for 60 seconds`
- `Chunk wait ... status initialize_light -> light -> full`

Conclusion:
- Root stall happens in the chunk pipeline.
- TooMuchZombies can amplify the risk via high-volume pathing + spawning + terrain modification pressure.

## Risk Multipliers
- Frequent path commits to destinations that may cross into not-yet-ready chunks.
- High spawn throughput and high managed-zombie caps.
- Builder/Breaker world edits under load.
- AI planning/replan work not degrading aggressively enough under overload.

## Implemented Hardening (v2.9.15)
1. Loaded-chunk pathing guard (hard stop)
- Added destination chunk checks before issuing move commands.
- If destination chunk is not loaded, movement is clipped to the nearest loaded corridor or skipped.
- Applied in both layers:
  - `ZombieAgent.moveTo(...)`
  - `PaperNMSHandler.moveTo(...)`

2. Overload mode and dynamic degradation
- Introduced overload detection in `ZombieAIManager` based on zombie pressure and TPS.
- Under overload:
  - Disable active Builder/Breaker behavior aggressively.
  - Reduce async planning cadence and budget.
  - Lower per-tick replan budget.
  - Throttle idle/no-target behavior execution.

3. Smart pathing safety against unloaded target chunks
- If remembered/target location chunk is not loaded, route target is clipped to loaded nearby points.
- Prevents long jumps that can provoke chunk waits.

4. Night spawn chunk-check safety
- Replaced `spawn.getChunk().isLoaded()` with direct `world.isChunkLoaded(...)` checks to avoid accidental chunk touch side-effects during validation.

5. Safer default pressure settings
- Reduced default AI planning throughput and spawn pressure in `config.yml`.
- Fixed hard-cap logic so low configured caps are actually respected.

## Default Config Adjustments
- `zombie-ai.async.max-plans-per-tick: 48`
- `zombie-ai.async.plan-time-budget-ms: 3`
- `spawn.max-zombies-per-chunk: 14`
- `spawn.algorithm.chunk-cooldown-ms: 320`
- `spawn.algorithm.max-global-zombies: 720`
- `spawn.algorithm.max-near-player: 96`
- `spawn.algorithm.spawn-budget-per-player: 7`

## Expected Validation Outcomes
- No watchdog freezes during sustained horde pressure tests.
- Lower chunk-wait spikes in spark/timings under combat load.
- Reduced AI-induced long frame outliers when players move across chunk edges.
