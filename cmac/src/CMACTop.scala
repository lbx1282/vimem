package cmac

import chisel3._
import chisel3.util._
import common._
import common.storage._
import common.axi._
import common.ToZero
import generator._

/** Selects complete AXI-Stream packets between the generator and an external
  * source. Input 0 (the generator) has priority at a packet boundary.
  */
class AXISPacketArbiter extends Module {
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

	when(io.out.fire) {
		when(locked) {
			when(io.out.bits.last.asBool) {
				locked := false.B
			}
		}.elsewhen(!io.out.bits.last.asBool) {
			locked := true.B
			lockedToGenerator := selectGenerator
		}
	}
}

/** Fixed-latency AXI-Stream delay line. Each beat (data/keep/last/valid) is
  * delayed by exactly `delayCycles` user-clock cycles to emulate network /
  * switch queuing delay. The whole line freezes on downstream backpressure so
  * no beat is dropped; upstream (the slicer) sees ready low and stalls too.
  */
class AxisCycleDelay(val delayCycles: Int) extends Module {
  require(delayCycles >= 1)
  val depth = 1 << log2Ceil(delayCycles + 16)
  val addrW = log2Ceil(depth)
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new AXIS(512)))
    val out = Decoupled(new AXIS(512))
  })

  val mem = SyncReadMem(depth, UInt((512 + 64 + 1).W))

  val advance = !(io.out.valid && !io.out.ready)
  val cycle = RegInit(0.U(addrW.W))
  when (advance) { cycle := cycle + 1.U }

  val validPipe = RegInit(VecInit(Seq.fill(delayCycles)(false.B)))
  when (advance) {
    validPipe(0) := io.in.valid
    for (i <- 1 until delayCycles) validPipe(i) := validPipe(i - 1)
  }
  io.out.valid := validPipe(delayCycles - 1)

  when (io.in.fire) {
    mem.write(cycle, Cat(io.in.bits.last, io.in.bits.keep, io.in.bits.data))
  }
  val rdData = mem.read(cycle - (delayCycles - 1).U(addrW.W))

  io.out.bits.data := rdData(511, 0)
  io.out.bits.keep := rdData(575, 512)
  io.out.bits.last := rdData(576)
  io.in.ready := advance
}

