package generator

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum



object Util {
    def has_overflow(data: DecoupledIO[Data]): Bool = {
        val overflow = RegInit(false.B)
        when(data.valid && (!data.ready)) {
            overflow := true.B
        }.otherwise {
            overflow := overflow
        }
        overflow
    }

    def reverse(data: UInt): UInt = {
        val width = data.getWidth
        val res = WireInit(VecInit(Seq.fill(width)(0.U(1.W))))
        for (i <- 0 until width / 8) {
            for (j <- 0 until 8) {
                res(i * 8 + j) := data(width - (i * 8) + j - 8)
            }
        }
        res.asUInt()
    }
}

object CONFIG {
    def DATA_WIDTH = 512
    def MTU = 4096
    def MTU_WORD = MTU / 64
    def MAX_QPS = 1024
    def RDMA_DEFAULT_PORT = 4791
    def UDP_PROTOCOL = 17
    def IP_HEADER_LEN = 160
    def UDP_HEADER_LEN = 64
    def IBH_HEADER_LEN = 96
    def RETH_HEADER_LEN = 128
    def AETH_HEADER_LEN = 32
    def INIT_CREDIT = 800
    def RX_BUFFER_FULL = 4000
    def ACK_CREDIT = 1000
}

class IP_HEADER() extends Bundle {
    val dst_ipaddr = UInt(32.W)
    val src_ipaddr = UInt(32.W)
    val checksum = UInt(16.W)
    val protocol = UInt(8.W)
    val ttl = UInt(8.W)
    val fragment_offset = UInt(13.W)
    val flags = UInt(3.W)
    val idendification = UInt(16.W)
    val length = UInt(16.W)
    val ecn = UInt(2.W)
    val dscp = UInt(6.W)
    val version_IHL = UInt(8.W)
}

class UDP_HEADER() extends Bundle {
    val checksum = UInt(16.W)
    val length = UInt(16.W)
    val des_prot = UInt(16.W)
    val src_prot = UInt(16.W)
}

class IBH_HEADER() extends Bundle {
    val psn = UInt(24.W)
    val index_msb = UInt(7.W)
    val ack = UInt(1.W)
    val qpn = UInt(24.W)
    val index_lsb = UInt(8.W)
    val p_key = UInt(16.W)
    val res2 = UInt(8.W)
    val op_code = UInt(8.W)
}

class RETH_HEADER() extends Bundle {
    val length = UInt(32.W)
    val r_key = UInt(32.W)
    val vaddr = UInt(64.W)
}

class AETH_HEADER() extends Bundle {
    val msn = UInt(16.W)
    val iswr_ack = UInt(1.W)
    val isNAK = UInt(2.W)
    val credit = UInt(13.W)
}

object IB_OPCODE extends ChiselEnum {
    val reserve0 = Value(0x0.U)
    val RC_WRITE_FIRST = Value(0x06.U)
    val RC_WRITE_MIDDLE = Value(0x07.U)
    val RC_WRITE_LAST = Value(0x08.U)
    val RC_WRITE_LAST_WITH_IMD = Value(0x09.U)
    val RC_WRITE_ONLY = Value(0x0A.U)
    val RC_WRITE_ONLY_WIT_IMD = Value(0x0B.U)
    val RC_READ_REQUEST = Value(0x0C.U)
    val RC_READ_RESP_FIRST = Value(0x0D.U)
    val RC_READ_RESP_MIDDLE = Value(0x0E.U)
    val RC_READ_RESP_LAST = Value(0x0F.U)
    val RC_READ_RESP_ONLY = Value(0x10.U)
    val RC_ACK = Value(0x11.U)
    val RC_DIRECT_FIRST = Value(0x18.U)
    val RC_DIRECT_MIDDLE = Value(0x19.U)
    val RC_DIRECT_LAST = Value(0x1A.U)
    val RC_DIRECT_ONLY = Value(0x1B.U)
    val CNP = Value(0x81.U)
    val reserve = Value(0xFF.U)
}

object PKG_JUDGE {

    def RETH_PKG(opcode: IB_OPCODE.Type): Bool = {
        val result = Wire(new Bool())
        when((opcode === IB_OPCODE.RC_WRITE_FIRST) | (opcode === IB_OPCODE.RC_WRITE_ONLY) |
             (opcode === IB_OPCODE.RC_READ_REQUEST) | (opcode === IB_OPCODE.RC_DIRECT_FIRST) |
             (opcode === IB_OPCODE.RC_DIRECT_ONLY)) {
            result := true.B
        }.otherwise {
            result := false.B
        }
        result
    }

