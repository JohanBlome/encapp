#!/usr/local/bin/python3

"""
    Verify tests
"""
import argparse
import sys
import json
import os
import re
import shutil
import datetime
import pandas as pd
import numpy as np
import cv2 as cv
import encapp
import encapp_search


DEFAULT_TESTS = [
    'color_check_surfaces.json',
    'color_check_buffers.json',
]


def parse_schema(schema):
    match = re.search('android.generic.([0-9]*)', schema)
    if match:
        return int(match.group(1))
    return -1


def parse_bitrate(bitrate):
    if isinstance(bitrate, str):
        bitrate_num = -1
        if bitrate.find('k') > -1:
            bitrate_num = int(str(bitrate).replace('k', '000'))
        elif bitrate.find('M') > -1:
            bitrate_num = int(str(bitrate).replace('k', '000000'))
        return bitrate_num
    else:
        return bitrate


def check_pair_values(ref, data, ok_range):
    not_found = []
    wrong_value = []
    for key in ref.keys():
        found = False
        for ext in range(0, ok_range):
            if int(key) + ext in data:
                # verify value
                if ref[key] != data[int(key) + ext]:
                    wrong_value.append([int(key), int(ref[key]),
                                        int(data[int(key) + ext])])
                found = True
                break

        if not found:
            not_found.append(key)
    return not_found, wrong_value


def print_partial_result(header, partial_result):
    if partial_result is not None and len(partial_result) > 0:
        result_string = f'\n\n\n   ===  {header} ==='
        result_string += '\n' + partial_result
        result_string += '\n-----\n'
        return result_string
    return ''


COLOR_RANGE_UNKOWN = 0
COLOR_RANGE_FULL = 1
COLOR_RANGE_LIMITED = 2

COLOR_STANDARD_BT709 = 1
COLOR_STANDARD_BT601_PAL = 2
COLOR_STANDARD_BT601_NTSC = 4

COLOR_STANDARD_BT2020 = 6
COLOR_TRANSFER_SDR_VIDEO = 3  # SMTE170M

COLOR_RANGE = {
    COLOR_RANGE_UNKOWN: 'unknown',
    COLOR_RANGE_FULL: 'full',
    COLOR_RANGE_LIMITED: 'limited',
}
COLOR_STANDARD = {
    COLOR_STANDARD_BT709: 'bt709',
    COLOR_STANDARD_BT601_PAL: 'bt601_pal',
    COLOR_STANDARD_BT601_NTSC: 'bt601_ntsc',
}
COLOR_TRANSFER = {
    COLOR_TRANSFER_SDR_VIDEO: 'sdr',
}


def get_plane_name(plane):
    if plane == 0:
        return 'blue'
    elif plane == 1:
        return 'green'
    elif plane == 2:
        return 'red'


def color_val_props(list, label, source, img):
    planes = img.shape[2]
    data = []
    for plane in range(0, planes):
        list.append([source, label, get_plane_name(plane),
                     np.min(img[:, :, plane]),
                     np.max(img[:, :, plane]),
                     int(round(np.mean(img[:, :, plane])))])
    return data


