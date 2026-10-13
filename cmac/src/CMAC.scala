package cmac

import chisel3._
import chisel3.util._
import common._
import common.storage._
import common.axi._
import common.ToZero


class XCMAC (BOARD: String="u200", PORT: Int = 0, IP_CORE_NAME: String="CMACBlackBox", ENABLE_DEBUG: Boolean = false) extends RawModule{
    require (Set("u50", "u200") contains BOARD)
	
	def getTCL() = {
        val (core_select, gt_group, lane1, lane2, lane3, lane4) =
        (BOARD, PORT) match {

            case ("u200", 0) =>
                (
                    "CMACE4_X0Y8",
                    "X1Y48~X1Y51",
                    "X1Y48",
                    "X1Y49",
                    "X1Y50",
                    "X1Y51"
                )

            case ("u200", 1) =>
                (
                    "CMACE4_X0Y6",
                    "X1Y44~X1Y47",
                    "X1Y44",
                    "X1Y45",
                    "X1Y46",
                    "X1Y47"
                )

            case ("u50", _) =>
                (
                    "CMACE4_X0Y3",
                    "X0Y28~X0Y31",
                    "X0Y28",
                    "X0Y29",
                    "X0Y30",
                    "X0Y31"
                )

            case _ =>
                require(false, "Invalid board and port pair for XCMAC!")
                ("", "", "", "", "", "")
        }
        val board_inf = (BOARD, PORT) match {
            case ("u200", 0) => "qsfp0_4x"
            case ("u200", 1) => "qsfp1_4x"
            case ("u50", _) => "qsfp_4x"
            case default => require(false, "Invalid board and port pair for XCMAC!")
        }
        val diff_clk_inf = (BOARD, PORT) match {
            case ("u200", 0) => "qsfp0_156mhz"
            case ("u200", 1) => "qsfp1_156mhz"
            case ("u50", _) => "qsfp_161mhz"
            case default => require(false, "Invalid board and port pair for XCMAC!")
        }
        val ref_clk_freq = BOARD match {
            case "u200" => "156.25"
            case "u50" => "161.1328125"
        }
		val s1 = f"create_ip -name cmac_usplus -vendor xilinx.com -library ip -version 3.1 -module_name ${IP_CORE_NAME}\n"
		val s2 = f"set_property -dict [list CONFIG.CMAC_CAUI4_MODE {1} CONFIG.NUM_LANES {4x25} CONFIG.GT_REF_CLK_FREQ {${ref_clk_freq}} CONFIG.USER_INTERFACE {AXIS} CONFIG.TX_FLOW_CONTROL {0} CONFIG.RX_FLOW_CONTROL {0} CONFIG.CMAC_CORE_SELECT {${core_select}} CONFIG.GT_GROUP_SELECT {${gt_group}} CONFIG.LANE1_GT_LOC {${lane1}} CONFIG.LANE2_GT_LOC {${lane2}} CONFIG.LANE3_GT_LOC {${lane3}} CONFIG.LANE4_GT_LOC {${lane4}} CONFIG.LANE5_GT_LOC {NA} CONFIG.LANE6_GT_LOC {NA} CONFIG.LANE7_GT_LOC {NA} CONFIG.LANE8_GT_LOC {NA} CONFIG.LANE9_GT_LOC {NA} CONFIG.LANE10_GT_LOC {NA} CONFIG.RX_GT_BUFFER {1} CONFIG.GT_RX_BUFFER_BYPASS {0} CONFIG.ETHERNET_BOARD_INTERFACE {${board_inf}} CONFIG.DIFFCLK_BOARD_INTERFACE {${diff_clk_inf}} CONFIG.Component_Name {${IP_CORE_NAME}}] [get_ips ${IP_CORE_NAME}]\n"
		println(s1 + s2)
	}


