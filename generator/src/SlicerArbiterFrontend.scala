package generator

import chisel3._
import chisel3.util._
import common.axi.AXIS

/** Arbitrates complete AXI-Stream packets.
  *
  * Input 0 has priority whenever a new packet is selected. Once a valid input
  * is selected, that selection is held through downstream backpressure and is
  * released only when the accepted beat has `last` asserted.
  */
private[generator] class SlicerPacketArbiter extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(2, Decoupled(new AXIS(512))))
    val out = Decoupled(new AXIS(512))
  })

  val locked = RegInit(false.B)
  val lockedToGenerator = RegInit(false.B)

  val selectGenerator = Mux(locked, lockedToGenerator, io.in(0).valid)

  io.out.valid := Mux(selectGenerator, io.in(0).valid, io.in(1).valid)
  io.out.bits := Mux(selectGenerator, io.in(0).bits, io.in(1).bits)

  io.in(0).ready := io.out.ready && selectGenerator
  io.in(1).ready := io.out.ready && !selectGenerator

  when(!locked && io.out.valid) {
    locked := true.B
    lockedToGenerator := selectGenerator
  }

  when(io.out.fire && io.out.bits.last.asBool) {
    locked := false.B
  }
}

/** Generator, packet arbiter, and header parser without the RISC-V Tile. */
class SlicerArbiterFrontend(
  genConfig: GeneratorConfig = GeneratorConfig()
) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val payload_len = Input(UInt(16.W))
    val idle_cycle = Input(UInt(8.W))
    val qpn = Input(UInt(24.W))

    val s_axis = Flipped(Decoupled(new AXIS(512)))
    val meta_out = Decoupled(new HeadProcessMeta)
    val m_axis = Decoupled(new AXIS(512))
  })

  val slicer0 = Module(new slicer(genConfig))
  val arbiter = Module(new SlicerPacketArbiter)
  val headProcess = Module(new HeadProcess(genConfig))

  slicer0.io.start := io.start
  slicer0.io.payload_len := io.payload_len
  slicer0.io.idle_cycle := io.idle_cycle
  slicer0.io.qpn := io.qpn

  arbiter.io.in(0) <> slicer0.io.m_axis
  arbiter.io.in(1) <> io.s_axis

  headProcess.io.rx_data_in <> arbiter.io.out
  io.meta_out <> headProcess.io.meta_out
  io.m_axis <> headProcess.io.reth_data_out
}
