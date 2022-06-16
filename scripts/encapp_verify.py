#!/usr/bin/env python3

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
from encapp_tool.adb_cmds import run_cmd, get_device_info
from encapp import convert_to_bps
from google.protobuf import text_format
import proto.tests_pb2 as proto

DEFAULT_TESTS = ['bitrate_buffer.pbtxt',
                 'bitrate_surface.pbtxt',
                 'bitrate_transcoder_show.pbtxt',
                 'dynamic_bitrate.pbtxt',
                 'dynamic_framerate.pbtxt',
                 'dynamic_idr.pbtxt',
                 'lt2.pbtxt',
                 'ltr-2ref.pbtxt',
                 'camera.pbtxt',
                 'camera_parallel.pbtxt'
                 ]


def parse_schema(schema):
    match = re.search('android.generic.([0-9]*)', schema)
    if match:
        return int(match.group(1))
    return -1


def get_nal_data(videopath, codec):
    ending = ''
    if codec.find('avc') or codec.find('h264') or codec.find('264'):
        ending = '264'
    elif codec.find('hevc') or codec.find('h265') or codec.find('265'):
        ending = '265'
    if len(ending) > 0:
        filename = os.path.splitext(videopath)[0]
        if not os.path.exists(f'{filename}.{ending}.nal'):
            if ending == '264':
                cmd = (f'ffmpeg -i {videopath} -c copy -bsf:v h264_mp4toannexb'
                       f' {filename}.{ending}')
                run_cmd(cmd, True)
                cmd = f'h264nal {filename}.{ending} > {filename}.{ending}.nal'
            else:
                cmd = (f'ffmpeg -i {videopath} -c copy -bsf:v hevc_mp4toannexb'
                       f' {filename}.{ending}')
                run_cmd(cmd, True)
                cmd = f'h265nal {filename}.{ending} > {filename}.{ending}.nal'

            print(f'cmd = {cmd}')
            run_cmd(cmd)
        return f'{filename}.{ending}.nal'

    return ''


