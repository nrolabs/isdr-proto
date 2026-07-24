# isdr-proto

Wire contract between the **iSDR** app and the **iSDR Drivers** hardware host:
one frame per command/event — `[u8 opcode][i32 length][payload]`, big-endian —
over a loopback TCP socket (`127.0.0.1:45733`). Commands map 1:1 onto driver
client calls; events carry IQ blocks + display spectrum, connection status,
telemetry and sweep blocks.

This is a plain Android library module, consumed as a git submodule by both
sides of the boundary.

## License

Dual-licensed — GPLv2+ only as part of the iSDR Drivers application; all
other rights reserved. See [LICENSE](LICENSE).
