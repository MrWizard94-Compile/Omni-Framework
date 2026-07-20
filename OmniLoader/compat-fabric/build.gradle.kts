// See the root build for shared configuration.
//
// The Fabric-family compat layer (CMP-2). Metadata-level: claims
// fabric.mod.json, imports it into the canonical model, and rewrites NO jar
// content — the quilt-loader proof that hosting this ecosystem does not
// require translation. Intermediary→official remapping is still needed, but
// that is the pipeline's own pass configured with this layer's mappings.
dependencies {
    api(project(":compat-api"))
}
