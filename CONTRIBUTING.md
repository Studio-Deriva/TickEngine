# Contributing

TickEngine is made by [Studio Deriva](https://discord.gg/pQPWr7vfHu), a Minecraft modding team of
developers and artists. Everything we make is free and open source — source included, no paywall,
no premium version. Read it, learn from it, fork it, build on it.

## Ideas

Tell us what you would want on your server. We read everything. If your idea turns into a mod or a
feature, you get credited for it in the release notes.

A good suggestion answers five things:

```
Idea:             one line, the whole thing
What it does:     what gets measured, throttled or tuned
Why it's worth building: what is missing without it
Version / loader: if it only makes sense on one
References:       links, screenshots, spark profiles, mods that come close
```

One idea per message, and check whether somebody already suggested it before posting it again.

## Bugs

The quickest way: run `/tickengine report` (general, or about one block entity, entity or mod) and click
the link it gives you. It opens a GitHub issue with the versions, settings, rule, profiler numbers and
mod list already filled in; you describe the problem and submit it. Attach the file it wrote to
`tickengine/reports/` too. Nothing is sent without you submitting it.

You can also open a ticket on [our Discord](https://discord.gg/pQPWr7vfHu), with:

- the TickEngine version, the Minecraft/Fabric versions and the full mod list (especially Lithium,
  C2ME, ServerCore and any mod that adds machines or mobs),
- what happened and what you expected,
- the relevant part of `logs/latest.log` (lines from `TickEngine`),
- for performance problems, the output of `/tickengine status` and an HTML report from
  `/tickengine profile start` (in `tickengine/reports/`),
- for something that breaks only far from players: which block or mob, and whether a rule with
  interval 1 for it makes the problem go away.

## Pull requests

```
./gradlew build               # compiles and tests every Minecraft version
./gradlew :1.21.11:runServer  # dev server in versions/1.21.11/run/
```

House style:

- Java 21, tabs, no wildcard imports.
- Comments say **why**, not what, and talk about the code.
- Everything is server-side: the mod must never be required on the client.
- Nothing runs on the server thread that could block: disk writes go to the IO pool.
- The hot path (every block entity and entity tick) allocates nothing and does as little as
  possible; anything that can be computed once per tick or once per config change is.
- Every behaviour change is an option in `config/tickengine.json5`, with a comment.
- Mixin targets are checked against the decompiled sources of every supported version, not guessed.
- Code that differs between Minecraft versions goes in `compat/MinecraftCompat.java` behind Stonecutter
  `//? if` comments where possible; run `./gradlew "Reset active project"` before committing so `src/`
  stays in the 1.21.11 state.

## Licence

By contributing you agree your work ships under the MIT licence, like the rest of the project.
Do not post work that is not yours to give away: somebody else's paid commission, leaked content,
or anything you are under an agreement to keep quiet.
