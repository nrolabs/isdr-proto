# iSDR Driver Protocol (isdr-proto) - Deep Technical Specification

## 1. Core Architecture and Philosophy
The protocol utilizes `Frames.kt`, which operates exclusively on pre-allocated, reusable `ByteBuffer` arrays (`scratch` and `readBuf`), achieving zero GC allocations on the streaming path.

## 2. Frame Encapsulation and Byte Layout
Every transmission is a "Frame": `[Opcode: u8][Length: i32][Payload]`.
**Forward Compatibility**: When older clients receive frames with trailing bytes (e.g., appended feature telemetry), the `length` header instructs the parser to skip the unknown bytes.

## 3. Streaming Telemetry State Machine (`EV_TELEMETRY`)
To prevent the UI from displaying "0 Watts" when a sensor simply does not exist on specific hardware, `EV_TELEMETRY` utilizes a precise bitmask architecture. The `has*` flags (e.g., `TLM_HAS_TEMPERATURE`) are unpacked directly from a `u16` bitmask.

## 4. Signal Quantization (Block Floating Point)
A 24-bit ADC hardware architecture requires ~76.8 Mbps at 2.4 MSPS. The protocol solves this via `IQ_FORMAT_BFP8`.
1.  The DSP extracts the peak magnitude: `peak = max(|I|, |Q|)`.
2.  The peak is serialized as a single `float32`.
3.  Every sample is quantized to an 8-bit integer relative to the peak: `Int8 = round((sample / peak) * 127)`.
This guarantees the quantization noise floor tracks the signal peak, achieving 96 dB of dynamic range using only 1 byte per sample, crushing transport bandwidth by 75%.

## 5. Zero-Copy Shared Memory Ring (`FEAT_SHM_RING`)
For heavy loopback:
1.  The protocol executes an Android Binder IPC transaction (`SHM_TRANSACT_GET_RING`) passing an `ashmem` file descriptor.
2.  `CMD_SHM_ATTACH` switches the data plane. The driver writes IQ floats directly into shared RAM.
3.  The TCP socket is demoted to a sequencer, sending empty `EV_SHM_FRAME` ticks containing only the slot index.
