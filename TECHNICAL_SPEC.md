# iSDR Driver Protocol (isdr-proto) - Deep Technical Specification

## 1. Core Architecture and Philosophy
The protocol utilizes `Frames.kt`, which operates exclusively on pre-allocated, reusable `ByteBuffer` arrays (`scratch` and `readBuf`), achieving zero GC allocations on the streaming path.

## 2. Frame Encapsulation and Byte Layout
Every transmission is a "Frame": `[Opcode: u8][Length: i32][Payload]`.
**Compatibility**: The HELLO version must match exactly. Known control/event payloads have exact shapes; malformed counts, unexpected tails and unknown opcodes are rejected. Optional capabilities require the corresponding negotiated feature bit.

## 3. Streaming Telemetry State Machine (`EV_TELEMETRY`)
To prevent the UI from displaying "0 Watts" when a sensor simply does not exist on specific hardware, `EV_TELEMETRY` utilizes a precise bitmask architecture. The `has*` flags (e.g., `TLM_HAS_TEMPERATURE`) are unpacked directly from a `u16` bitmask.

## 4. Signal Quantization (Block Floating Point)
Protocol **V4** keeps raw receiver IQ lossless: `EV_DATA` and `EV_DATA_RX` use `IQ_FORMAT_F32` (`IQ_WIRE_FORMAT`). Quantizing this wideband data before channelisation destroys weak signals beside strong neighbours; a later filter cannot restore them. The driver-to-station connection is local. After station filtering, `EV_DATA_NARROW` and `EV_DATA_RX_NARROW` use `NARROW_IQ_WIRE_FORMAT = IQ_FORMAT_BFP8`, preserving the remote link's one-byte-per-component cost. The opcode identifies the codec independently of control/media arrival order. `CMD_TX_IQ_NARROW` retains `TX_IQ_WIRE_FORMAT = IQ_FORMAT_BFP8`. V3 peers are refused by the exact version handshake.
1.  `encodeBfp8` scans the whole block for `peak = max |v|` over the interleaved I and Q floats (NaN samples are skipped rather than poisoning the peak).
2.  The peak is serialized once per block as a single `float32` header (`BFP_HEADER_BYTES = 4`), floored at `MIN_BFP_SCALE = 1e-9` so a digitally silent block does not divide by zero.
3.  Every sample is quantized to `round(v * 128 / peak)`, clamped into `[-128, 127]` — clamped, never wrapped, so an overshooting driver clips instead of flipping the sign of the loudest sample.

Eight bits give roughly 48 dB of SNR **within** a block; that is fixed by the word length and no encoding changes it. What the per-block float32 exponent buys is *range*: the quantizer rides the block's own peak, so a stream sitting well below full scale spends all eight bits on the signal that is actually there instead of on unused headroom, and the noise floor tracks the signal from block to block across the float32's full exponent range. This is what makes 8 bits enough **after channelisation has removed the strong neighbours** — the narrow window has little intra-block dynamic range left to represent. Against the `float32` wire alternative it is a 4x reduction (1 byte + 4 bytes per block, vs 4 bytes per sample).

### Receiver context and continuity

`EV_RX_CONTEXT` (`0x9B`) carries one atomic hardware snapshot: `i32 active`, `i32 streamMask`, `i32 sampleRateHz`, `i32 count`, then `i64 frequenciesHz[count]`. Count is 1..8, active is below count, the mask names only other existing receivers, rates and frequencies are positive. The LOs describe each actual DDC, so a Station never substitutes RX0's frequency for the active receiver. `EV_FREQUENCY` retains its generic/RX0 tuning meaning. Clients validate this station context without applying it to their RX0 command ledger.

`EV_DATA_NARROW` and `EV_DATA_RX_NARROW` prepend `i64 epoch`, `i32 widthHz`, `i64 centerHz`, `i32 decimation` (24 bytes) to their corresponding raw-event layout. `EV_NARROWBAND` carries its original `i32 widthHz`, `i64 centerHz`, `i32 decimation`, followed by `i64 epoch` (24 bytes total). Epochs are positive and increase on confirmed source-context or effective-geometry changes, including off/error; gap recovery and repeated requests keep the epoch. Media requires positive width/centre and a supported decimation. An off announcement is width=0, centre=0, D=1.

CTRL and RXIQ have independent transport ordering. A newer media header establishes runtime geometry before that IQ; older media is discarded. An old reliable announcement may settle its own correlated command but cannot roll runtime geometry backward. A media header cannot stand in for a command ACK/readback. Station emits only epoch-tagged IQ after a valid window is selected, including D=1; clients reject epochless raw IQ from peers advertising `FEAT_NARROWBAND`.

Narrow frames retain the source flush sequence. While the overlap-save filter buffers, an empty main frame still proves continuity; it must not reset audio DSP or be interpreted as a lost source block. Auxiliary and main receiver IQ are processed as one aligned group. Missing or inconsistent members invalidate the group rather than pairing samples from different flushes. New raw/narrow media opcodes use the unreliable RX channel; receiver context and geometry announcements remain reliable.

## 5. Zero-Copy Shared Memory Ring (`FEAT_SHM_RING`)
For heavy loopback:
1.  The protocol executes an Android Binder IPC transaction (`SHM_TRANSACT_GET_RING`) passing an `ashmem` file descriptor.
2.  `CMD_SHM_ATTACH` switches the data plane. The driver writes IQ floats directly into shared RAM.
3.  The TCP socket is demoted to a sequencer, sending empty `EV_SHM_FRAME` ticks containing only the slot index.
