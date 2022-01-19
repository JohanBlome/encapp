#!/usr/local/bin/python3

"""
    Verify tests
"""
import argparse
import sys
import json
import os
import pandas as pd
import numpy as np
import re
import shutil
import encapp as ep
import encapp_search as es
from encapp import run_cmd
from datetime import datetime

default_tests = ['simple.180p.json', 
                 'bitrates.json', 
                 'dynamic_bitrate.json',
                 'dynamic_framerate.json',
                 'idr.json',
                 'lt2.json',
                 'ltr.json',
                 'ltr-2ref.json']

def parse_schema(schema):
    match = re.search('android.generic.([0-9]*)',schema)
    if match:
        return int(match.group(1))
    return -1


def parse_bitrate(bitrate):
    if isinstance(bitrate, str):
        br = -1
        if bitrate.find('k') > -1:
            br = int(str(bitrate).replace('k', '000'))
        elif bitrate.find('M') > -1:
            br = int(str(bitrate).replace('k', '000000'))
        return br
    else:
        return bitrate

def get_nal_data(videopath, codec):    
    ending = ""
    if codec.find('avc') or codec.find('h264') or codec.find('264'):
        ending = '264'
    elif codec.find('hevc') or codec.find('h265') or codec.find('265'):
        ending = '265'
    if (len(ending) > 0 ):
        filename = os.path.splitext(videopath)[0]
        print(f'filename = {filename}')
        print(f'videopath = {videopath}')
        
        if not os.path.exists(f'{filename}.{ending}.nal'):
            cmd= f'ffmpeg -i {videopath} -c copy -bsf:v h264_mp4toannexb {filename}.{ending}'
            print(f'cmd = {cmd}')
            ret, stdout, stderr = run_cmd(cmd)
            
            if ending == '264':
                cmd= f'h264nal {filename}.{ending} >  {filename}.{ending}.nal'
            else:
                cmd= f'h265nal {filename}.{ending} > {filename}.{ending}.nal'

            print(f'cmd = {cmd}')
            ret, stdout, stderr = run_cmd(cmd)
        return f'{filename}.{ending}.nal'
        
    return ""

def check_pair_values(ref, data, ok_range):
    not_found = []
    wrong_value = []
    for key in ref.keys():
        found = False
        wrong = True
        for ext in range(0, ok_range):
            if (int(key) + ext) in data:
                # verify value
                if ref[key] != data[int(key) + ext]:
                    wrong_value.append([int(key), int(ref[key]), int(data[int(key) + ext])])
                found = True
                break;

        if not found:
            not_found.append(key)
    return not_found, wrong_value


def check_long_term_ref(testpath, resultpath):
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
            directory, resultfilename = os.path.split(file)
            encoder_settings = result.get('settings')            
            testname = result.get('test')
            bitrate = parse_bitrate(encoder_settings.get('bitrate'))
            fps = encoder_settings.get('fps')
            runtime_settings = result.get('runtime_settings')

            mark_frame = None 
            use_frame = None 
            if runtime_settings != None and len(runtime_settings) > 0:
                mark_frame = runtime_settings.get('vendor.qti-ext-enc-ltr.mark-frame')
                use_frame = runtime_settings.get('vendor.qti-ext-enc-ltr.use-frame')

            REG_LONG_TERM_ID = "long_term_frame_idx { ([0-9]*) }"
            REG_LONG_PIC_NUM = "long_term_pic_num { ([0-9]*) }"
            lt_mark = {}
            lt_use = {}
            if mark_frame != None and use_frame != None:
                nal_file = get_nal_data(f"{directory}/{result.get('encodedfile')}", encoder_settings.get('codec'))
                nal_data = ""
                frame = 0
                with open(nal_file) as nal:
                    line = "-1"
                    while len(line) > 0:
                        line  = nal.readline()
                        if line.find('frame_num:') != -1:
                            if frame < 4:
                                print(f'frame: {frame} - {line}')
                            frame += 1
                            match = re.search(REG_LONG_TERM_ID, line)
                            if match:
                                pid = match.group(1)
                                lt_mark[frame] = pid
                                continue
                            match = re.search(REG_LONG_PIC_NUM, line)
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
                    result_string += f'\nMarked ltr frames correct (within {ok_range} frames)'
                else:
                    if  len(not_found) > 0:
                        result_string += f'\nFollowing ltr frames not found (within {ok_range}: {not_found})'
                    if  len(wrong_values) > 0:
                        result_string += f'\nFollowing ltr frames have wrong value (within {ok_range}: {wrong_values})'
                        for item in wrong_values:
                            result_string += f'frame: {item} = {wrong_values[item]}'

                not_found, wrong_values = check_pair_values(use_frame, lt_use, 3)
                result_string += '\n(2) Verify long term reference use setting'
                if len(not_found) == 0 and len(wrong_values) == 0:
                    print(f'{not_found} - {{not_found}}')
                    result_string += f'Used ltr frames correct (within {ok_range})'
                else:
                    if  len(not_found) > 0:
                        result_string += f'\nFollowing ltr use frames not found (within {ok_range} frames):\n{not_found})'
                    if  len(wrong_values) > 0:
                        result_string +=f'\nFollowing ltr use frames have wrong value (within {ok_range}):'
                        for item in wrong_values:
                            result_string += '\nframe {:4d} - ref: {:2d}, should have been {:2d}'.format(item[0], item[2], item[1])
                                

    return result_string
                
