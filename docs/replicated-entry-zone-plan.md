# This document has been superseded

The replicated-entry-zone design described here was replaced by **zone instancing** (layering), which is fully implemented.

See `docs/engine-sharding-design.md` for the sharding architecture, including zone instancing with `InstanceSelector` and `ThresholdInstanceScaler` for load-balanced routing, and the `phase` command that lets players switch instances.