	val io = IO(new Bundle{
		val pin		= new CMACPin

		val net_clk 	    = Output(Clock())
		val net_rstn	    = Output(Bool())
        val stat_rx_aligned = Output(Bool())

        val drp_clk         = Input(Clock())
		val user_clk	    = Input(Clock())
		val user_arstn	    = Input(Bool())
        val sys_reset       = Input(Bool())

		val s_net_tx	    = Flipped(Decoupled(new AXIS(512)))
		val m_net_rx	    = Decoupled(new AXIS(512))

	})


    val fifo_tx_data        = XConverter(new AXIS(512), io.user_clk, io.user_arstn, io.net_clk)
    val fifo_rx_data        = XConverter(new AXIS(512), io.net_clk, io.net_rstn, io.user_clk)

    val fifo_tx_pkg         = withClockAndReset(io.net_clk,!io.net_rstn){XPacketQueue(512,512)}

    val tx_padding          = withClockAndReset(io.user_clk,!io.user_arstn){Module(new Frame_Padding_512())}

    tx_padding.io.data_in           <> io.s_net_tx
    fifo_tx_data.io.in             	<> withClockAndReset(io.user_clk,!io.user_arstn){RegSlice(2)(tx_padding.io.data_out)}
    fifo_tx_pkg.io.in              	<> fifo_tx_data.io.out

	val tx_regdelay					= withClockAndReset(io.net_clk,!io.net_rstn){RegSlice(1)(fifo_tx_pkg.io.out)}

    io.m_net_rx                     <> fifo_rx_data.io.out    

    
    val cmac_inst = Module(new CMACBlackBox(IP_CORE_NAME=IP_CORE_NAME))
	
    val rx_rst                      = Wire(Bool())
    val tx_rst                      = Wire(Bool())

    val tx_clk                      = Wire(Clock())
    val rx_clk                      = Wire(Clock())

    io.net_clk                := tx_clk
    io.stat_rx_aligned        := cmac_inst.io.stat_rx_aligned.asBool

