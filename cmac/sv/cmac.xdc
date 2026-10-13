# ==========================================================
# U200 general-purpose SI570 system clock - 156.25 MHz
# ==========================================================

set_property PACKAGE_PIN AU19 [get_ports sys_100M_0_p]
set_property PACKAGE_PIN AV19 [get_ports sys_100M_0_n]

set_property IOSTANDARD LVDS [get_ports sys_100M_0_p]
set_property IOSTANDARD LVDS [get_ports sys_100M_0_n]

create_clock -period 6.400 \
    -name sys_clk_156M25 \
    [get_ports sys_100M_0_p]


# ==========================================================
# Status LED0
# ==========================================================

set_property PACKAGE_PIN BC21 [get_ports led]
set_property IOSTANDARD LVCMOS12 [get_ports led]

set_clock_groups -asynchronous \
    -group [get_clocks -include_generated_clocks sys_clk_156M25] \
    -group [get_clocks -include_generated_clocks cmac0_pin_gt_clk_p] \
    -group [get_clocks -include_generated_clocks cmac1_pin_gt_clk_p]

# Enable if you import HBM and CMAC at the same time

# set_false_path -from [get_clocks -of_objects [get_pins -hier -filter {NAME =~ */gtye4_channel_gen.gen_gtye4_channel_inst[*].GTYE4_CHANNEL_PRIM_INST/TXOUTCLK}]] -to [get_clocks -of_objects [get_pins -hier -filter {NAME =~ */mmcmGlbl/mmcm4_adv/CLKOUT0}] -filter {IS_GENERATED && MASTER_CLOCK == sys_100M_clock_0}]

# Enable if you import QDMA and CMAC at the same time

# set_false_path -from [get_clocks -of_objects [get_pins -hier -filter {NAME =~ */phy_clk_i/bufg_gt_userclk/O}]] -to [get_clocks -of_objects [get_pins -hier -filter {NAME =~ */gtye4_channel_gen.gen_gtye4_channel_inst[*].GTYE4_CHANNEL_PRIM_INST/TXOUTCLK}]]
# set_false_path -from [get_clocks -of_objects [get_pins -hier -filter {NAME =~ */gtye4_channel_gen.gen_gtye4_channel_inst[*].GTYE4_CHANNEL_PRIM_INST/TXOUTCLK}]] -to [get_clocks -of_objects [get_pins -hier -filter {NAME =~ */phy_clk_i/bufg_gt_userclk/O}]]
