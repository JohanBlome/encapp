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
from encapp import convert_to_bps
from google.protobuf import text_format
import proto.tests_pb2 as proto

DEFAULT_TESTS = ['bitrate_buffer.pbtxt',
                 'bitrate_surface.pbtxt',
                 'dynamic_bitrate.pbtxt',
                 'dynamic_framerate.pbtxt',
                 'dynamic_idr.pbtxt',
                 'lt2.pbtxt',
                 'ltr-2ref.pbtxt']


def parse_schema(schema):
    match = re.search('android.generic.([0-9]*)', schema)
    if match:
        return int(match.group(1))
    return -1




def get_nal_data(videopath, codec):
    ending = ""
    if codec.find('avc') or codec.find('h264') or codec.find('264'):
        ending = '264'
    elif codec.find('hevc') or codec.find('h265') or codec.find('265'):
        ending = '265'
    if len(ending) > 0:
        filename = os.path.splitext(videopath)[0]
        if not os.path.exists(f'{filename}.{ending}.nal'):
            if ending == '264':
                cmd = (f'ffmpeg -i {videopath} -c copy -bsf:v h264_mp4toannexb '
                       f'{filename}.{ending}')
                run_cmd(cmd, True)
                cmd = f'h264nal {filename}.{ending} > {filename}.{ending}.nal'
            else:
                cmd = (f'ffmpeg -i {videopath} -c copy -bsf:v hevc_mp4toannexb '
                       f'{filename}.{ending}')
                run_cmd(cmd, True)
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

# TODO: fix ltr
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
            # bitrate = convert_to_bps(encoder_settings.get('bitrate'))
            runtime_settings = result.get('runtime_settings')

            mark_frame = None
            use_frame = None

            test_def = result.get('testdefinition')
            test = text_format.Parse(test_def, proto.Test());

            dynamics = parse_dynamic_settings(test.runtime)['params']

            if dynamics is not None and len(dynamics) > 0:
                mark_frame = dynamics['vendor.qti-ext-enc-ltr.mark-frame']
                use_frame = dynamics['vendor.qti-ext-enc-ltr.use-frame']
            reg_long_term_id = "long_term_frame_idx { ([0-9]*) }"
            reg_long_pic_id = "long_term_pic_num { ([0-9]*) }"
            lt_mark = {}
            lt_use = {}
            if mark_frame is not None and use_frame is not None:
                nal_file = get_nal_data(f'{directory}/'
                                        f"{result.get('encodedfile')}",
                                        encoder_settings.get('codec'))
                frame = 0
                with open(nal_file) as nal:
                    line = "-1"
                    while len(line) > 0:
                        line = nal.readline()
                        if line.find('frame_num:') != -1:
                            #if frame < 4:
                            #    print(f'frame: {frame} - {line}')
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

                print(f'Found ltr mark cases')
                print(f'{lt_mark}')
                print(f'Found ltr use cases')
                print(f'{lt_use}')
                result_string += f'\n\n----- {testname} -----'
                result_string += f'\n\nSource: {file}'
                # each mark frame will cause a use frame so merge the mark
                # with the use
                for item in lt_mark:
                    use_frame[item] = lt_mark[item]

                ok_range = 2
                not_found, wrong_values = check_pair_values(
                    mark_frame, lt_mark, ok_range)
                result_string += '\n(1) Verify long term reference mark'
                if len(not_found) == 0 and len(wrong_values) == 0:
                    result_string += ('\nMarked ltr frames correct (within '
                                      f'{ok_range} frames)')
                else:
                    if len(not_found) > 0:
                        result_string += ('\nFollowing ltr frames not found '
                                          f'(within {ok_range}: {not_found})')
                    if len(wrong_values) > 0:
                        result_string += ('\nFollowing ltr frames have wrong '
                                          f'value (within {ok_range}: '
                                          f'{wrong_values})')
                        for item in wrong_values:
                            result_string += (f'frame: {item} = '
                                              f'{wrong_values[item]}')

                not_found, wrong_values = check_pair_values(
                    use_frame, lt_use, 3)
                result_string += '\n(2) Verify long term reference use setting'
                if len(not_found) == 0 and len(wrong_values) == 0:
                    print(f'{not_found} - {{not_found}}')
                    result_string += ('Used ltr frames correct (within '
                                      f'{ok_range})')
                else:
                    if len(not_found) > 0:
                        result_string += ('\nFollowing ltr use frames not '
                                          f'found (within {ok_range} frames):'
                                          f'\n{not_found})')
                    if len(wrong_values) > 0:
                        result_string += ('\nFollowing ltr use frames have '
                                          f'wrong value (within {ok_range}):')
                        for item in wrong_values:
                            result_string += ('\nframe {:4d} - ref: {:2d}, '
                                              'should have been {:2d}'
                                              .format(item[0], item[2],
                                                      item[1]))

                # What was found
                result_string += f'\n\nMarked in media:'
                for val in lt_mark:
                    result_string += ('\nframe {:4d} - id: {:d}'
                                          .format(val, int(lt_mark[val])))
                result_string += f'\nUsed in media:'
                for val in lt_use:
                    result_string += ('\nframe {:4d} - id: {:d}'
                                          .format(val, int(lt_use[val])))

    return result_string

