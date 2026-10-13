`timescale 1ns / 1ps

module tb_SlicerArbiterFrontend;

localparam int MAX_WAIT_CYCLES = 20_000;

logic         clock = 1'b0;
logic         reset = 1'b1;
logic         io_start = 1'b0;
logic [15:0]  io_payload_len = 16'd64;
logic [7:0]   io_idle_cycle = 8'd0;

wire          io_s_axis_ready;
logic         io_s_axis_valid = 1'b0;
logic         io_s_axis_bits_last = 1'b0;
logic [511:0] io_s_axis_bits_data = '0;
logic [63:0]  io_s_axis_bits_keep = '0;

logic         io_meta_out_ready = 1'b1;
wire          io_meta_out_valid;
wire [31:0]   io_meta_out_bits_src_ipv4;
wire [31:0]   io_meta_out_bits_dst_ipv4;
wire [15:0]   io_meta_out_bits_src_port;
wire [15:0]   io_meta_out_bits_dst_port;
wire [7:0]    io_meta_out_bits_op_code;
wire [23:0]   io_meta_out_bits_qpn;

logic         io_m_axis_ready = 1'b1;
wire          io_m_axis_valid;
wire          io_m_axis_bits_last;
wire [511:0]  io_m_axis_bits_data;
wire [63:0]   io_m_axis_bits_keep;

integer payload_count = 0;
integer meta_count = 0;

function automatic [511:0] expected_payload_data;
    integer word_index;
    integer byte_index;
    reg [63:0] word_value;
    reg [511:0] result;
    begin
        result = '0;
        for (word_index = 0; word_index < 8; word_index = word_index + 1) begin
            word_value = 64'h0000_0000_cafe_babe + word_index;
            for (byte_index = 0; byte_index < 8; byte_index = byte_index + 1) begin
                result[(word_index * 64) + (byte_index * 8) +: 8] =
                    word_value[63 - (byte_index * 8) -: 8];
            end
        end
        expected_payload_data = result;
    end
endfunction

SlicerArbiterFrontend dut (
    .*
);

// 156.25 MHz
always #3.2 clock = ~clock;

always @(posedge clock) begin
    if (!reset && io_meta_out_valid && io_meta_out_ready) begin
        meta_count <= meta_count + 1;
        if (meta_count != 0)
            $fatal(1, "Unexpected extra metadata event");
        if ({
            io_meta_out_bits_src_ipv4,
            io_meta_out_bits_dst_ipv4,
            io_meta_out_bits_src_port,
            io_meta_out_bits_dst_port,
            io_meta_out_bits_op_code,
            io_meta_out_bits_qpn
        } !== {
            32'hc0a80101,
            32'hc0a80102,
            16'd4791,
            16'd4791,
            8'h0a,
            24'd1
        }) begin
            $fatal(1, "Metadata mismatch");
        end
    end
end

always @(posedge clock) begin
    if (!reset && io_m_axis_valid && io_m_axis_ready) begin
        payload_count <= payload_count + 1;
        if (payload_count != 0)
            $fatal(1, "Unexpected extra payload beat");
        if (!io_m_axis_bits_last)
            $fatal(1, "64-byte payload must complete in one beat");
        if (io_m_axis_bits_keep !== 64'hffff_ffff_ffff_ffff)
            $fatal(1, "Unexpected payload keep: %h", io_m_axis_bits_keep);
        if (io_m_axis_bits_data !== expected_payload_data())
            $fatal(1, "Payload mismatch: got=%h expected=%h",
                   io_m_axis_bits_data, expected_payload_data());
    end
end

initial begin : main_test
    integer wait_cycles;

    $timeformat(-9, 3, " ns", 12);
    repeat (20) @(posedge clock);
    reset = 1'b0;
    repeat (20) @(posedge clock);

    // A pulse starts one packet; holding start high would intentionally
    // request more packets and is a separate stress/backpressure scenario.
    @(negedge clock);
    io_start = 1'b1;
    @(negedge clock);
    io_start = 1'b0;

    wait_cycles = 0;
    while (payload_count < 1 || meta_count < 1) begin
        @(posedge clock);
        wait_cycles = wait_cycles + 1;
        if (wait_cycles >= MAX_WAIT_CYCLES)
            $fatal(1, "Timeout: payload=%0d metadata=%0d",
                   payload_count, meta_count);
    end

    repeat (20) @(posedge clock);
    if (payload_count != 1 || meta_count != 1)
        $fatal(1, "Unexpected counts: payload=%0d metadata=%0d",
               payload_count, meta_count);

    $display("[%0t] PASS: frontend payload and metadata checks completed", $time);
    $finish;
end

initial begin
    $dumpfile("tb_SlicerArbiterFrontend.vcd");
    $dumpvars(0, tb_SlicerArbiterFrontend);
end

endmodule
