package generator

import chisel3._
import chisel3.util._
import common.axi.AXIS

/** Header parser without a packet source or the RISC-V Tile.
  * The enclosing top connects its RX stream to s_axis. HeadProcess extracts
  * metadata for RX_Meta and removes the packet header from the data stream.
  */
class SlicerFrontend(
  genConfig: GeneratorConfig = GeneratorConfig()
) extends Module {
  val io = IO(new Bundle {
    val s_axis = Flipped(Decoupled(new AXIS(512)))
    val meta_out = Decoupled(new HeadProcessMeta)
    val m_axis = Decoupled(new AXIS(512))
  })

  val headProcess = Module(new HeadProcess(genConfig))

  headProcess.io.rx_data_in <> io.s_axis
  io.meta_out <> headProcess.io.meta_out
  io.m_axis <> headProcess.io.reth_data_out
}
