package cmac

import chisel3._
import chisel3.util._
import common._
import common.storage._
import common.axi._
import common.ToZero

class CMACTop extends RawModule{
	val cmac0_pin		= IO(new CMACPin())
	val cmac1_pin		= IO(new CMACPin())
	val led 			= IO(Output(UInt(1.W)))
	val sys_100M_0_p	= IO(Input(Clock()))
  	val sys_100M_0_n	= IO(Input(Clock()))

	led := 0.U

	val mmcm = Module(new MMCME4_ADV_Wrapper(
		CLKFBOUT_MULT_F 		= 32,
		MMCM_DIVCLK_DIVIDE		= 5,
		MMCM_CLKOUT0_DIVIDE_F	= 4,
		MMCM_CLKOUT1_DIVIDE_F	= 10,
		
		MMCM_CLKIN1_PERIOD 		= 6.4
	))


	mmcm.io.CLKIN1	:= IBUFDS(sys_100M_0_p, sys_100M_0_n)
	mmcm.io.RST		:= 0.U

	val dbg_clk 	= BUFG(mmcm.io.CLKOUT1)
	dontTouch(dbg_clk)

	val user_clk = BUFG(mmcm.io.CLKOUT0)
	val user_rstn = mmcm.io.LOCKED

	val cmac0 = Module(new XCMAC(
		BOARD = "u200",
		PORT = 0,
		IP_CORE_NAME = "CMACBlackBox0"
	))

	val cmac1 = Module(new XCMAC(
		BOARD = "u200",
		PORT = 1,
		IP_CORE_NAME = "CMACBlackBox1"
	))

	cmac0.getTCL()
	cmac1.getTCL()

	cmac0_pin <> cmac0.io.pin
	cmac1_pin <> cmac1.io.pin
	
	cmac0.io.m_net_rx.ready := true.B
	cmac1.io.m_net_rx.ready := true.B


	// val rx_data0 = Wire(UInt(32.W))
	// rx_data0		:= cmac0.io.m_net_rx.bits.data(31,0)

  	// class ila_rx0(seq:Seq[Data]) extends BaseILA(seq)
  	// val mod_rx0 = Module(new ila_rx0(Seq(
	// 	cmac0.io.stat_rx_aligned,	
	// 	cmac0.io.m_net_rx.valid,
	//   	cmac0.io.m_net_rx.ready,
    // 	rx_data0,
    // 	cmac0.io.m_net_rx.bits.last
  	// )))
  	// mod_rx0.connect(user_clk)

	// val rx_data1 = Wire(UInt(32.W))
	// rx_data1		:= cmac1.io.m_net_rx.bits.data(31,0)

	// class ila_rx1(seq: Seq[Data]) extends BaseILA(seq)
	// val mod_rx1 = Module(new ila_rx1(Seq(
	// 	cmac1.io.stat_rx_aligned,
	// 	cmac1.io.m_net_rx.valid,
	// 	cmac1.io.m_net_rx.ready,
	// 	rx_data1,
	// 	cmac1.io.m_net_rx.bits.last
	// )))
	// mod_rx1.connect(user_clk)

	// class ila_tx0(seq:Seq[Data]) extends BaseILA(seq)	  
  	// val tx0 = Module(new ila_tx0(Seq(	
	// 	cmac0.io.s_net_tx.valid,
	//   	cmac0.io.s_net_tx.ready,
    // 	cmac0.io.s_net_tx.bits.last
  	// )))
  	// tx0.connect(user_clk)

	// class ila_tx1(seq:Seq[Data]) extends BaseILA(seq)	  
  	// val tx1 = Module(new ila_tx1(Seq(	
	// 	cmac1.io.s_net_tx.valid,
	//   	cmac1.io.s_net_tx.ready,
    // 	cmac1.io.s_net_tx.bits.last
  	// )))
  	// tx1.connect(user_clk)

  	val send0 = Wire(Bool())
	val send1 = Wire(Bool())
	
	class vio_net(seq:Seq[Data]) extends BaseVIO(seq)
  	val mod_vio = Module(new vio_net(Seq(
    	send0,send1
  	)))
  	mod_vio.connect(user_clk)

