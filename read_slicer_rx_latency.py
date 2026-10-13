#!/usr/bin/env python3
"""Configure slicer pacing and read the RXcore latency histogram via QDMA BAR4.

Use the PCI device's mmap-capable BAR resource path as --bar. Traffic start and
the packet-rate profiler's C2H buffer are managed by the existing host program.
Run ``clear`` before an experiment, ``configure`` to set the per-packet length
and gap, then ``dump`` after the experiment to stop traffic and save CSV.
"""

import argparse
import csv
import mmap
import os
import struct
import sys
import time

CONTROL_ENABLE = 150
CONTROL_BUCKET_ID = 151
CONTROL_CLEAR = 152
CONTROL_START = 224
CONTROL_PAYLOAD_LEN = 227
CONTROL_IDLE_CYCLE = 228
STATUS_BASE = 512
STATUS_BUCKET_VALUE = 340
STATUS_RESET_DONE = 341
STATUS_SENT = 342
STATUS_PROCESSED = 343
STATUS_OUTSTANDING = 344
BUCKET_COUNT = 1024
BUCKET_STRIDE_CYCLES = 32
USER_CLOCK_HZ = 200_000_000


class Registers:
    def __init__(self, resource):
        self.fd = os.open(resource, os.O_RDWR | os.O_SYNC)
        self.mem = mmap.mmap(
            self.fd, 4096, flags=mmap.MAP_SHARED,
            prot=mmap.PROT_READ | mmap.PROT_WRITE,
        )

    def close(self):
        self.mem.close()
        os.close(self.fd)

    def read(self, word):
        return struct.unpack_from("<I", self.mem, word * 4)[0]

    def write(self, word, value):
        struct.pack_into("<I", self.mem, word * 4, value)

    def status(self, word):
        return self.read(STATUS_BASE + word)


def wait_until(read_value, target, timeout, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if read_value() == target:
            return
        time.sleep(0.001)
    raise TimeoutError(f"timed out waiting for {description}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bar", required=True,
                        help="mmap-capable PCI BAR4 resource path")
    sub = parser.add_subparsers(dest="action", required=True)
    configure = sub.add_parser("configure")
    configure.add_argument("--payload-len", type=int)
    configure.add_argument("--idle-cycle", type=int)
    configure.add_argument("--enable", type=int, choices=(0, 1))
    sub.add_parser("clear")
    dump = sub.add_parser("dump")
    dump.add_argument("--csv", required=True)
    dump.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    if args.action == "configure":
        if args.payload_len is not None and not 0 <= args.payload_len <= 65479:
            parser.error("payload-len must fit the 16-bit IPv4 total length")
        if args.idle_cycle is not None and not 0 <= args.idle_cycle <= 255:
            parser.error("idle-cycle must be in 0..255")

    regs = Registers(args.bar)
    try:
        if args.action == "configure":
            if args.payload_len is not None:
                regs.write(CONTROL_PAYLOAD_LEN, args.payload_len)
            if args.idle_cycle is not None:
                regs.write(CONTROL_IDLE_CYCLE, args.idle_cycle)
            if args.enable is not None:
                regs.write(CONTROL_ENABLE, args.enable)
            print("payload_len={}, idle_cycle={}, enabled={}".format(
                regs.read(CONTROL_PAYLOAD_LEN),
                regs.read(CONTROL_IDLE_CYCLE),
                regs.read(CONTROL_ENABLE) & 1,
            ))

        elif args.action == "clear":
            # Stop and drain before resetting the histogram or timestamp IDs.
            regs.write(CONTROL_START, 0)
            wait_until(lambda: regs.status(STATUS_OUTSTANDING), 0, 30.0,
                       "all RXcore events to complete")
            regs.write(CONTROL_ENABLE, 0)
            regs.write(CONTROL_CLEAR, 0)
            regs.write(CONTROL_CLEAR, 1)
            time.sleep(0.001)
            regs.write(CONTROL_CLEAR, 0)
            wait_until(lambda: regs.status(STATUS_RESET_DONE) & 1, 1, 5.0,
                       "histogram RAM clear")
            print("histogram cleared")

        else:
            # Finish all generated packets before freezing and reading bins.
            regs.write(CONTROL_START, 0)
            wait_until(lambda: regs.status(STATUS_OUTSTANDING), 0,
                       args.timeout, "all RXcore events to complete")
            regs.write(CONTROL_ENABLE, 0)
            time.sleep(0.001)
            if not (regs.status(STATUS_RESET_DONE) & 1):
                raise RuntimeError("histogram reset is still in progress")
            sent = regs.status(STATUS_SENT)
            processed = regs.status(STATUS_PROCESSED)
            with open(args.csv, "w", newline="") as output:
                writer = csv.writer(output)
                writer.writerow(("bucket", "min_cycles", "min_ns", "count"))
                for bucket in range(BUCKET_COUNT):
                    regs.write(CONTROL_BUCKET_ID, bucket)
                    # The bucket RAM has two user-clock cycles of read latency;
                    # AXI-Lite plus this delay safely exceeds it.
                    time.sleep(0.0001)
                    writer.writerow((bucket, bucket * BUCKET_STRIDE_CYCLES,
                                     bucket * BUCKET_STRIDE_CYCLES *
                                     1_000_000_000 // USER_CLOCK_HZ,
                                     regs.status(STATUS_BUCKET_VALUE)))
            print(f"sent={sent} processed={processed} csv={args.csv}")
            print("bucket 1023 also contains all latencies >= 32736 cycles")
    finally:
        regs.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, TimeoutError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