    withClockAndReset(tx_clk,(rx_rst | tx_rst)){
        val net_rstn = RegNext(!(rx_rst || tx_rst), false.B)
        io.net_rstn                     := net_rstn

        val sIDLE :: sGT_LOCK :: sWAIT_RX_ALIGNED :: sPKG_TRANS_INIT :: Nil = Enum(4)
        val rx_state                = RegInit(sIDLE)
        val ctl_rx_enable_r         = RegInit(0.U(1.W))
        val stat_rx_aligned_1d      = RegNext(cmac_inst.io.stat_rx_aligned)
 
        switch(rx_state){
            is(sIDLE){
                rx_state        := sGT_LOCK
            }
            is(sGT_LOCK){
                ctl_rx_enable_r     := 1.U
                rx_state            := sWAIT_RX_ALIGNED
            } 
            is(sWAIT_RX_ALIGNED){
                when(stat_rx_aligned_1d === 1.U){
                    rx_state        := sPKG_TRANS_INIT
                }
            } 
            is(sPKG_TRANS_INIT){
                when(stat_rx_aligned_1d === 0.U){
                    rx_state        := sIDLE
                }
            } 
        }
        val tx_state                = RegInit(sIDLE)
        val ctl_tx_enable_r         = RegInit(0.U(1.W))
        val ctl_tx_send_rfi_r       = RegInit(0.U(1.W))
        switch(tx_state){
            is(sIDLE){
                ctl_tx_enable_r     := 0.U
                ctl_tx_send_rfi_r   := 0.U
                tx_state        := sGT_LOCK
            }        
            is(sGT_LOCK){
                ctl_tx_send_rfi_r   := 1.U
                tx_state            := sWAIT_RX_ALIGNED
            } 
            is(sWAIT_RX_ALIGNED){
                when(stat_rx_aligned_1d === 1.U){
                    tx_state        := sPKG_TRANS_INIT
                }
            }
            is(sPKG_TRANS_INIT){
                ctl_tx_send_rfi_r   := 0.U
                ctl_tx_enable_r     := 1.U
                when(stat_rx_aligned_1d === 0.U){
                    tx_state        := sIDLE
                }
            }
        }
        cmac_inst.io.ctl_rx_enable       := ctl_rx_enable_r
        cmac_inst.io.ctl_tx_enable       := ctl_tx_enable_r
        cmac_inst.io.ctl_tx_send_rfi     := ctl_tx_send_rfi_r

        //---ILA---
        if(PORT == 0 && ENABLE_DEBUG){
            // class ila_cmac0_rx_debug(seq: Seq[Data]) extends BaseILA(seq)
            // val mod_cmac0_rx_debug = Module(new ila_cmac0_rx_debug(Seq(
            //     rx_state,
            //     ctl_rx_enable_r,
            //     stat_rx_aligned_1d,

            //     cmac_inst.io.usr_rx_reset,
            //     cmac_inst.io.stat_rx_aligned,

            //     cmac_inst.io.rx_axis_tvalid,
            //     cmac_inst.io.rx_axis_tlast,
            //     cmac_inst.io.rx_axis_tkeep,
            //     cmac_inst.io.rx_axis_tdata
            // )))
            // mod_cmac0_rx_debug.connect(tx_clk)
            class ila_cmac0_tx_axis_debug(seq: Seq[Data]) extends BaseILA(seq)
            val mod_cmac0_tx_axis_debug = Module(new ila_cmac0_tx_axis_debug(Seq(
                ctl_tx_enable_r,
                cmac_inst.io.tx_axis_tvalid,
                cmac_inst.io.tx_axis_tready,
                cmac_inst.io.tx_axis_tlast,
                cmac_inst.io.tx_axis_tkeep,
                cmac_inst.io.tx_axis_tdata
            )))
            mod_cmac0_tx_axis_debug.connect(tx_clk)
        }

		// CMAC1/QSFP1 TX feeds CMAC0/QSFP0 RX through the external cable.
		// Generate this monitor for PORT 1 only so its module/IP name is unique.
		if(PORT == 1 && ENABLE_DEBUG){
			// class ila_cmac1_tx_axis_debug(seq: Seq[Data]) extends BaseILA(seq)
			// val mod_cmac1_tx_axis_debug = Module(new ila_cmac1_tx_axis_debug(Seq(
			// 	ctl_tx_enable_r,
			// 	cmac_inst.io.tx_axis_tvalid,
			// 	cmac_inst.io.tx_axis_tready,
			// 	cmac_inst.io.tx_axis_tlast,
			// 	cmac_inst.io.tx_axis_tkeep,
            //     cmac_inst.io.tx_axis_tdata
			// )))
            // mod_cmac1_tx_axis_debug.connect(tx_clk)
            class ila_cmac1_rx_debug(seq: Seq[Data]) extends BaseILA(seq)
            val mod_cmac1_rx_debug = Module(new ila_cmac1_rx_debug(Seq(
                rx_state,
                ctl_rx_enable_r,
                stat_rx_aligned_1d,
                cmac_inst.io.usr_rx_reset,
                cmac_inst.io.stat_rx_aligned,
                cmac_inst.io.rx_axis_tvalid,
                cmac_inst.io.rx_axis_tlast,
                cmac_inst.io.rx_axis_tkeep,
                cmac_inst.io.rx_axis_tdata
            )))
            mod_cmac1_rx_debug.connect(tx_clk)
		}
    } 





    

	
	cmac_inst.io.sys_reset 				<> io.sys_reset
	cmac_inst.io.gt_ref_clk_p 			<> io.pin.gt_clk_p
	cmac_inst.io.gt_ref_clk_n 			<> io.pin.gt_clk_n
    cmac_inst.io.init_clk               <> io.drp_clk
	cmac_inst.io.gt_txp_out				<> io.pin.tx_p
	cmac_inst.io.gt_txn_out				<> io.pin.tx_n
	cmac_inst.io.gt_rxp_in				<> io.pin.rx_p
	cmac_inst.io.gt_rxn_in				<> io.pin.rx_n