	// RX0 is driven by the packet sent from CMAC1 / QSFP1.  Observe the
	// user-side handshake before the clock-domain crossing and padding logic.
	class ila_cmac1_tx_user_debug(seq: Seq[Data]) extends BaseILA(seq)
	val mod_cmac1_tx_user_debug = Module(new ila_cmac1_tx_user_debug(Seq(
		send1,
		cmac1.io.s_net_tx.valid,
		cmac1.io.s_net_tx.ready,
		cmac1.io.s_net_tx.bits.last
	)))
	mod_cmac1_tx_user_debug.connect(user_clk)
	//RX1 is driven by the packet sent from CMAC0/QSFP0(sen0)
	class ila_cmac0_tx_user_debug(seq: Seq[Data]) extends BaseILA(seq)

	val mod_cmac0_tx_user_debug =
	Module(new ila_cmac0_tx_user_debug(Seq(
		send0,
		cmac0.io.s_net_tx.valid,
		cmac0.io.s_net_tx.ready,
		cmac0.io.s_net_tx.bits.last
	)))

	mod_cmac0_tx_user_debug.connect(user_clk)

	cmac0.io.sys_reset := !user_rstn
	cmac1.io.sys_reset := !user_rstn

	withClockAndReset(user_clk,!user_rstn){
		// val rst_delay_cnt = RegInit(0.U(32.W))
		// val cmac_sys_reset = RegInit(true.B)

		// when(rst_delay_cnt === 25000000.U){
		// 	rst_delay_cnt		:= rst_delay_cnt
		// }.otherwise{
		// 	rst_delay_cnt		:= rst_delay_cnt + 1.U
		// }

		// when(rst_delay_cnt === 25000000.U){
		// 	cmac_sys_reset		:= false.B
		// }.otherwise{
		// 	cmac_sys_reset		:= true.B
		// }


        // CMAC0 / QSFP0 TX
		val data_cnt0 = RegInit(0.U(16.W))
		val tx_valid0 = RegInit(false.B)
		val send0_d = RegNext(send0, false.B)

		when(cmac0.io.s_net_tx.fire){
			when(data_cnt0 === 20.U){
				data_cnt0	:= 0.U
			}.otherwise{
				data_cnt0	:= data_cnt0 + 1.U;
			}
		}

		when(!send0_d && send0) {
			tx_valid0 := true.B
		}.elsewhen(cmac0.io.s_net_tx.fire && data_cnt0 === 20.U) {
			tx_valid0 := false.B
		}

		cmac0.io.s_net_tx.valid     := tx_valid0
		cmac0.io.s_net_tx.bits.data := data_cnt0
		cmac0.io.s_net_tx.bits.keep := "hffffffffffffffff".U
		cmac0.io.s_net_tx.bits.last := data_cnt0 === 20.U

		// CMAC1 / QSFP1 TX
		val data_cnt1 = RegInit(0.U(16.W))
		val tx_valid1 = RegInit(false.B)

		val send1_d = RegNext(send1, false.B)

		when(cmac1.io.s_net_tx.fire) {
			when(data_cnt1 === 20.U) {
				data_cnt1 := 0.U
			}.otherwise {
				data_cnt1 := data_cnt1 + 1.U
			}
		}

		when(!send1_d && send1) {
			tx_valid1 := true.B
		}.elsewhen(cmac1.io.s_net_tx.fire && data_cnt1 === 20.U) {
			tx_valid1 := false.B
		}

		cmac1.io.s_net_tx.valid 	:= tx_valid1
		cmac1.io.s_net_tx.bits.data		:= data_cnt1
		cmac1.io.s_net_tx.bits.keep		:= "hffffffffffffffff".U
		cmac1.io.s_net_tx.bits.last		:= data_cnt1 === 20.U
	}	  


	

    cmac0.io.drp_clk         := dbg_clk
	cmac1.io.drp_clk         := dbg_clk
	cmac0.io.user_clk	    := user_clk
	cmac1.io.user_clk	    := user_clk
	cmac0.io.user_arstn	    := user_rstn
	cmac1.io.user_arstn	    := user_rstn

}