def find_frame(frame, rfid, frame_list, count):
    ''' Check mark or use, verifying the id used as welll
    '''
    for index in range(0, count, 1):
        if frame + index in frame_list:
            if rfid == frame_list[frame + index]:
                return frame + index
    return -1

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

            mark_frame = None
            use_frame = None

            test_def = result.get('testdefinition')
            test = text_format.Parse(test_def, proto.Test())

            dynamics = parse_dynamic_settings(test.runtime)['params']

            if dynamics is not None and len(dynamics) > 0:
                mark_frame = dynamics['vendor.qti-ext-enc-ltr.mark-frame']
                use_frame = dynamics['vendor.qti-ext-enc-ltr.use-frame']

            reg_long_term_id = 'long_term_frame_idx { ([0-9]*) }'
            reg_long_pic_id = 'long_term_pic_num { ([0-9]*) }'
            reg_max_num_ref_frames = 'max_num_ref_frames: ([0-9]*)'
            lt_mark = {}
            lt_use = {}
            if mark_frame is not None and use_frame is not None:
                nal_file = get_nal_data(f'{directory}/'
                                        f"{result.get('encodedfile')}",
                                        encoder_settings.get('codec'))
                ltr_count = -1
                frame = 0
                with open(nal_file) as nal:
                    line = '-1'
                    while len(line) > 0:
                        line = nal.readline()
                        if line.find('frame_num:') != -1:
                            # if frame < 4:
                            #    print(f'frame: {frame} - {line}')
                            frame += 1
                            match = re.search(reg_long_term_id, line)
                            if match:
                                num = match.group(1)
                                lt_mark[frame] = num
                                continue
                            match = re.search(reg_long_pic_id, line)
                            if match:
                                num = match.group(1)
                                lt_use[frame] = num
                                continue
                        match = re.search(reg_max_num_ref_frames, line)
                        if match:
                            num = int(match.group(1)) - 1
                            if ltr_count != -1:
                                print(
                                    'ERROR: ltr count appears multiple times, '
                                    f'{ltr_count} -> {num}')
                            ltr_count = num
                            continue
                # ltr refs are flushed after an I frame
                frames = result.get('frames')
                iframes = list(filter(lambda x: (x['iframe'] == 1), frames))
                result_string += f'\n\n----- test case: [{testname}] -----'
                # each mark frame will cause a use frame so merge the mark
                # with the use

                for frame in iframes:
                    lt_mark[frame['frame']] = 0  # implicit marking of 0th
                    for ltr in range(0, ltr_count - 1, 1):
                        mark_frame[frame['original_frame'] + ltr] = ltr
                        use_frame[frame['original_frame'] + ltr_count + 1] = ltr

                ok_range = 2
                matching = {}

                result_string += f'\nLtr frame count: {ltr_count}'
                result_string += '\n(1) Verify long term reference mark'

                # Check 'mark' frames
                for frame in sorted(mark_frame.keys()):
                    frame_match = find_frame(
                        frame, mark_frame[frame], lt_mark, ok_range)
                    if frame_match != -1:
                        matching[frame] = frame_match

                not_found = {}
                for frame in sorted(mark_frame.keys()):
                    if not (frame in matching.keys()):
                        not_found[frame] = mark_frame[frame]

                if len(matching) > 0:
                    result_string += ('\nMarked ltr frames correct (within '
                                      f'{ok_range} frames)')
                    for frame in matching.keys():
                        result_string += (
                            f'\nframe: {frame} as {matching[frame]} id: '
                            f'{mark_frame[frame]}')
                if len(not_found) > 0:
                    result_string += ('\nFollowing mark ltr frames not found '
                                      f'(within {ok_range})')
                    for frame in sorted(not_found.keys()):
                        result_string += f'\n{frame} id:{not_found[frame]}'

                # Check 'use' frames
                matching = {}
                for frame in sorted(use_frame.keys()):
                    frame_match = find_frame(
                        frame, use_frame[frame], lt_use, ok_range)
                    if frame_match != -1:
                        matching[frame] = frame_match
                # How many missed?
                not_found = {}
                for frame in sorted(use_frame.keys()):
                    if not (frame in matching.keys()):
                        not_found[frame] = use_frame[frame]
                result_string += '\n(2) Verify long term reference use setting'
                if len(matching) > 0:
                    result_string += ('\nUsed ltr frames correct (within '
                                      f'{ok_range})')
                    for frame in matching:
                        result_string += (
                            f'\nframe: {frame} as {matching[frame]} id: '
                            f'{use_frame[frame]}')

                if len(not_found) > 0:
                    result_string += ('\nFollowing ltr use frames not '
                                      f'found (within {ok_range} frames):')
                    for frame in sorted(not_found.keys()):
                        result_string += f'\n{frame} id:{not_found[frame]}'

                # What was found
                result_string += '\n\nMarked in media:'
                for val in lt_mark:
                    result_string += ('\nframe {:4d} - id: {:d}'
                                      .format(val, int(lt_mark[val])))
                result_string += '\nUsed in media:'
                for val in lt_use:
                    result_string += ('\nframe {:4d} - id: {:d}'
                                      .format(val, int(lt_use[val])))

                if len(iframes) > 0:
                    result_string += '\nKey frames:\n'
                    for frame in iframes:
                        result_string += f"{frame['frame']}\n"
    return result_string


def get_config_param(config, param_name):
    for param in config.parameter:
        if param.key == param_name:
            return param.value