    def AETH_PKG(opcode: IB_OPCODE.Type): Bool = {
        val result = Wire(new Bool())
        when((opcode === IB_OPCODE.RC_READ_RESP_FIRST) | (opcode === IB_OPCODE.RC_READ_RESP_LAST) |
             (opcode === IB_OPCODE.RC_READ_RESP_ONLY) | (opcode === IB_OPCODE.RC_ACK)) {
            result := true.B
        }.otherwise {
            result := false.B
        }
        result
    }

    def INFER_PKG(opcode: IB_OPCODE.Type): Bool = {
        val result = Wire(new Bool())
        when((opcode === IB_OPCODE.RC_READ_RESP_FIRST) | (opcode === IB_OPCODE.RC_READ_RESP_LAST) |
             (opcode === IB_OPCODE.RC_READ_RESP_ONLY) | (opcode === IB_OPCODE.RC_READ_RESP_MIDDLE) |
             (opcode === IB_OPCODE.RC_WRITE_FIRST) | (opcode === IB_OPCODE.RC_WRITE_LAST) |
             (opcode === IB_OPCODE.RC_WRITE_ONLY) | (opcode === IB_OPCODE.RC_WRITE_MIDDLE) |
             (opcode === IB_OPCODE.RC_READ_REQUEST)) {
            result := true.B
        }.otherwise {
            result := false.B
        }
        result
    }
}

// =============================================================================
// Generator configuration.
//
// Holds the *fixed* RDMA header fields (MAC/IP/port/op-code/QPN/PSN/vaddr/R-key
// and the payload byte pattern). The payload LENGTH is intentionally NOT here:
// it is a runtime AXI-Stream-adjacent input of the slicer so it can be changed
// directly from the testbench / software without re-elaborating the design.
//
// `packetBytes(payloadSize)` is a pure-Scala "golden model" used to derive the
// expected bytes for the testbench; the synthesised slicer produces the same
// bytes at runtime from its `payload_len` input.
// =============================================================================

