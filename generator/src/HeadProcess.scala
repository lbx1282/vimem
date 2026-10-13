package generator

import chisel3._
import chisel3.util._
import common.RSHIFT
import common.axi.AXIS

/** Header fields extracted from packets produced by [[slicer]].
  *
  * Multi-byte fields retain their normal numeric/network-order value. For
  * example, bytes `c0 a8 01 01` are reported as `0xc0a80101`.
  */
class HeadProcessMeta extends Bundle {
  val src_ipv4 = UInt(32.W)
  val dst_ipv4 = UInt(32.W)
  val src_port = UInt(16.W)
  val dst_port = UInt(16.W)
  // val ipv4_ttl = UInt(8.W)
  // val ipv4_identification = UInt(16.W)
  // val ipv4_dont_fragment = Bool()
  val op_code = UInt(8.W)
  val qpn = UInt(24.W)
}

/** Splits the generator packet header from its AXI-Stream payload.
  *
  * The generator emits a 70-byte Ethernet + IPv4 + UDP + IBH + RETH header on
  * a 512-bit AXI stream. The first 64-byte beat is consumed here to capture
  * metadata. The remaining six header bytes are removed with [[RSHIFT]], so
  * `reth_data_out` starts at payload byte zero and preserves `keep`/`last`.
  * Both outputs support independent downstream backpressure.
  */
class HeadProcess(
  config: GeneratorConfig = GeneratorConfig()
) extends Module {

  val io = IO(new Bundle {
    val rx_data_in = Flipped(Decoupled(new AXIS(config.axi_data_width)))
    val meta_out = Decoupled(new HeadProcessMeta)
    val reth_data_out = Decoupled(new AXIS(config.axi_data_width))
  })

  val dataBytes = config.axi_data_width / 8
  val residualHeaderBytes = config.header_size - dataBytes

  val sHeader :: sPayload :: Nil = Enum(2)
  val state = RegInit(sHeader)

  // The generator maps packet byte i to data(8*i+7, 8*i).
  val headerData = io.rx_data_in.bits.data
  def packetByte(index: Int): UInt =
    headerData(8 * index + 7, 8 * index)
  def packetUInt(firstByte: Int, byteCount: Int): UInt =
    Cat((0 until byteCount).map(offset => packetByte(firstByte + offset)))

  val parsedMeta = Wire(new HeadProcessMeta)
  parsedMeta.src_ipv4 := packetUInt(26, 4)
  parsedMeta.dst_ipv4 := packetUInt(30, 4)
  parsedMeta.src_port := packetUInt(34, 2)
  parsedMeta.dst_port := packetUInt(36, 2)
  parsedMeta.op_code := packetByte(42)
  parsedMeta.qpn := packetUInt(47, 3)

  // Buffer metadata independently from the realigned payload. The first input
  // beat is accepted only when its metadata can also be retained.
  val metaQueue = Module(new Queue(new HeadProcessMeta, entries = 2))
  metaQueue.io.enq.valid := state === sHeader && io.rx_data_in.valid
  metaQueue.io.enq.bits := parsedMeta
  io.meta_out <> metaQueue.io.deq

  // One complete 64-byte header beat is discarded by the state machine. The
  // remaining six bytes are stripped from the rest of the packet here.
  val payloadShift = Module(new RSHIFT(residualHeaderBytes, config.axi_data_width))
  payloadShift.io.in.valid := state === sPayload && io.rx_data_in.valid
  payloadShift.io.in.bits := io.rx_data_in.bits
  io.reth_data_out <> payloadShift.io.out

  io.rx_data_in.ready := Mux(
    state === sHeader,
    metaQueue.io.enq.ready,
    payloadShift.io.in.ready
  )

  when(io.rx_data_in.fire) {
    when(state === sHeader) {
      when(!io.rx_data_in.bits.last.asBool) {
        state := sPayload
      }
    }.otherwise {
      when(io.rx_data_in.bits.last.asBool) {
        state := sHeader
      }
    }
  }
}