def check_temporal_layer(resultpath):
    result_string = ''

    for file in resultpath:
        with open(file) as resultfile:
            result = json.load(resultfile)
            _, resultfilename = os.path.split(file)
            testname = result.get('test')
            encoder_settings = result.get('settings')
            schema = encoder_settings.get('ts-schema')

            test_def = result.get('testdefinition')
            test = text_format.Parse(test_def, proto.Test())
            schema = get_config_param(test.configure, 'ts-schema')
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

                result_string += f'\n\n----- test case: [{testname}] -----'
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
            if gop <= 0:
                print('gop is missing')
                gop = 1
            fps = encoder_settings.get('fps')
            if fps <= 0:
                print('fps is missing')
                fps = 30
            for frame in iframes:
                idr_ids.append(frame['frame'])

            test_def = result.get('testdefinition')
            test = text_format.Parse(test_def, proto.Test())

            dynamic_sync = parse_dynamic_settings(test.runtime)['syncs']
            if dynamic_sync is not None:
                passed = True
                for item in dynamic_sync:
                    if int(item) not in idr_ids:
                        passed = False

                    status.append([testname, 'Runtime sync request', passed,
                                   item, resultfilename])
            frame_gop = gop * fps
            passed = True
            if frame_gop < len(frames):
                for frame in idr_ids:
                    if frame % frame_gop != 0:
                        passed = False
            # TODO: check for missing key frames
            status.append([testname, 'Even gop', passed, gop, resultfilename])

    labels = ['test', 'subtest', 'passed', 'gop', 'file']
    data = pd.DataFrame.from_records(status, columns=labels, coerce_float=True)
    data = data.sort_values(by=['gop'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- test case: [{testname}] -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():
            result_string += ('\n{:s} "{:s}" at {:2d} frames, {:s}'
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
        # print(f'{param}')
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
            test = text_format.Parse(test_def, proto.Test())
            parse_dynamic_settings(test.runtime)
            dynamic_video_bitrate = parse_dynamic_settings(test.runtime)[
                'bitrates']

            if (dynamic_video_bitrate is not None
               and len(dynamic_video_bitrate) > 0):
                frames = result.get('frames')
                previous_limit = 0
                dyn_data = []
                target_bitrate = bitrate
                limits = list(dynamic_video_bitrate.keys())
                limits.append(frames[-1]['frame'])
                status = 'passed'
                limit_too_high = False
                for limit in limits:
                    if limit > len(frames):
                        limit_too_high = True
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
                        mean = (fps * 8 * accum / num)
                    else:
                        mean = 0
                    ratio = mean / target_bitrate
                    bitrate_error_perc = int((ratio - 1) * 100)
                    if abs(bitrate_error_perc) > ERROR_LIMIT:
                        status = 'failed'
                    dyn_data.append([int(previous_limit), int(limit),
                                     int(target_bitrate), int(round(mean, 0)),
                                     int(round(bitrate_error_perc, 0))])
                    if limit in dynamic_video_bitrate:
                        target_bitrate = convert_to_bps(
                            dynamic_video_bitrate[limit])
                    previous_limit = limit
                result_string += f'\n\n----- test case: [{testname}] -----'

                result_string += (f'\n{status} "Dynamic bitrate", ')
                result_string += (f" codec: {encoder_settings.get('codec')}"
                                  f", {encoder_settings.get('height')}"
                                  f'p @ {fps}fps'
                                  f', {resultfilename}')

                if limit_too_high:
                    result_string += (
                        f'\nERROR: limit higher than available frames '
                        f'({len(frames)}), adjust test case')
                for item in dyn_data:
                    result_string += ('\n      {:3d}% error in {:4d}:{:4d} '
                                      '({:4d}kbps) for {:4d}kbps'
                                      .format(item[4], item[0], item[1],
                                              int(item[3] / 1000),
                                              int(item[2] / 1000)))
                result_string += f'\n      (limit set to {ERROR_LIMIT}%)'
            else:
                mean_bitrate = encoder_settings.get('meanbitrate')
                ratio = mean_bitrate / bitrate
                bitrate_error_perc = int((ratio - 1) * 100)
                bitrate_error.append([testname, bitrate_error_perc,
                                      int(bitrate), mean_bitrate,
                                      codec, encoder_settings.get('height'),
                                      fps, resultfilename])

    labels = ['test', 'error', 'bitrate', 'real_bitrate',
              'codec', 'height', 'fps', 'file']
    data = pd.DataFrame.from_records(bitrate_error, columns=labels,
                                     coerce_float=True)
    data = data.sort_values(by=['bitrate'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- test case: [{testname}] -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():
            status = 'passed'
            if abs(row.error) > ERROR_LIMIT:
                status = 'failed'
            result_string += (
                '\n{:s} "Bitrate accuracy" {:3d} % error for '
                '{:4d}kbps ({:4d}kbps), codec: {:s}, {:4d}p @ {:.2f} fps, {:s}'
                .format(status,
                        row.error, int(row.bitrate / 1000),
                        int(row.real_bitrate / 1000),
                        row.codec,
                        row.height,
                        row.fps,
                        row.file))
        result_string += f'\n      (limit set to {ERROR_LIMIT}%)'

    return result_string



def check_framerate_deviation(resultpath):
    result_string = ''
    framerate_error = []

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
            test = text_format.Parse(test_def, proto.Test())
            parse_dynamic_settings(test.runtime)
            dynamic_video_framerates = parse_dynamic_settings(test.runtime)[
                'framerates']
            frames = result.get('frames')
            print(f"dyn frmrate: {dynamic_video_framerates}")
            if (dynamic_video_framerates is not None
               and len(dynamic_video_framerates) > 0):
                previous_limit = 0
                dyn_data = []
                limits = list(dynamic_video_framerates.keys())
                limits.append(frames[-1]['original_frame'])
                status = 'passed'
                limit_too_high = False
                target_rate = fps
                for limit in limits:
                    if limit > len(frames):
                        limit_too_high = True
                    filtered = list(filter(lambda x: (x['original_frame'] >=
                                                      int(previous_limit) and
                                                      x['original_frame'] < int(limit)),
                                           frames))
                    frame1 = filtered[0]
                    frame2 = filtered[-1]
                    actual_framerate, deviation_perc = calcFrameRate(frame1,
                                                                     frame2,
                                                                     target_rate)
                    if abs(deviation_perc) > ERROR_LIMIT:
                        status = 'failed'
                    dyn_data.append([int(previous_limit), int(limit),
                                     target_rate, round(actual_framerate, 2),
                                     int(round(deviation_perc, 0))])

                    previous_limit = limit
                    if limit in dynamic_video_framerates:
                        target_rate = dynamic_video_framerates[limit]

                print(f"Dyn data = {dyn_data}")
                result_string += f'\n\n----- test case: [{testname}] -----'

                result_string += (f'\n{status} "Dynamic framerate", ')
                result_string += (f" codec: {encoder_settings.get('codec')}"
                                  f", {encoder_settings.get('height')}"
                                  f'p @ {fps}fps'
                                  f', {resultfilename}')

                if limit_too_high:
                    result_string += (
                        f'\nERROR: limit higher than available frames '
                        f'({len(frames)}), adjust test case')
                for item in dyn_data:
                    result_string += ('\n      {:3d}% error in {:4d}:{:4d} '
                                      '({:.2f} fps) for {:.2f} fps'
                                      .format(item[4],
                                              item[0],
                                              item[1],
                                              item[3],
                                              item[2]))
                result_string += f'\n      (limit set to {ERROR_LIMIT}%)'
            elif len(frames) > 0:
                framerate = encoder_settings.get('framerate')
                frame1 = frames[0]
                frame2 = frames[-1]
                actual_framerate, deviation_perc = calcFrameRate(frame1,
                                                                 frame2,
                                                                 fps)
                framerate_error.append([testname, int(round(deviation_perc, 0)),
                                      fps, actual_framerate,
                                      codec, encoder_settings.get('height'),
                                      fps, resultfilename])

                labels = ['test', 'error', 'framerate', 'real_framerate',
                          'codec', 'height', 'fps', 'file']
                data = pd.DataFrame.from_records(framerate_error, columns=labels,
                                                 coerce_float=True)
                data = data.sort_values(by=['framerate'])
                print(f'{data}')
                test_names = np.unique(data['test'])
                for name in test_names:
                    result_string += f'\n\n----- test case: [{testname}] -----'
                    files = data.loc[data['test'] == name]
                    for row in files.itertuples():
                        status = 'passed'
                        if abs(row.error) > ERROR_LIMIT:
                            status = 'failed'
                        result_string += (
                            '\n{:s} "Framerate accuracy" {:3d} % error for '
                            '{:.2f} fps ({:.2f} fps), codec: {:s}, {:4d}p @ {:.2f} fps, {:s}'
                            .format(status,
                                    row.error,
                                    row.framerate,
                                    row.real_framerate,
                                    row.codec,
                                    row.height,
                                    row.fps,
                                    row.file))
                    result_string += f'\n      (limit set to {ERROR_LIMIT}%)'
    return result_string


def calcFrameRate(frame1, frame2, target_rate):
    time_delta = (frame2['pts'] -  frame1['pts'])/1000000
    frame_delta = frame2['frame'] - frame1['frame']
    framerate = frame_delta/time_delta
    framerate_error_perc = 100 * (1 - target_rate/framerate);
    return framerate, framerate_error_perc;


def print_partial_result(header, partial_result):
    if len(partial_result) > 0:
        result_string = f'\n\n\n   ===  {header} ==='
        result_string += '\n' + partial_result
        result_string += '\n-----\n'
        return result_string
    return ''


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
        '-if', '--input_fps', type=float, help='Override input fps',
        default=None)
    parser.add_argument(
        '-os', '--output_res', help='Override input file', default=None)
    parser.add_argument(
        '-of', '--output_fps', type=float, help='Override input fps',
        default=None)
    parser.add_argument('-c', '--codec', help='Override encoder',
                        default=None)
    parser.add_argument('-t', '--test', nargs='+',)
    parser.add_argument('-r', '--result', nargs='+',)
    parser.add_argument('--bitrate_limit', nargs='?',
                        help='Set acceptance lmit on bitrate in percentage',
                        default=5)

    options = parser.parse_args(argv[1:])
    result_string = ''
    model = None
    serial = None

    if options.serial is None and 'ANDROID_SERIAL' in os.environ:
        # read serial number from ANDROID_SERIAL env variable
        options.serial = os.environ['ANDROID_SERIAL']

    global ERROR_LIMIT
    ERROR_LIMIT = int(options.bitrate_limit)
    bitrate_string = ''
    idr_string = ''
    temporal_string = ''
    ltr_string = ''
    framerate_string = ''
    workdir = options.dir
    if options.result is not None:
        results = []
        for file in options.result:
            results.append(file)
        bitrate_string += check_mean_bitrate_deviation(results)
        idr_string += check_idr_placement(results)
        temporal_string += check_temporal_layer(results)
        ltr_string += check_long_term_ref(results)
        framerate_string += check_framerate_deviation(results)
    else:
        if os.path.exists(workdir):
            shutil.rmtree(workdir)

        os.mkdir(workdir)
        model, serial = get_device_info(options.serial)
        ep.remove_encapp_gen_files(serial)

        if isinstance(model, dict):
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
                test_path = '../tests/' + test
                if len(directory) > 0:
                    test_path = f'{directory}/{test_path}'

            else:
                test_path = test

            if os.path.exists(es.INDEX_FILE_NAME):
                os.remove(es.INDEX_FILE_NAME)
                print(f'removed path: {es.INDEX_FILE_NAME}')
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
            settings['inp_resolution'] = options.input_res
            settings['out_resolution'] = options.output_res
            settings['inp_framerate'] = options.input_fps
            settings['out_framerate'] = options.output_fps
            settings['output'] = workdir

            result = ep.codec_test(settings, model, serial)
            bitrate_string += check_mean_bitrate_deviation(result)
            idr_string += check_idr_placement(result)
            temporal_string += check_temporal_layer(result)
            ltr_string += check_long_term_ref(result)
            framerate_string += check_framerate_deviation(result)

    result_string += print_partial_result(
        'Verify bitrate accuracy', bitrate_string)
    result_string += print_partial_result(
        'Verify framerate accuracy', framerate_string)
    result_string += print_partial_result('Verify idr accuracy', idr_string)
    result_string += print_partial_result(
        'Verify temporal layers', temporal_string)
    result_string += print_partial_result(
        'Verify long term reference settings', ltr_string)

    print(f'\nRESULTS\n{result_string}')
    with open(f'{workdir}/RESULT.txt', 'w') as output:
        output.write(result_string)
        output.write('\n---------')
        extra = ''
        if model is not None and serial is not None:
            with open(f'{workdir}/dut.txt', 'w') as dut:
                now = datetime.now()
                dt_string = now.strftime('%Y-%m-%d_%H_%M')
                dut.write(f'\nTest performed: {dt_string}')
                if isinstance(model, str):
                    dut.write(f'\nDUT: {model}, serial: {serial}')
                else:
                    dut.write(f"\nDUT: {model['product']}, serial: {serial}")

        if os.path.exists(f'{workdir}/dut.txt'):
            with open(f'{workdir}/dut.txt', 'r') as dut:
                extra = dut.read()
        output.write(f'\n{extra}')
        output.write('\n')


if __name__ == '__main__':
    main(sys.argv)