def check_temporal_layer(testpath, resultpath):
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
                for index in range(0,layer_count):
                    layer = list(filter(lambda x:((x['frame'] + index) % layer_count), frames))
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
                    result_string += '\nlayer {:d}:{:3d}%, {:s}'.format(size[0], int(round(ratio * 100, 0)), resultfilename)
            
    return result_string


def check_idr_placement(testpath, resultpath):
    result_string = ''
    status = []

    for file in resultpath:
        with open(file) as resultfile:            
            result = json.load(resultfile)
            _, resultfilename = os.path.split(file)
            encoder_settings = result.get('settings')                        
            testname = result.get('test')
            frames = result.get('frames')
            iframes = list(filter(lambda x:(x['iframe'] == 1), frames))
            idr_ids = []
            for frame in iframes:
                idr_ids.append(frame['frame'])            

            runtime_settings = result.get('runtime_settings')
            if runtime_settings != None and len(runtime_settings) > 0:                
                dynamic_sync = runtime_settings.get('request-sync')
                if dynamic_sync != None:
                    passed = True
                    for item in dynamic_sync:
                        if not int(item) in idr_ids:
                             passed = False
                                        
                    status.append([testname, "Runtime sync request", passed, gop, resultfilename])
                        
            
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
    data = pd.DataFrame.from_records(status, columns=labels,
                              coerce_float=True)
    data = data.sort_values(by=['gop'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- {name} -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():            
            result_string += '\n{:s} \"{:s}\", gop {:2d} sec, {:s}'.format({True:'passed', False: 'failed'}[row.passed], row.subtest, row.gop, row.file)

    return result_string
    

def check_mean_bitrate_deviation(testpath, resultpath):
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
            if runtime_settings != None and len(runtime_settings) > 0:
                dynamic_video_bitrate = runtime_settings.get('video-bitrate')
                
            if dynamic_video_bitrate != None:
                frames = result.get('frames')                    
                previous_limit = 0
                dyn_data = []
                target_bitrate = bitrate
                limits = list(dynamic_video_bitrate.keys())
                limits.append(frames[-1]['frame'])

                for limit in limits:
                    filtered = list(filter(lambda x:(x['frame'] >= int(previous_limit) and x['frame'] < int(limit)), frames))
                    accum = 0
                    for item in filtered:
                        accum += item['size']
                    # Calc mean in bits per second
                    mean = (fps * 8 * accum/len(filtered))
                    ratio = mean / target_bitrate
                    bitrate_error_perc = int((ratio - 1) * 100)
                    dyn_data.append([int(previous_limit), int(limit), int(target_bitrate), int(round(mean, 0)), int(round(bitrate_error_perc, 0))])
                    if limit in dynamic_video_bitrate:
                        target_bitrate = parse_bitrate(dynamic_video_bitrate[limit])
                    previous_limit = limit
                result_string += f'\n\n----- {testname}, runtime bitrate changes -----'
                result_string += f"\n Codec: {encoder_settings.get('codec')}, {encoder_settings.get('height')}p@{fps}fps"
                for item in dyn_data:
                    result_string += '\n{:3d}% error in {:4d}:{:4d} ({:4d}kbps) for {:4d}kbps, {:s}'.format(item[4], item[0], item[1], int(item[3]/1000), int(item[2]/1000), resultfilename)
            else:                
                mean_bitrate = encoder_settings.get('meanbitrate')
                ratio = mean_bitrate / bitrate
                bitrate_error_perc = int((ratio - 1) * 100)
                bitrate_error.append([testname, bitrate_error_perc, int(bitrate), mean_bitrate, resultfilename])
        
    labels = ['test', 'error', 'bitrate', 'real_bitrate', 'file']
    data = pd.DataFrame.from_records(bitrate_error, columns=labels,
                              coerce_float=True)
    data = data.sort_values(by=['bitrate'])
    test_names = np.unique(data['test'])
    for name in test_names:
        result_string += f'\n\n----- {name} -----'
        files = data.loc[data['test'] == name]
        for row in files.itertuples():
            result_string += '\n{:3d} % error for {:4d}kbps ({:4d}kbps), {:s}'.format(row.error, int(row.bitrate/1000), int(row.real_bitrate/1000), row.file)

    return result_string


def print_partial_result(header, partial_result):
    if len(partial_result) > 0:        
        result_string = f'\n\n\n   ===  {header} ==='
        result_string += '\n' + partial_result
        result_string += '\n-----\n'
        return result_string
    else:
        return ""


def main(argv):    
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument('-d','--dir', default='encapp_verify')
    parser.add_argument('-t', '--test', nargs="+",)
    parser.add_argument('-r', '--result',nargs="+",)
    parser.add_argument('-c', '--codec',nargs="+",)

    options = parser.parse_args()

    result_string = ""
    model = None
    serial = None
    
    bitrate_string = ""
    idr_string = ""
    temporal_string = ""
    ltr_string = ""
    workdir = options.dir
    if options.result != None:
        results = []
        for file in options.result:
            results.append(file)            
        bitrate_string +=check_mean_bitrate_deviation(options.test, results)
        idr_string += check_idr_placement(options.test, results)
        temporal_string += check_temporal_layer(options.test, results)
        ltr_string += check_long_term_ref(options.test, results)
    else:
        if os.path.exists(workdir):        
            shutil.rmtree(workdir)

        os.mkdir(workdir)
        model, serial = ep.get_device_info(options.serial)

        if options.test != None:
            #check if list
            tests = options.test
        else:
            tests = default_tests

        for test in tests:
            directory, _ = os.path.split(__file__)
            if options.test == None:
                test_path = directory + "/../tests/" + test
            else:
                test_path = test

            with open(test_path, 'r') as fp:
                bitrate_error = []
                tests_json = json.load(fp)
                if os.path.exists(es.INDEX_FILE_NAME):        
                    os.remove(es.INDEX_FILE_NAME)

                args = []
                if options.codec is not None and len(options.codec) >  0:
                    args.append('--codec')
                    args.append(options.codec[0])

                print(f'test: {test}, path = {test_path}')
                epOptions = ep.get_options(args)
                print(f'run test: {tests_json}')
                result = ep.run_encode_tests(tests_json,
                                 test_path,
                                 model,
                                 serial,
                                 "encapp_verify",
                                 workdir,
                                 epOptions)
                
                bitrate_string += check_mean_bitrate_deviation(test_path, result)
                idr_string += check_idr_placement(test_path, result)
                temporal_string += check_temporal_layer(test_path, result)
                ltr_string += check_long_term_ref(test_path, result)

    result_string += print_partial_result('Verify bitrate accuracy', bitrate_string)
    result_string += print_partial_result('Verify idr accuracy', idr_string)
    result_string += print_partial_result('Verify check temporal layer accuracy', temporal_string)
    result_string += print_partial_result('Verify long term reference settings', ltr_string)

    print(f'\nRESULTS\n{result_string}')
    with open(f'{workdir}/RESULT.txt', "w") as output:
        output.write(result_string)        
        output.write(f'\n---------')
        extra = ""
        if model != None and serial != None:
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
