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
from datetime import datetime
import pandas as pd
import numpy as np
import encapp as ep
import encapp_search as es
from encapp import run_cmd


DEFAULT_TESTS = ['simple.qcif.json',
                 'bitrates.json',
                 'dynamic_bitrate.json',
                 'dynamic_framerate.json',
                 'idr.json',
                 'lt2.json',
                 'ltr.json',
                 'ltr-2ref.json']

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

def get_nal_data(videopath, codec):
    ending = ""
    if codec.find('avc') or codec.find('h264') or codec.find('264'):
        ending = '264'
    elif codec.find('hevc') or codec.find('h265') or codec.find('265'):
        ending = '265'
    if len(ending) > 0:
        filename = os.path.splitext(videopath)[0]
        print(f'filename = {filename}')
        print(f'videopath = {videopath}')

        if not os.path.exists(f'{filename}.{ending}.nal'):
            cmd = f'ffmpeg -i {videopath} -c copy -bsf:v h264_mp4toannexb {filename}.{ending}'
            run_cmd(cmd)

            if ending == '264':
                cmd = f'h264nal {filename}.{ending} > {filename}.{ending}.nal'
            else:
                cmd = f'h265nal {filename}.{ending} > {filename}.{ending}.nal'

            print(f'cmd = {cmd}')
            run_cmd(cmd)
        return f'{filename}.{ending}.nal'

    return ""

def check_pair_values(ref, data, ok_range):
    not_found = []
    wrong_value = []
    for key in ref.keys():
        found = False
        wrong = True
        for ext in range(0, ok_range):
            if int(key) + ext in data:
                # verify value
                if ref[key] != data[int(key) + ext]:
                    wrong_value.append([int(key), int(ref[key]), int(data[int(key) + ext])])
                found = True
                break

        if not found:
            not_found.append(key)
    return not_found, wrong_value


def check_long_term_ref(resultpath):
    result_string = ''

    '''
      "vendor.qti-ext-enc-ltr.mark-frame": {
          "10": "0",
          "20": "1",
          "150": "0"
        },
        "vendor.qti-ext-enc-ltr.use-frame": {
          "40": "0",
          "60": "1",
          "90": "0",
          "120": "1",
          "180": "0"
        }
    '''
    for file in resultpath:
        with open(file) as resultfile:
            result = json.load(resultfile)
            directory, __ = os.path.split(file)
            encoder_settings = result.get('settings')
            testname = result.get('test')
            bitrate = parse_bitrate(encoder_settings.get('bitrate'))
            runtime_settings = result.get('runtime_settings')

            mark_frame = None
            use_frame = None
            if runtime_settings is not None and len(runtime_settings) > 0:
                mark_frame = runtime_settings.get('vendor.qti-ext-enc-ltr.mark-frame')
                use_frame = runtime_settings.get('vendor.qti-ext-enc-ltr.use-frame')

            reg_long_term_id = "long_term_frame_idx { ([0-9]*) }"
            reg_long_pic_id = "long_term_pic_num { ([0-9]*) }"
            lt_mark = {}
            lt_use = {}
            if mark_frame is not None and use_frame is not None:
                nal_file = get_nal_data(f"{directory}/{result.get('encodedfile')}",
                                        encoder_settings.get('codec'))
                nal_data = ""
                frame = 0
                with open(nal_file) as nal:
                    line = "-1"
                    while len(line) > 0:
                        line = nal.readline()
                        if line.find('frame_num:') != -1:
                            if frame < 4:
                                print(f'frame: {frame} - {line}')
                            frame += 1
                            match = re.search(reg_long_term_id, line)
                            if match:
                                pid = match.group(1)
                                lt_mark[frame] = pid
                                continue
                            match = re.search(reg_long_pic_id, line)
                            if match:
                                pid = match.group(1)
                                lt_use[frame] = pid
                                continue

                result_string += f'\n\n----- {testname} -----'
                result_string += f'\n\nSource: {file}'
                # each mark frame will cause a use frame so merge the mark with the use
                for item in lt_mark:
                    use_frame[item] = lt_mark[item]

                ok_range = 2
                not_found, wrong_values = check_pair_values(mark_frame, lt_mark, ok_range)
                result_string += '\n(1) Verify long term reference mark'
                if len(not_found) == 0 and len(wrong_values) == 0:
                    result_string += f'\nMarked ltr frames correct (within ' \
                                     f'{ok_range} frames)'
                else:
                    if  len(not_found) > 0:
                        result_string += f'\nFollowing ltr frames not found ' \
                                         f'(within {ok_range}: {not_found})'
                    if  len(wrong_values) > 0:
                        result_string += f'\nFollowing ltr frames have wrong ' \
                                         f'value (within {ok_range}: {wrong_values})'
                        for item in wrong_values:
                            result_string += f'frame: {item} = {wrong_values[item]}'

                not_found, wrong_values = check_pair_values(use_frame, lt_use, 3)
                result_string += '\n(2) Verify long term reference use setting'
                if len(not_found) == 0 and len(wrong_values) == 0:
                    print(f'{not_found} - {{not_found}}')
                    result_string += f'Used ltr frames correct (within {ok_range})'
                else:
                    if  len(not_found) > 0:
                        result_string += f'\nFollowing ltr use frames not found'\
                                         f' (within {ok_range} frames):\n{not_found})'
                    if  len(wrong_values) > 0:
                        result_string += f'\nFollowing ltr use frames have wrong' \
                                         f' value (within {ok_range}):'
                        for item in wrong_values:
                            result_string += '\nframe {:4d} - ref: {:2d}, should' \
                                             ' have been {:2d}' \
                                             .format(item[0], item[2], item[1])


    return result_string