class CMACTop (
  ENABLE_DEBUG: Boolean = false,
  genConfig: GeneratorConfig = GeneratorConfig(),
  USE_EXTERNAL_CLOCK: Boolean = false,
  TX_DELAY_CYCLES: Int = 0
) extends RawModule{
	val led 			= IO(Output(UInt(1.W)))
	val sys_clk	        = IO(Input(Clock()))
	val cmac0_pin       = IO(new CMACPin())
	val cmac1_pin       = IO(new CMACPin())
	val s_net_tx        = IO(Flipped(Vec(2, Decoupled(new AXIS(512)))))
	val m_net_rx        = IO(Vec(2, Decoupled(new AXIS(512))))
	val axis_clk        = IO(Output(Clock()))
	val axis_reset      = IO(Output(Bool()))
	val cmac0_rx_aligned = IO(Output(Bool()))
	val cmac1_rx_aligned = IO(Output(Bool()))
	val payload_len      = IO(Input(Vec(2, UInt(16.W))))
	val idle_cycle       = IO(Input(Vec(2, UInt(8.W))))
	val external_user_clk = if (USE_EXTERNAL_CLOCK) Some(IO(Input(Clock()))) else None
	val external_user_rstn = if (USE_EXTERNAL_CLOCK) Some(IO(Input(Bool()))) else None
	val generator_qpn = if (USE_EXTERNAL_CLOCK) Some(IO(Input(Vec(2, UInt(24.W))))) else None
	val slicer0_tx_fire = IO(Output(Bool()))
	val slicer0_tx_last = IO(Output(Bool()))
	val slicer0_tx_qpn = IO(Output(UInt(24.W)))

	led := 0.U

	val mmcm = if (USE_EXTERNAL_CLOCK) None else Some(Module(new MMCME4_ADV_Wrapper(
		CLKFBOUT_MULT_F 		= 32,
		MMCM_DIVCLK_DIVIDE		= 5,
		MMCM_CLKOUT0_DIVIDE_F	= 5,
		MMCM_CLKOUT1_DIVIDE_F	= 10,
		
		MMCM_CLKIN1_PERIOD 		= 6.4
	)))

	mmcm.foreach { m =>
		m.io.CLKIN1 := sys_clk
		m.io.RST := 0.U
	}

	val user_clk = if (USE_EXTERNAL_CLOCK) external_user_clk.get
		else BUFG(mmcm.get.io.CLKOUT0)
	val user_rstn = if (USE_EXTERNAL_CLOCK) external_user_rstn.get
		else mmcm.get.io.LOCKED
	val dbg_clk = if (USE_EXTERNAL_CLOCK) user_clk
		else BUFG(mmcm.get.io.CLKOUT1)
	dontTouch(dbg_clk)

	axis_clk   := user_clk
	axis_reset := !user_rstn

	val cmac0 = Module(new XCMAC(
		BOARD = "u200",
		PORT = 0,
		IP_CORE_NAME = "CMACBlackBox0",
		ENABLE_DEBUG = ENABLE_DEBUG
	))

	val cmac1 = Module(new XCMAC(
		BOARD = "u200",
		PORT = 1,
		IP_CORE_NAME = "CMACBlackBox1",
		ENABLE_DEBUG = ENABLE_DEBUG
	))

	cmac0.getTCL()
	cmac1.getTCL()
	val generator_start = if (!ENABLE_DEBUG) { Some(IO(Input(Vec(2, Bool())))) } else { None }

	cmac0_pin.tx_p := cmac0.io.pin.tx_p
	cmac0_pin.tx_n := cmac0.io.pin.tx_n
	cmac0.io.pin.rx_p := cmac0_pin.rx_p
	cmac0.io.pin.rx_n := cmac0_pin.rx_n
	cmac0.io.pin.gt_clk_p := cmac0_pin.gt_clk_p
	cmac0.io.pin.gt_clk_n := cmac0_pin.gt_clk_n
	cmac0_rx_aligned := cmac0.io.stat_rx_aligned

	cmac1_pin.tx_p := cmac1.io.pin.tx_p
	cmac1_pin.tx_n := cmac1.io.pin.tx_n
	cmac1.io.pin.rx_p := cmac1_pin.rx_p
	cmac1.io.pin.rx_n := cmac1_pin.rx_n
	cmac1.io.pin.gt_clk_p := cmac1_pin.gt_clk_p
	cmac1.io.pin.gt_clk_n := cmac1_pin.gt_clk_n
	cmac1_rx_aligned := cmac1.io.stat_rx_aligned

	m_net_rx(0) <> cmac0.io.m_net_rx
	m_net_rx(1) <> cmac1.io.m_net_rx



  	val send0 = Wire(Bool())
	val send1 = Wire(Bool())

	cmac0.io.sys_reset := !user_rstn
	cmac1.io.sys_reset := !user_rstn

	if (ENABLE_DEBUG) {
	class vio_net(seq:Seq[Data]) extends BaseVIO(seq)
  	val mod_vio = Module(new vio_net(Seq(
    	send0,send1
  	)))
  	mod_vio.connect(user_clk)

	// Observe CMAC1 TX separately; the latency path uses CMAC1 RX.
	class ila_cmac1_tx_user_debug(seq: Seq[Data]) extends BaseILA(seq)
	val mod_cmac1_tx_user_debug = Module(new ila_cmac1_tx_user_debug(Seq(
		send1,
		cmac1.io.s_net_tx.valid,
		cmac1.io.s_net_tx.ready,
		cmac1.io.s_net_tx.bits.last
	)))
	mod_cmac1_tx_user_debug.connect(user_clk)
	// QSFP0 TX reaches QSFP1 RX through the physical board connection.
	class ila_cmac0_tx_user_debug(seq: Seq[Data]) extends BaseILA(seq)

	val mod_cmac0_tx_user_debug =
	Module(new ila_cmac0_tx_user_debug(Seq(
		send0,
		cmac0.io.s_net_tx.valid,
		cmac0.io.s_net_tx.ready,
		cmac0.io.s_net_tx.bits.last
	)))

	mod_cmac0_tx_user_debug.connect(user_clk)

	} else {
	send0 := generator_start.get(0)
	send1 := generator_start.get(1)
	}

	val slicer0 = withClockAndReset(user_clk, !user_rstn) {
		Module(new slicer(genConfig))
	}
	val slicer1 = withClockAndReset(user_clk, !user_rstn) {
		Module(new slicer(genConfig))
	}

	slicer0.io.start := send0
	slicer1.io.start := send1
	slicer0.io.payload_len := payload_len(0)
	slicer1.io.payload_len := payload_len(1)
	slicer0.io.idle_cycle := idle_cycle(0)
	slicer1.io.idle_cycle := idle_cycle(1)
	slicer0.io.qpn := (if (USE_EXTERNAL_CLOCK) generator_qpn.get(0)
	  else genConfig.qpn.U(24.W))
	slicer1.io.qpn := (if (USE_EXTERNAL_CLOCK) generator_qpn.get(1)
	  else genConfig.qpn.U(24.W))
	slicer0_tx_fire := slicer0.io.m_axis.fire
	slicer0_tx_last := slicer0.io.m_axis.bits.last.asBool
	slicer0_tx_qpn := Cat(
	  slicer0.io.m_axis.bits.data(383, 376),
	  slicer0.io.m_axis.bits.data(391, 384),
	  slicer0.io.m_axis.bits.data(399, 392)
	)


	val txArbiter0 = withClockAndReset(user_clk, !user_rstn) {
		Module(new AXISPacketArbiter)
	}
	val txArbiter1 = withClockAndReset(user_clk, !user_rstn) {
		Module(new AXISPacketArbiter)
	}

	if (TX_DELAY_CYCLES > 0) {
		val slicer0Delay = withClockAndReset(user_clk, !user_rstn) {
			Module(new AxisCycleDelay(TX_DELAY_CYCLES))
		}
		slicer0Delay.io.in <> slicer0.io.m_axis
		txArbiter0.io.in(0) <> slicer0Delay.io.out
	} else {
		txArbiter0.io.in(0) <> slicer0.io.m_axis
	}
	txArbiter0.io.in(1) <> s_net_tx(0)
	cmac0.io.s_net_tx <> txArbiter0.io.out  

	txArbiter1.io.in(0) <> slicer1.io.m_axis
	txArbiter1.io.in(1) <> s_net_tx(1)
	txArbiter1.io.out <> cmac1.io.s_net_tx


	

    cmac0.io.drp_clk         := dbg_clk
	cmac1.io.drp_clk         := dbg_clk
	cmac0.io.user_clk	    := user_clk
	cmac1.io.user_clk	    := user_clk
	cmac0.io.user_arstn	    := user_rstn
	cmac1.io.user_arstn	    := user_rstn

}
