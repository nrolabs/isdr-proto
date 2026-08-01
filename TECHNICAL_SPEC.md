# iSDR Driver Protocol (isdr-proto) - Deep Technical Specification

## 1. Core Architecture and Philosophy
The protocol utilizes `Frames.kt`, which operates exclusively on pre-allocated, reusable `ByteBuffer` arrays (`scratch` and `readBuf`), achieving zero GC allocations on the streaming path.

## 2. Frame Encapsulation and Byte Layout
Every transmission is a "Frame": `[Opcode: u8][Length: i32][Payload]`.
**Forward Compatibility**: When older clients receive frames with trailing bytes (e.g., appended feature telemetry), the `length` header instructs the parser to skip the unknown bytes.

## 3. Streaming Telemetry State Machine (`EV_TELEMETRY`)
To prevent the UI from displaying "0 Watts" when a sensor simply does not exist on specific hardware, `EV_TELEMETRY` utilizes a precise bitmask architecture. The `has*` flags (e.g., `TLM_HAS_TEMPERATURE`) are unpacked directly from a `u16` bitmask.

## 4. Signal Quantization (Block Floating Point)
Raw IQ is expensive on a link: at 2.4 MSPS, 16-bit I+Q is 76.8 Mbps and float32 I+Q is 153.6 Mbps. The protocol's wire default is `IQ_FORMAT_BFP8` (`DriverProto.IQ_WIRE_FORMAT`, and `TX_IQ_WIRE_FORMAT` for the transmit path).
1.  `encodeBfp8` scans the whole block for `peak = max |v|` over the interleaved I and Q floats (NaN samples are skipped rather than poisoning the peak).
2.  The peak is serialized once per block as a single `float32` header (`BFP_HEADER_BYTES = 4`), floored at `MIN_BFP_SCALE = 1e-9` so a digitally silent block does not divide by zero.
3.  Every sample is quantized to `round(v * 128 / peak)`, clamped into `[-128, 127]` — clamped, never wrapped, so an overshooting driver clips instead of flipping the sign of the loudest sample.

Eight bits give roughly 48 dB of SNR **within** a block; that is fixed by the word length and no encoding changes it. What the per-block float32 exponent buys is *range*: the quantizer rides the block's own peak, so a stream sitting well below full scale spends all eight bits on the signal that is actually there instead of on unused headroom, and the noise floor tracks the signal from block to block across the float32's full exponent range. This is what makes 8 bits enough **after channelisation has removed the strong neighbours** — the narrow window has little intra-block dynamic range left to represent. Against the `float32` wire alternative it is a 4x reduction (1 byte + 4 bytes per block, vs 4 bytes per sample).

## 5. Zero-Copy Shared Memory Ring (`FEAT_SHM_RING`)
For heavy loopback:
1.  The protocol executes an Android Binder IPC transaction (`SHM_TRANSACT_GET_RING`) passing an `ashmem` file descriptor.
2.  `CMD_SHM_ATTACH` switches the data plane. The driver writes IQ floats directly into shared RAM.
3.  The TCP socket is demoted to a sequencer, sending empty `EV_SHM_FRAME` ticks containing only the slot index.
