package cmac

import chisel3.stage.ChiselStage
import generator._

object elaborate extends App {

    args(0) match {

        case "CMACTop" =>
            (new ChiselStage()).emitSystemVerilog(
                new CMACTop(),
                Array(
                    "--target-dir", "Verilog",
                    "--full-stacktrace"
                )
            )
    }
}
