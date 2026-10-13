package generator

import chisel3.stage.ChiselStage

object elaborate extends App {
  val targetDirectory = "Verilog"
  val config = GeneratorConfig()

  args.headOption.getOrElse("generator") match {
    case "generator" | "slicer" =>
      (new ChiselStage()).emitSystemVerilog(
        new slicer(config),
        Array("--target-dir", targetDirectory, "--full-stacktrace")
      )
    case "slicer-frontend" | "SlicerFrontend" | "slicer-arbiter-frontend" | "SlicerArbiterFrontend" =>
      (new ChiselStage()).emitSystemVerilog(
        new SlicerFrontend(config),
        Array("--target-dir", targetDirectory, "--full-stacktrace")
      )
    case target =>
      throw new IllegalArgumentException(s"Unknown generator target: $target")
  }
}