def check_temporal_layer(resultpath):
    result_string = ''
    # "ts-schema": "android.generic.2"
    status = []

    for file in resultpath:
        with open(file) as resultfile:
            result = json.load(resultfile)
            _, resultfilename = os.path.split(file)
            testname = result.get('test')
            encoder_settings = result.get('settings')
            schema = encoder_settings.get('ts-schema')
            if not isinstance(schema, type(None)) and len(schema) > 0:
                frames = result.get('frames')
                layer_count = parse_schema(schema)
                layer_size = []
                for index in range(0, layer_count):
                    layer = list(filter(lambda x: ((x['frame'] + index) % layer_count), frames))
                    accum = 0
                    for item in layer:
                        accum += item['size']

                    layer_size.append([index, accum])
                total_size = 0
                for size in layer_size:
                    total_size += size[1]

                result_string += f'\n\n----- {testname} -----'
                for size in layer_size:
                    ratio = size[1] / total_size
                    result_string += '\nlayer {:d}:{:3d}%, {:s}' \
                                     .format(size[0],
                                             int(round(ratio * 100, 0)),
                                             resultfilename)

    return result_string


def check_idr_placement(resultpath):
    result_string = ''
    status = []

    for file in resultpath:
        with open(file) as resultfile:
            result = json.load(resultfile)
            _, resultfilename = os.path.split(file)
            encoder_settings = result.get('settings')
            testname = result.get('test')
            frames = result.get('frames')
            iframes = list(filter(lambda x: (x['iframe'] == 1), frames))
            idr_ids = []
            for frame in iframes:
                idr_ids.append(frame['frame'])

            runtime_settings = result.get('runtime_settings')
            if runtime_settings is not None and len(runtime_settings) > 0:
                dynamic_sync = runtime_settings.get('request-sync')
                if dynamic_sync is not None:
                    passed = True
                    for item in dynamic_sync:
                        if int(item) not in idr_ids:
                            passed = False

                    status.append([testname, "Runtime sync request", passed,
                                   gop, resultfilename])


            # gop, either static gop or distance from last?
            gop = encoder_settings.get('gop')
            fps = encoder_settings.get('fps')
            frame_gop = gop * fps
            passed = True
            if frame_gop < len(frames):
                for frame in idr_ids:
                    if frame % frame_gop != 0:
                        passed = False
            #TODO: check for missing key frames

            status.append([testname, "Even gop", passed, gop, resultfilename])

    labels = ['test', 'subtest', 'passed', 'gop', 'file']
    data = pd.DataFrame.from_records(status, columns=labels, coerce_float=True)
    data = data.sort_values(by=['gop'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- {name} -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():
            result_string += '\n{:s} \"{:s}\", gop {:2d} sec, {:s}' \
                             .format({True:'passed', False: 'failed'}[row.passed],
                                     row.subtest, row.gop, row.file)

    return result_string


def check_mean_bitrate_deviation(resultpath):
    result_string = ''
    bitrate_error = []

    for file in resultpath:
        with open(file) as resultfile:
            result = json.load(resultfile)
            _, resultfilename = os.path.split(file)
            encoder_settings = result.get('settings')
            testname = result.get('test')
            bitrate = parse_bitrate(encoder_settings.get('bitrate'))
            fps = encoder_settings.get('fps')
            runtime_settings = result.get('runtime_settings')
            dynamic_video_bitrate = None
            if runtime_settings is not None and len(runtime_settings) > 0:
                dynamic_video_bitrate = runtime_settings.get('video-bitrate')

            if dynamic_video_bitrate is not None:
                frames = result.get('frames')
                previous_limit = 0
                dyn_data = []
                target_bitrate = bitrate
                limits = list(dynamic_video_bitrate.keys())
                limits.append(frames[-1]['frame'])

                for limit in limits:
                    filtered = list(filter(lambda x: (x['frame'] >=
                                                      int(previous_limit) and
                                                      x['frame'] < int(limit)),
                                           frames))
                    accum = 0
                    for item in filtered:
                        accum += item['size']
                    # Calc mean in bits per second
                    num = len(filtered)
                    if num > 0:
                        mean = (fps * 8 * accum/num)
                    else:
                        mean = 0
                    ratio = mean / target_bitrate
                    bitrate_error_perc = int((ratio - 1) * 100)
                    dyn_data.append([int(previous_limit), int(limit),
                                     int(target_bitrate), int(round(mean, 0)),
                                     int(round(bitrate_error_perc, 0))])
                    if limit in dynamic_video_bitrate:
                        target_bitrate = parse_bitrate(dynamic_video_bitrate[limit])
                    previous_limit = limit
                result_string += f'\n\n----- {testname}, runtime bitrate changes -----'
                result_string += f"\n Codec: {encoder_settings.get('codec')}, " \
                                 f"{encoder_settings.get('height')}p@{fps}fps"
                for item in dyn_data:
                    result_string += '\n{:3d}% error in {:4d}:{:4d} ({:4d}kbps)' \
                                     'for {:4d}kbps, {:s}' \
                                     .format(item[4], item[0], item[1],
                                             int(item[3]/1000), int(item[2]/1000),
                                             resultfilename)
            else:
                mean_bitrate = encoder_settings.get('meanbitrate')
                ratio = mean_bitrate / bitrate
                bitrate_error_perc = int((ratio - 1) * 100)
                bitrate_error.append([testname, bitrate_error_perc, int(bitrate),
                                      mean_bitrate, resultfilename])

    labels = ['test', 'error', 'bitrate', 'real_bitrate', 'file']
    data = pd.DataFrame.from_records(bitrate_error, columns=labels,
                                     coerce_float=True)
    data = data.sort_values(by=['bitrate'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- {name} -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():
            result_string += '\n{:3d} % error for {:4d}kbps ({:4d}kbps), {:s}' \
                              .format(row.error, int(row.bitrate/1000),
                                      int(row.real_bitrate/1000), row.file)

    return result_string


def print_partial_result(header, partial_result):
    if len(partial_result) > 0:
        result_string = f'\n\n\n   ===  {header} ==='
        result_string += '\n' + partial_result
        result_string += '\n-----\n'
        return result_string
    return ""


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument('-d', '--dir', default='encapp_verify')
    parser.add_argument('-i', '--input', help='Replace all test defined ' \
                                                    'sources with input', default='')
    parser.add_argument('-is', '--input_res', help='Override input file', default='')
    parser.add_argument('-if', '--input_fps', help='Override input fps', default='')
    parser.add_argument('-os', '--output_res', help='Override input file', default='')
    parser.add_argument('-of', '--output_fps', help='Override input fps', default='')
    parser.add_argument('-c', '--codec', help='Override encoder', default='')
    parser.add_argument('-t', '--test', nargs="+",)
    parser.add_argument('-r', '--result', nargs="+",)


    options = parser.parse_args(argv[1:])
    result_string = ""
    model = None
    serial = None

    bitrate_string = ""
    idr_string = ""
    temporal_string = ""
    ltr_string = ""
    workdir = options.dir
    if options.result is not None:
        results = []
        for file in options.result:
            results.append(file)
        bitrate_string += check_mean_bitrate_deviation(results)
        idr_string += check_idr_placement(results)
        temporal_string += check_temporal_layer(results)
        ltr_string += check_long_term_ref(results)
    else:
        if os.path.exists(workdir):
            shutil.rmtree(workdir)

        os.mkdir(workdir)
        model, serial = ep.get_device_info(options.serial)

        if options.test is not None:
            #check if list
            tests = options.test
        else:
            tests = DEFAULT_TESTS

        for test in tests:
            directory, _ = os.path.split(__file__)
            if options.test is None:
                test_path = directory + "/../tests/" + test
            else:
                test_path = test

            with open(test_path, 'r') as test_file:
                tests_json = json.load(test_file)
                if os.path.exists(es.INDEX_FILE_NAME):
                    os.remove(es.INDEX_FILE_NAME)

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
                encapp_options = ep.get_options(args)
                print(f'run test: {tests_json}')
                result = ep.run_encode_tests(tests_json,
                                             test_path,
                                             model,
                                             serial,
                                             "encapp_verify",
                                             workdir,
                                             encapp_options)

                bitrate_string += check_mean_bitrate_deviation(result)
                idr_string += check_idr_placement(result)
                temporal_string += check_temporal_layer(result)
                ltr_string += check_long_term_ref(result)

    result_string += print_partial_result('Verify bitrate accuracy', bitrate_string)
    result_string += print_partial_result('Verify idr accuracy', idr_string)
    result_string += print_partial_result('Verify check temporal layer accuracy', temporal_string)
    result_string += print_partial_result('Verify long term reference settings', ltr_string)

    print(f'\nRESULTS\n{result_string}')
    with open(f'{workdir}/RESULT.txt', "w") as output:
        output.write(result_string)
        output.write(f'\n---------')
        extra = ""
        if model is not None and serial is not None:
            with open(f'{workdir}/dut.txt', 'w') as dut:
                now = datetime.now()
                dt_string = now.strftime('%Y-%m-%d_%H_%M')
                dut.write(f"\nTest performed: {dt_string}")
                dut.write(f"\nDUT: {model['product']}, serial: {serial}")

        if os.path.exists(f'{workdir}/dut.txt'):
            with open(f'{workdir}/dut.txt', 'r') as dut:
                extra = dut.read()
        output.write(f'\n{extra}')
        output.write('\n')
if __name__ == '__main__':
    main(sys.argv)