def check_color_values(path):
    directory, mediafile = os.path.split(path)
    cmd = f"ffprobe {path} 2>&1 | grep 'Stream #0'"
    ret, std_out, std_err = encapp.run_cmd_silent(cmd)

    # reg_pix_format = '(?P<pix_fmt>[yuvj420p]{7,8}).*'
    # reg_color_settings = '\(*(?P<range>[a-z]*)[ ,]*'\
    #                      '(?P<color1>[a-z0-9]*)/*'\
    #                      '(?P<color2>[a-z0-9]*)/*'\
    #                      '(?P<color3>[a-z0-9]*)\)'
    # res = re.search(reg_pix_format, std_out)
    # pix_fmt = ''
    # color1 = ''
    # color2 = ''
    # color3 = ''
    # if res:
    #     pix_fmt = res.group('pix_fmt')
    #     res = re.search(reg_color_settings, res.group(0))
    #     if res:
    #         color1 = res.group('color1')
    #         color2 = res.group('color2')
    #         color3 = res.group('color3')

    single_pic_path = f'{path}_1st.png'
    cmd = (f"ffmpeg -y -i {path} -vf 'scale=in_range=full:out_range=full' "
           f"-vframes 1 {single_pic_path}")

    ret, std_out, std_err = encapp.run_cmd_silent(cmd)
    cap = cv.VideoCapture(single_pic_path)
    status, img = cap.read()

    w = img.shape[1]
    h = img.shape[0]

    section_width = int(w/8)
    data = []
    color_val_props(data, 'image', mediafile, img[0:h, 0:w, :])
    color_val_props(data, 'gray',  mediafile, img[0:h, 0:w, :])
    color_val_props(data, 'red',   mediafile, img[0:h, 0:section_width, :])
    color_val_props(data, 'green', mediafile,
                    img[0:h, section_width * 1:section_width * 2, :])
    color_val_props(data, 'blue',  mediafile,
                    img[0:h, section_width * 2:section_width * 3, :])
    color_val_props(data, '0',     mediafile,
                    img[0:h, section_width * 3:section_width * 4, :])
    color_val_props(data, '16',    mediafile,
                    img[0:h, section_width * 4:section_width * 5, :])
    color_val_props(data, '127',   mediafile,
                    img[0:h, section_width * 5:section_width * 6, :])
    color_val_props(data, '235',   mediafile,
                    img[0:h, section_width * 6:section_width * 7, :])
    color_val_props(data, '255',   mediafile,
                    img[0:h, section_width * 7:section_width * 8, :])

    labels = ['file', 'label', 'plane', 'min', 'max', 'mean']
    pdata = pd.DataFrame.from_records(data, columns=labels,
                                      coerce_float=True)
    return pdata


def check_media_file(filepath):
    result_str = f'\n----- Verifying {filepath} -----\n'
    data = check_color_values(filepath)
    result_str += '\nactual range:\n'
    result_str += '{:3d}   {:3d}\n'.format(
        0, data.loc[(data['label'] == '0') &
                    (data['plane'] == 'red')]['mean'].values[0])
    result_str += '{:3d}   {:3d}\n'.format(
        16, data.loc[(data['label'] == '16') &
                     (data['plane'] == 'red')]['mean'].values[0])
    result_str += '{:3d}   {:3d}\n'.format(
        127, data.loc[(data['label'] == '127') &
                      (data['plane'] == 'red')]['mean'].values[0])
    result_str += '{:3d}   {:3d}\n'.format(
        235, data.loc[(data['label'] == '235') &
                      (data['plane'] == 'red')]['mean'].values[0])
    result_str += '{:3d}   {:3d}\n'.format(
        255, data.loc[(data['label'] == '255') &
                      (data['plane'] == 'red')]['mean'].values[0])
    return result_str