#TODO: fix
def check_temporal_layer(resultpath):
    result_string = ''
    # "ts-schema": "android.generic.2"

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
                    layer = list(filter(lambda x: ((x['frame'] + index) %
                                                   layer_count), frames))
                    accum = 0
                    for item in layer:
                        accum += item['size']

                    layer_size.append([index, accum])
                total_size = 0
                for size in layer_size:
                    total_size += size[1]

                result_string += f'\n\n----- {testname} -----'
                for size in layer_size:
                    if total_size > 0:
                        ratio = size[1] / total_size
                        result_string += ('\nlayer {:d}:{:3d}%, {:s}'
                                          .format(size[0],
                                                  int(round(ratio * 100, 0)),
                                                  resultfilename))

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
            # gop, either static gop or distance from last?
            gop = encoder_settings.get('gop')
            print(f'Encoder settings: {encoder_settings}')
            if gop <= 0:
                print(f'gop is missing')
                gop = 1
            fps = encoder_settings.get('fps')
            if fps <= 0:
                print(f'fps is missing')
                fps = 30
            for frame in iframes:
                idr_ids.append(frame['frame'])


            test_def = result.get('testdefinition')
            test = text_format.Parse(test_def, proto.Test());

            dynamic_sync = parse_dynamic_settings(test.runtime)['syncs']
            if dynamic_sync is not None:
                passed = True
                for item in dynamic_sync:
                    if int(item) not in idr_ids:
                        passed = False

                    status.append([testname, "Runtime sync request", passed,
                                   item, resultfilename])

            frame_gop = gop * fps
            print(f'fps = {fps} gop = {gop} frame gop = {frame_gop}')

            passed = True
            if frame_gop < len(frames):
                for frame in idr_ids:
                    if frame % frame_gop != 0:
                        passed = False
            # TODO: check for missing key frames
            status.append([testname, "Even gop", passed, gop, resultfilename])

    labels = ['test', 'subtest', 'passed', 'gop', 'file']
    data = pd.DataFrame.from_records(status, columns=labels, coerce_float=True)
    data = data.sort_values(by=['gop'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- {name} -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():
            result_string += ('\n{:s} \"{:s}\" at {:2d} frames, {:s}'
                              .format({True: 'passed', False: 'failed'}
                                      [row.passed], row.subtest, row.gop,
                                      row.file))

    return result_string


def parse_dynamic_settings(settings):
    params = {}
    bitrates = {}
    framerates = {}
    syncs = []

    for param in settings.parameter:
        # TODO: fix this
        #print(f'{param}')
        if param.key in params:
            serie = params[param.key]
        else:
            serie = {}
            params[param.key] = serie

        if param.type == proto.DataValueType.Value('intType'):
            serie[param.framenum] = int(param.value)
        if param.type == proto.DataValueType.Value('floatType'):
            serie[param.framenum] = float(param.value)
        if param.type == proto.DataValueType.Value('longType'):
            serie[param.framenum] = param.value
        else:
            serie[param.framenum] = param.value
    for param in settings.video_bitrate:
        bitrates[param.framenum] = convert_to_bps(param.bitrate)
    for param in settings.dynamic_framerate:
        framerates[param.framenum] = param.framerate
    for param in settings.request_sync:
        syncs.append(param)

    runtime_data = {
        'params': params,
        'bitrates': bitrates,
        'framerates': framerates,
        'syncs': syncs,
    }
    return runtime_data


ERROR_LIMIT = 5

def check_mean_bitrate_deviation(resultpath):
    result_string = ''
    bitrate_error = []

    for file in resultpath:
        with open(file) as resultfile:
            result = json.load(resultfile)
            _, resultfilename = os.path.split(file)
            encoder_settings = result.get('settings')
            codec = encoder_settings.get('codec')
            testname = result.get('test')
            bitrate = convert_to_bps(encoder_settings.get('bitrate'))
            fps = encoder_settings.get('fps')

            test_def = result.get('testdefinition')
            test = text_format.Parse(test_def, proto.Test());
            parse_dynamic_settings(test.runtime)
            dynamic_video_bitrate = parse_dynamic_settings(test.runtime)['bitrates']

            if dynamic_video_bitrate is not None and len(dynamic_video_bitrate) > 0:
                frames = result.get('frames')
                previous_limit = 0
                dyn_data = []
                target_bitrate = bitrate
                limits = list(dynamic_video_bitrate.keys())
                limits.append(frames[-1]['frame'])
                status = "passed"
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
                    if abs(bitrate_error_perc) > ERROR_LIMIT:
                        status = "failed"
                    dyn_data.append([int(previous_limit), int(limit),
                                     int(target_bitrate), int(round(mean, 0)),
                                     int(round(bitrate_error_perc, 0))])
                    if limit in dynamic_video_bitrate:
                        target_bitrate = convert_to_bps(
                            dynamic_video_bitrate[limit])
                    previous_limit = limit
                result_string += (f'\n\n----- {testname}, runtime bitrate '
                                  'changes -----')

                result_string += (f"\n{status} \"Dynamic bitrate\", ")
                result_string += (f" codec: {encoder_settings.get('codec')}"
                                  f", {encoder_settings.get('height')}"
                                  f"p@{fps}fps"
                                  f", {resultfilename}")
                for item in dyn_data:
                    result_string += ('\n      {:3d}% error in {:4d}:{:4d} '
                                      '({:4d}kbps) for {:4d}kbps'
                                      .format(item[4], item[0], item[1],
                                              int(item[3]/1000),
                                              int(item[2]/1000)))
            else:
                mean_bitrate = encoder_settings.get('meanbitrate')
                ratio = mean_bitrate / bitrate
                bitrate_error_perc = int((ratio - 1) * 100)
                bitrate_error.append([testname, bitrate_error_perc,
                                      int(bitrate), mean_bitrate,
                                      codec, resultfilename])

    labels = ['test', 'error', 'bitrate', 'real_bitrate', 'codec', 'file']
    data = pd.DataFrame.from_records(bitrate_error, columns=labels,
                                     coerce_float=True)
    data = data.sort_values(by=['bitrate'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- {name} -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():
            status = "passed"
            if abs(row.error) > ERROR_LIMIT:
                status = "failed"
            result_string += ('\n{:s} "Bitrate accuracy" {:3d} % error for {:4d}kbps ({:4d}kbps), codec: {:s}, {:s}'
                              .format(status,
                                      row.error, int(row.bitrate/1000),
                                      int(row.real_bitrate/1000),
                                      row.codec,
                                      row.file))

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
    parser.add_argument(
        '-i', '--videofile',
        help='Replace all test defined sources with input', default=None)
    parser.add_argument(
        '-is', '--input_res', help='Override input file', default=None)
    parser.add_argument(
        '-if', '--input_fps', help='Override input fps', default=None)
    parser.add_argument(
        '-os', '--output_res', help='Override input file', default=None)
    parser.add_argument(
        '-of', '--output_fps', help='Override input fps', default=None)
    parser.add_argument('-c', '--codec', help='Override encoder', default=None)
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
        if type(model) is dict:
            if 'model' in model:
                model = model.get('model')
            else:
                model = list(model.values())[0]

        if options.test is not None:
            # check if list
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
                if os.path.exists(es.INDEX_FILE_NAME):
                    os.remove(es.INDEX_FILE_NAME)
                '''
                args = []
                args.append(__file__)
                args.append('--codec')
                args.append(options.codec)
                args.append('--input')
                args.append(options.videofile)
                args.append('--input_res')
                args.append(options.input_res)
                args.append('--input_fps')
                args.append(options.input_fps)
                args.append('--output_res')
                args.append(options.output_res)
                args.append('--output_fps')
                args.append(options.output_fps)
                args.append('--configfile')
                args.append(configfile)
                '''
                settings = ep.extra_settings
                settings['configfile'] = test_path
                settings['videofile'] = options.videofile
                settings['encoder'] = options.codec
                settings['output'] = workdir

                result = ep.codec_test(settings, model, serial)
                bitrate_string += check_mean_bitrate_deviation(result)
                idr_string += check_idr_placement(result)
                temporal_string += check_temporal_layer(result)
                ltr_string += check_long_term_ref(result)

    result_string += print_partial_result(
        'Verify bitrate accuracy', bitrate_string)
    result_string += print_partial_result('Verify idr accuracy', idr_string)
    result_string += print_partial_result(
        'Verify check temporal layer accuracy', temporal_string)
    result_string += print_partial_result(
        'Verify long term reference settings', ltr_string)

    print(f'\nRESULTS\n{result_string}')
    with open(f'{workdir}/RESULT.txt', "w") as output:
        output.write(result_string)
        output.write('\n---------')
        extra = ""
        if model is not None and serial is not None:
            with open(f'{workdir}/dut.txt', 'w') as dut:
                now = datetime.now()
                dt_string = now.strftime('%Y-%m-%d_%H_%M')
                dut.write(f"\nTest performed: {dt_string}")
                if isinstance(model, str):
                    dut.write(f"\nDUT: {model}, serial: {serial}")
                else:
                    dut.write(f"\nDUT: {model['product']}, serial: {serial}")

        if os.path.exists(f'{workdir}/dut.txt'):
            with open(f'{workdir}/dut.txt', 'r') as dut:
                extra = dut.read()
        output.write(f'\n{extra}')
        output.write('\n')


if __name__ == '__main__':
    main(sys.argv)