final case class GeneratorConfig(
  axi_data_width: Int = 512,
  align_width: Int = 64,
  ethernet_header_size: Int = 14,
  ipv4_header_size: Int = 20,
  udp_header_size: Int = 8,
  ibh_header_size: Int = 12,
  reth_header_size: Int = 16,
  dst_mac: BigInt = BigInt("020000000002", 16),
  src_mac: BigInt = BigInt("020000000001", 16),
  src_ipv4: BigInt = BigInt("c0a80101", 16),
  dst_ipv4: BigInt = BigInt("c0a80102", 16),
  src_port: Int = CONFIG.RDMA_DEFAULT_PORT,
  dst_port: Int = CONFIG.RDMA_DEFAULT_PORT,
  ipv4_ttl: Int = 64,
  ipv4_identification: Int = 0,
  ipv4_dont_fragment: Boolean = true,
  op_code: Int = 0x0a,                                   // RC_WRITE_ONLY
  qpn: Int = 1,                                          // 24-bit QPN
  psn: Int = 0,                                          // 24-bit PSN
  vaddr: BigInt = BigInt("1000", 16),                    // 64-bit remote vaddr
  r_key: BigInt = BigInt(0),                             // 32-bit R-key
  payload_init: BigInt = BigInt("cafebabe", 16),         // custom payload seed
  payload_step: BigInt = BigInt(1)                       // custom payload increment
) {
  private def fitsUnsigned(value: BigInt, width: Int): Boolean =
    value >= 0 && value < (BigInt(1) << width)

  private def bytesBE(value: BigInt, byteCount: Int): Vector[Int] =
    Vector.tabulate(byteCount) { index =>
      ((value >> (8 * (byteCount - index - 1))) & 0xff).toInt
    }



  require(ethernet_header_size == 14,
    "the fixed Ethernet II header must be 14 bytes")
  require(ipv4_header_size == 20,
    "the fixed IPv4 header must be 20 bytes (no options)")
  require(udp_header_size == 8, "the UDP header must be 8 bytes")
  require(ibh_header_size == 12, "the IBH header must be 12 bytes")
  require(reth_header_size == 16, "the RETH header must be 16 bytes")
  
  require(fitsUnsigned(dst_mac, 48) && fitsUnsigned(src_mac, 48),
    "MAC addresses must be unsigned 48-bit values")
  require(fitsUnsigned(src_ipv4, 32) && fitsUnsigned(dst_ipv4, 32),
    "IPv4 addresses must be unsigned 32-bit values")
  require(src_port >= 0 && src_port <= 0xffff,
    "src_port must be an unsigned 16-bit value")
  require(dst_port >= 0 && dst_port <= 0xffff,
    "dst_port must be an unsigned 16-bit value")
  require(ipv4_ttl >= 0 && ipv4_ttl <= 0xff,
    "ipv4_ttl must be an unsigned 8-bit value")
  require(ipv4_identification >= 0 && ipv4_identification <= 0xffff,
    "ipv4_identification must be an unsigned 16-bit value")
  require(op_code >= 0 && op_code <= 0xff, "op_code must be an unsigned 8-bit value")


  val axi_data_bytes: Int = axi_data_width / 8
  val align_bytes: Int = align_width / 8
  val keep_width: Int = axi_data_bytes
  val header_size: Int =
    ethernet_header_size + ipv4_header_size + udp_header_size +
      ibh_header_size + reth_header_size

  private val ethernetHeader: Vector[Int] =
    bytesBE(dst_mac, 6) ++ bytesBE(src_mac, 6) ++ Vector(0x08, 0x00)

  private val flagsAndFragmentOffset = if (ipv4_dont_fragment) 0x4000 else 0

  // Golden model: full Ethernet+IPv4+UDP+IBH+RETH+payload bytes for a payload size.
  // def packetBytes(payloadSize: Int): Vector[Int] = {
  //   require(payloadSize > 0 && payloadSize % axi_data_bytes == 0,
  //     "payloadSize must be positive and a multiple of 64 bytes")
  //   val udpLength = udp_header_size + ibh_header_size + reth_header_size + payloadSize
  //   val ipv4TotalLength = ipv4_header_size + udpLength
  //   ethernetHeader ++
  //     buildIpv4Header(ipv4TotalLength) ++
  //     buildUdpHeader(udpLength) ++
  //     buildIbhHeader() ++
  //     buildRethHeader(payloadSize) ++
  //     buildPayload(payloadSize)
  // }

  // Golden model: bytes -> AXI-Stream beats (data, keep, last).
  // def packetBeats(payloadSize: Int): Vector[(BigInt, BigInt, Boolean)] = {
  //   val bytes = packetBytes(payloadSize)
  //   bytes.grouped(axi_data_bytes).toVector.zipWithIndex.map { case (beatBytes, index) =>
  //     val data = beatBytes.zipWithIndex.foldLeft(BigInt(0)) {
  //       case (acc, (byte, lane)) => acc | (BigInt(byte) << (8 * lane))
  //     }
  //     val keep = (BigInt(1) << beatBytes.length) - 1
  //     val last = index == (bytes.length + axi_data_bytes - 1) / axi_data_bytes - 1
  //     (data, keep, last)
  //   }
  // }

  // ---------------------------------------------------------------------------
  // Constants used by the runtime slicer.
  // ---------------------------------------------------------------------------

  // 70-byte header with 0 in the payload-length-dependent byte positions:
  //   IPv4 total_length @16..17, IPv4 checksum @24..25, UDP length @38..39,
  //   RETH length @66..69
  val fixed_header_bytes: Vector[Int] = {
    val ip =
      Vector(0x45, 0x00, 0x00, 0x00,
        (ipv4_identification >> 8) & 0xff, ipv4_identification & 0xff,
        (flagsAndFragmentOffset >> 8) & 0xff, flagsAndFragmentOffset & 0xff,
        ipv4_ttl, 0x11, 0x00, 0x00) ++
        bytesBE(src_ipv4, 4) ++ bytesBE(dst_ipv4, 4)
    val udp = bytesBE(src_port, 2) ++ bytesBE(dst_port, 2) ++ Vector(0x00, 0x00, 0x00, 0x00)
    val ibh =
      Vector(op_code, 0x00) ++
        bytesBE(0xffff, 2) ++
        Vector(0x00) ++
        bytesBE(qpn, 3) ++
        Vector(0x00) ++
        bytesBE(psn, 3)
    val reth = bytesBE(vaddr, 8) ++ bytesBE(r_key, 4) ++ Vector(0x00, 0x00, 0x00, 0x00)
    ethernetHeader ++ ip ++ udp ++ ibh ++ reth
  }
  require(fixed_header_bytes.length == header_size,
    "fixed_header_bytes length must equal header_size")

  // Sum of the 8 constant 16-bit IPv4 header words (word1=total_length and
  // word5=checksum are excluded; they are supplied at runtime).
  val ipv4_checksum_const_sum: Int = {
    0x4500 +
      (ipv4_identification & 0xffff) +
      (flagsAndFragmentOffset & 0xffff) +
      (((ipv4_ttl & 0xff) << 8) | 0x11) +
      ((src_ipv4 >> 16).toInt & 0xffff) + (src_ipv4.toInt & 0xffff) +
      ((dst_ipv4 >> 16).toInt & 0xffff) + (dst_ipv4.toInt & 0xffff)
  }
}