def check_settings(resultpath):
    result_str = ''
    # ffprobe
    # Stream #0:0(eng): Video: h264 (Constrained Baseline) \
    #     (avc1 / 0x31637661), yuv420p(tv, smpte170m/bt470bg/bt709),
    # \(*([a-z]*)[ ,]*([a-z0-9]*)\/*([a-z0-9]*)\/*([a-z0-9]*)\),
    # ([yuvj420p]{7,8}).*
    for file in resultpath:
        with open(file) as resultfile:
            result = json.load(resultfile)
            directory, jsonfile = os.path.split(file)
            encoder_settings = result.get('settings')
            # testname = result.get('test')
            encoded = result.get('encodedfile')
            encoded_path = f'{directory}/{encoded}'

            '''
                "color-range": "1",
                "color-standard": "2",
                "color-transfer": "3"
            '''
            color_range = encoder_settings.get('color-range')
            # color_standard = encoder_settings.get('color-standard')
            # color_transfer = encoder_settings.get('color-transfer')
            if color_range is None:
                color_range = 0

            result_str += f'\n----- Verifying {jsonfile} -----\n'
            result_str += (f'Encoder set to {COLOR_RANGE[int(color_range)]} '
                           'color range')
            data = check_color_values(encoded_path)
            result_str += 'actual range:\n'
            result_str += '{:3d}   {:3d}\n'.format(
                0, data.loc[(data['label'] == '0') &
                            (data['plane'] == 'red')]['mean'].values[0])
            result_str += '{:3d}   {:3d}\n'.format(
                16, data.loc[(data['label'] == '16') &
                             (data['plane'] == 'red')]['mean'].values[0])
            result_str += '{:3d}   {:3d}\n'.format(
                127, data.loc[(data['label'] == '127') &
                              (data['plane'] == 'red')]['mean'].values[0])
            result_str += '{:3d}   {:3d}\n'.format(
                235, data.loc[(data['label'] == '235') &
                              (data['plane'] == 'red')]['mean'].values[0])
            result_str += '{:3d}   {:3d}\n'.format(
                255, data.loc[(data['label'] == '255') &
                              (data['plane'] == 'red')]['mean'].values[0])

    return result_str


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument('--check', default='')
    parser.add_argument('-d', '--dir', default='color_check')
    parser.add_argument('-c', '--codec', help='Override encoder', default='')
    parser.add_argument(
        '-i', '--input',
        help='Replace all test defined sources with input', default='')
    parser.add_argument(
        '-is', '--input_res', help='Override input file', default='')
    parser.add_argument(
        '-if', '--input_fps', help='Override input fps', default='')
    parser.add_argument(
        '-os', '--output_res', help='Override input file', default='')
    parser.add_argument(
        '-of', '--output_fps', help='Override input fps', default='')
    parser.add_argument('-t', '--test', nargs='+',)
    parser.add_argument('-r', '--result', nargs='+',)

    options = parser.parse_args(argv[1:])
    result_string = ''
    model = None
    serial = None

    if len(options.check) > 0:
        color_check = check_media_file(options.check)
        result_string = print_partial_result('Verify color settings',
                                             color_check)
        print(f'\nRESULTS\n{result_string}')
        exit(0)
    color_check = ''
    workdir = options.dir
    if options.result is not None:
        print('check results')
        results = []
        for file in options.result:
            results.append(file)
        color_check += check_settings(results)

    else:
        if os.path.exists(workdir):
            shutil.rmtree(workdir)

        os.mkdir(workdir)
        model, serial = encapp.get_device_info(options.serial)

        if options.test is not None:
            # check if list
            tests = options.test
        else:
            tests = DEFAULT_TESTS

        for test in tests:
            directory, _ = os.path.split(__file__)
            if options.test is None:
                test_path = directory + '/../tests/' + test
            else:
                test_path = test

            with open(test_path, 'r') as test_file:
                tests_json = json.load(test_file)
                if os.path.exists(encapp_search.INDEX_FILE_NAME):
                    os.remove(encapp_search.INDEX_FILE_NAME)

                args = []
                args.append(__file__)
                args.append('--codec')
                args.append(options.codec)
                args.append('--input')
                args.append(options.input)
                args.append('--input_res')
                args.append(options.input_res)
                args.append('--input_fps')
                args.append(options.input_fps)
                args.append('--output_res')
                args.append(options.output_res)
                args.append('--output_fps')
                args.append(options.output_fps)

                print(f'test: {test}, path = {test_path}')
                encapp_options = encapp.get_options(args)
                print(f'run test: {tests_json}')
                result = encapp.run_encode_tests(tests_json,
                                                 test_path,
                                                 model,
                                                 serial,
                                                 'encapp_verify',
                                                 workdir,
                                                 encapp_options)
                color_check += check_settings(result)
    result_string += print_partial_result('Verify color settings', color_check)
    print(f'\nRESULTS\n{result_string}')
    with open(f'{workdir}/RESULT.txt', 'w') as output:
        output.write(result_string)
        output.write('\n---------')
        extra = ''
        if model is not None and serial is not None:
            with open(f'{workdir}/dut.txt', 'w') as dut:
                now = datetime.datetime.now()
                dt_string = now.strftime('%Y-%m-%d_%H_%M')
                dut.write(f'\nTest performed: {dt_string}')
                dut.write(f"\nDUT: {model['product']}, serial: {serial}")

        if os.path.exists(f'{workdir}/dut.txt'):
            with open(f'{workdir}/dut.txt', 'r') as dut:
                extra = dut.read()
        output.write(f'\n{extra}')
        output.write('\n')


if __name__ == '__main__':
    main(sys.argv)
