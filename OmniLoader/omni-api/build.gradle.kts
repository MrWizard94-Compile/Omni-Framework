// See the root build for shared configuration.
//
// omni-api is what native mods compile against, and ARCHITECTURE §2 gives it a
// SEPARATE RELEASE CADENCE from the loader. That is the whole point of the
// module: a mod built against api 1.x must keep working when the loader ships
// 0.2, 0.3, 0.4 — the corpus lesson from Fabric API's split.
//
// So it deliberately depends on NOTHING. Not loader-core, not loader-model. A
// dependency here would drag loader internals into every mod's compile
// classpath and make the API's stability a fiction: mods would compile against
// types the loader is free to change.
version = "1.0.0"
