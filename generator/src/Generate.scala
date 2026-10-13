package generator

import chisel3._
import chisel3.util._
import common._
import common.storage._
import common.axi._
import common.ToZero

/** Runtime RDMA-over-UDP packet generator.
  *
  * One `start` pulse transmits a single packet whose payload length is taken
  * from the runtime `payload_len` input (in bytes). The Ethernet/IPv4/UDP/IBH/
  * RETH headers are assembled on the fly from [[GeneratorConfig]] constants and
  * the length-dependent fields (IPv4 total length + checksum, UDP length, RETH
  * length) are recomputed for the requested length. This lets the payload
  * length be changed directly from the testbench without re-elaboration.
  */
class slicer(config: GeneratorConfig) extends Module {
    val io = IO(new Bundle {
        val start = Input(Bool())
        val payload_len = Input(UInt(16.W))   // payload bytes (multiple of 64)
        val idle_cycle  = Input(UInt(8.W))
        val qpn = Input(UInt(24.W))
        val m_axis = Decoupled(new AXIS(config.axi_data_width))
        val busy = Output(Bool())
        val done = Output(Bool())
    })

    val headerBytes = Wire(Vec(config.header_size, UInt(8.W)))
    for (i <- 0 until config.header_size) {
        headerBytes(i) := config.fixed_header_bytes(i).U
    }

    // Latch all per-packet controls so an in-flight packet is stable.
    val payloadLen = RegInit(0.U(16.W))
    val packetQpn = RegInit(config.qpn.U(24.W))

    // Length-dependent fields (all derived from the latched payload length).
    val udpLen     = 36.U + payloadLen                     // UDP(8)+IBH(12)+RETH(16)+payload
    val ipTotalLen = 56.U + payloadLen                     // IPv4(20)+UDP_len
    val rethLen    = payloadLen.pad(32)                    // 32-bit RETH length (msg_length == payload)

    // IPv4 header checksum: the only variable word is the total-length field.
    val sum     = config.ipv4_checksum_const_sum.U + ipTotalLen
    val fold1   = (sum & 0xFFFF.U) + (sum >> 16)
    val fold2   = (fold1 & 0xFFFF.U) + (fold1 >> 16)
    val ipChecksum = ~fold2(15, 0)

    // Patch the variable header bytes.
    headerBytes(16) := ipTotalLen(15, 8)
    headerBytes(17) := ipTotalLen(7, 0)
    headerBytes(24) := ipChecksum(15, 8)
    headerBytes(25) := ipChecksum(7, 0)
    headerBytes(38) := udpLen(15, 8)
    headerBytes(39) := udpLen(7, 0)
    headerBytes(47) := packetQpn(23, 16)
    headerBytes(48) := packetQpn(15, 8)
    headerBytes(49) := packetQpn(7, 0)
    headerBytes(66) := rethLen(31, 24)
    headerBytes(67) := rethLen(23, 16)
    headerBytes(68) := rethLen(15, 8)
    headerBytes(69) := rethLen(7, 0)

    // Payload byte generator: 64-bit word k = payload_init + payload_step * k,
    // emitted big-endian.
    def payloadByte(j: UInt): UInt = {
        val wordIdx    = j >> 3
        val byteInWord = j & 7.U
        val word       = (config.payload_init.U(64.W) + config.payload_step.U(64.W) * wordIdx)(63, 0)
        val shift      = (7.U - byteInWord) << 3
        (word >> shift)(7, 0)
    }

    // Total beat count = ceil((header_size + payload_len) / 64).
    val totalBytes = config.header_size.U + payloadLen
    val totalBeats = (totalBytes + 63.U) >> 6

    val maxPayloadBytes = (1 << 16) - 1
    val beatWidth = math.max(1, log2Ceil((config.header_size + maxPayloadBytes + 63) / 64 + 1))

    val sIdle :: sSend :: Nil = Enum(2)
    val state    = RegInit(sIdle)
    val beatIdx  = RegInit(0.U(beatWidth.W))
    val idleCount = RegInit(0.U(8.W))
    val pending   = RegInit(false.B)
    val idleReload = Mux(io.idle_cycle === 0.U, 0.U, io.idle_cycle - 1.U)
    val running   = Wire(Bool())
    running := io.start   // 电平跟随：start 高 → 持续发包，拉低 → 发完当前包后停

    // Build the current 64-byte beat by selecting from the header or payload.
    val beatBase = beatIdx << 6
    val beatBytes = Wire(Vec(64, UInt(8.W)))
    for (i <- 0 until 64) {
        val gb     = beatBase + i.U
        val isHdr  = gb < config.header_size.U
        val hdrIdx = Mux(isHdr, gb, 0.U)
        val plIdx  = Mux(!isHdr, gb - config.header_size.U, 0.U)
        beatBytes(i) := Mux(isHdr, headerBytes(hdrIdx), payloadByte(plIdx))
    }

    val rem        = totalBytes - beatBase
    val isLastBeat = beatIdx === (totalBeats - 1.U)
    val keep       = Mux(rem >= 64.U, Fill(64, 1.U(1.W)), ((1.U(64.W) << rem) - 1.U))

    io.m_axis.valid     := (state === sSend)
    io.m_axis.bits.data := Cat(beatBytes.reverse)
    io.m_axis.bits.keep := keep
    io.m_axis.bits.last := isLastBeat

    io.busy := (state === sSend) || pending || running
    io.done := false.B

    switch(state) {
        is(sIdle) {
            // A timed run may stop during an inter-packet gap. Cancel the
            // pending launch instead of emitting one packet after start falls.
            when(!running) {
                pending := false.B
                idleCount := 0.U
            }.elsewhen(pending) {
                when(idleCount === 0.U) {
                    // Sample QPN at packet launch, after the idle gap, so a
                    // timed version switch cannot be hidden by a stale QPN
                    // captured several gap cycles earlier.
                    packetQpn := io.qpn
                    state := sSend
                    pending := false.B
                }.otherwise {
                    idleCount := idleCount - 1.U
                }
            }.elsewhen(running) {
                payloadLen := io.payload_len
                packetQpn := io.qpn
                beatIdx := 0.U

                when(io.idle_cycle === 0.U) {
                    state := sSend
                }.otherwise {
                    idleCount := idleReload
                    pending := true.B
                }
            }
        }
        is(sSend) {
            when(io.m_axis.fire) {
                    when(isLastBeat) {
                        io.done := true.B
                        beatIdx := 0.U

                        when(running) {
                            payloadLen := io.payload_len
                            packetQpn := io.qpn

                        when(io.idle_cycle === 0.U) {
                            // Start the next packet without inserting an idle beat.
                            pending := false.B
                            state := sSend
                        }.otherwise {
                            idleCount := idleReload
                            pending := true.B
                            state := sIdle
                        }
                    }.otherwise {
                        pending := false.B
                        state := sIdle
                    }
                }.otherwise {
                    beatIdx := beatIdx + 1.U
                }
            }
        }
    }
}
