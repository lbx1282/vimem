def read_data_from_file(filename):
    byte_data = []

    with open(filename, 'r') as file:
        for line in file:
            line = line.strip()

            if not line:
                continue

            # 去掉右边的 ASCII 部分
            # 例如：
            # 00000000  17 61 ... 42  |.a......PB|
            if '|' in line:
                line = line.split('|')[0].strip()

            parts = line.split()

            # hexdump 最后一行可能只有地址，例如：
            # 000001f0
            if len(parts) <= 1:
                continue

            # parts[0] 是 byte 地址，后面才是数据
            for item in parts[1:]:
                # 只接受两个 hex 字符，也就是一个 byte
                if len(item) == 2:
                    try:
                        int(item, 16)
                        byte_data.append(item)
                    except ValueError:
                        pass

    return byte_data


def process_data(byte_data):
    processed_data = []

    word_addr = 0

    # 每4个byte组成一个32-bit word
    for i in range(0, len(byte_data), 4):

        group = byte_data[i:i + 4]

        # 最后一组不足4 byte时补0
        while len(group) < 4:
            group.append('00')

        # RISC-V little endian
        # 17 61 00 00 -> 00006117
        data_str = ''.join(reversed(group))

        address_str = format(word_addr, '08x')

        processed_data.append(
            f'@{address_str} {data_str}'
        )

        word_addr += 1

    return processed_data


def write_data_to_file(filename, processed_data):
    with open(filename, 'w') as file:
        for item in processed_data:
            file.write(item + '\n')


if __name__ == "__main__":

    input_file = "inst_tx.txt"
    output_file = "inst_tx.mem"

    data = read_data_from_file(input_file)
    processed_data = process_data(data)
    write_data_to_file(output_file, processed_data)

    print(f"转换完成: {input_file} -> {output_file}")
    print(f"读取 byte 数: {len(data)}")
    print(f"生成 32-bit word 数: {len(processed_data)}")