        //user clks
    cmac_inst.io.gt_txusrclk2           <> tx_clk
    cmac_inst.io.usr_rx_reset           <> rx_rst
    cmac_inst.io.usr_tx_reset           <> tx_rst
    cmac_inst.io.gt_rxusrclk2           <> rx_clk
    cmac_inst.io.rx_clk                 <> cmac_inst.io.gt_txusrclk2


    cmac_inst.io.gt_loopback_in              <> 0.U
    cmac_inst.io.gtwiz_reset_tx_datapath     <> 0.U
    cmac_inst.io.gtwiz_reset_rx_datapath     <> 0.U

        //data stream
    cmac_inst.io.rx_axis_tvalid         <> fifo_rx_data.io.in.valid
    cmac_inst.io.rx_axis_tdata          <> fifo_rx_data.io.in.bits.data
    cmac_inst.io.rx_axis_tlast          <> fifo_rx_data.io.in.bits.last
    cmac_inst.io.rx_axis_tkeep          <> fifo_rx_data.io.in.bits.keep

    cmac_inst.io.tx_axis_tvalid              <> tx_regdelay.valid
    cmac_inst.io.tx_axis_tready              <> tx_regdelay.ready
    cmac_inst.io.tx_axis_tdata               <> tx_regdelay.bits.data
    cmac_inst.io.tx_axis_tlast               <> tx_regdelay.bits.last
    cmac_inst.io.tx_axis_tkeep               <> tx_regdelay.bits.keep
    cmac_inst.io.tx_axis_tuser               <> 0.U
        //ctrl interface
    
    cmac_inst.io.ctl_rx_force_resync         := 0.U
    cmac_inst.io.ctl_rx_test_pattern         := 0.U
    cmac_inst.io.core_rx_reset               := 0.U
    cmac_inst.io.ctl_tx_send_idle            := 0.U
    cmac_inst.io.ctl_tx_send_lfi             := 0.U
    cmac_inst.io.ctl_tx_test_pattern         := 0.U
    cmac_inst.io.core_tx_reset               := 0.U
    // cmac_inst.io.tx_ovfout                   := Output(UInt(1.W))
    // cmac_inst.io.tx_unfout                   := Output(UInt(1.W))
    cmac_inst.io.tx_preamblein               := 0.U

    //state interface

    //drp interface
    cmac_inst.io.core_drp_reset              := 0.U
    cmac_inst.io.drp_clk                     := 0.U
    cmac_inst.io.drp_addr                    := 0.U
    cmac_inst.io.drp_di                      := 0.U
    cmac_inst.io.drp_en                      := 0.U
    // cmac_inst.io.drp_do                      := Output(UInt(16.W))
    // cmac_inst.io.drp_rdy                     := Output(UInt(1.W))
    cmac_inst.io.drp_we                      := 0.U


}


class Frame_Padding_512 extends Module{
	val io = IO(new Bundle{
		val data_in = Flipped(Decoupled(new AXIS(512)))
		val data_out = Decoupled(new AXIS(512))
	})

	val sIDLE :: sREAD_DATA :: Nil 	= Enum(2)
	val state                   	= RegInit(sIDLE)


	io.data_in.ready 			:= io.data_out.ready

	io.data_out.valid 				:= 0.U
	io.data_out.bits				:= 0.U.asTypeOf(io.data_out.bits)


	switch(state){
		is(sIDLE){
			when(io.data_in.fire){
				io.data_out.valid		:= 1.U
				io.data_out.bits 		:= io.data_in.bits
                io.data_out.bits.keep   := "hffffffffffffffff".U
                when(io.data_in.bits.last === 1.U){
                    state               	:= sIDLE
                }.otherwise{
                    state               	:= sREAD_DATA
                }
			}
		}
		is(sREAD_DATA){
			when(io.data_in.fire){
				io.data_out.valid		:= 1.U
				io.data_out.bits 		:= io.data_in.bits
				when(io.data_in.bits.last === 1.U){
					state					:= sIDLE
				}
			}
		}
	}
}
