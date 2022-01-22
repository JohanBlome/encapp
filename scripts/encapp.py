#!/usr/local/bin/python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
import subprocess
import json
import sys
import argparse
import re
import time

from datetime import datetime
from os.path import exists

KEY_NAME_DESCRIPTION = 'description'
KEY_NAME_INPUT_FILES = 'input_files'
KEY_NAME_CODECS = 'codecs'
KEY_NAME_ENCODE_RESOLUTIONS = 'encode_resolutions'
KEY_NAME_RC_MODES = 'rc_modes'
KEY_NAME_BITRATES = 'bitrates'
KEY_NAME_I_INTERVALS = 'i_intervals'
KEY_NAME_DURATION = 'duration'
KEY_NAME_INPUT_FORMAT = 'input_format'
KEY_NAME_INPUT_RESOLUTION = 'input_resolution'
KEY_NAME_TEMPORAL_LAYER_COUNTS = 'temporal_layer_counts'
KEY_NAME_ENC_LOOP = 'enc_loop'
KEY_NAME_CONFIGURE = 'configure'
KEY_NAME_RUNTIME_PARAMETER = 'runtime_parameter'

sample_config_json_data = \
    {
        'tests':
            [{
              KEY_NAME_DESCRIPTION: 'sample',
              KEY_NAME_INPUT_FILES: [''],
              KEY_NAME_INPUT_FORMAT: 'mp4',
              KEY_NAME_INPUT_RESOLUTION: '1280x720',
              KEY_NAME_CODECS: ['h264.encoder'],
              KEY_NAME_ENCODE_RESOLUTIONS: ['1280x720'],
              KEY_NAME_RC_MODES: ['cbr'],
              KEY_NAME_BITRATES: ['500k', '1000k', '1500k', '2M', '2500k'],
              KEY_NAME_I_INTERVALS: [2],
              # DEFAULT, MEDIUM, HUGE, UNLIMITED
              # KEY_NAME_CONFIGURE: [''],
              # KEY_NAME_RUNTIME_PARAMETER: ['']
              KEY_NAME_DURATION: 10,
              KEY_NAME_ENC_LOOP: 0
            }]
    }


ACTIVITY = 'com.facebook.encapp/.MainActivity'
ENCAPP_OUTPUT_FILE_NAME_RE = r'encapp_.*'
RD_RESULT_FILE_NAME = 'rd_results.json'


def run_cmd_silent(cmd):
    ret = True
    try:
        process = subprocess.Popen(cmd, shell=True,
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        stdout, stderr = process.communicate()
    except Exception:
        ret = False
        print('Failed to run command: ' + cmd)

    return ret, stdout.decode(), stderr.decode()


def run_cmd(cmd):
    ret = True
    try:
        print(cmd, sep=' ')
        process = subprocess.Popen(cmd, shell=True,
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        stdout, stderr = process.communicate()
    except Exception:
        ret = False
        print('Failed to run command: ' + cmd)

    return ret, stdout.decode(), stderr.decode()


# returns info (device model and serial number) about the device where the
# test will be run
def get_device_info(serial_inp, debug=0):
    # list all available devices
    adb_cmd = 'adb devices -l'
    ret, stdout, stderr = run_cmd(adb_cmd)
    assert ret, 'error: failed to get adb devices'

    # parse list
    device_info = {}
    for line in stdout.split('\n'):
        if line == 'List of devices attached' or line == '':
            continue
        serial = line.split()[0]
        item_dict = {}
        for item in line.split()[1:]:
            # ':' used to separate key/values
            if ':' in item:
                key, val = item.split(':', 1)
                item_dict[key] = val
        # ensure the 'model' field exists
        if 'model' not in item_dict:
            item_dict['model'] = 'generic'
        device_info[serial] = item_dict
    assert len(device_info) > 0, 'error: no devices connected'
    if debug > 2:
        print('available devices: %r' % device_info)

    # select output device
    serial, model = None, None
    if serial_inp is None:
        # if user did not select a serial_inp, make sure there is only one
        # device available
        assert len(device_info) == 1, (
            'error: need to choose a device %r' % list(device_info.keys()))
        serial = list(device_info.keys())[0]
        model = device_info[serial]

    else:
        # if user forced a serial number, make sure it is available
        assert serial_inp in device_info, (
            'error: device %s not available' % serial_inp)
        serial = serial_inp
        model = device_info[serial]

    if debug > 0:
        print('selecting device: serial: %s model: %s' % (serial, model))

    # remove any files that are generated in previous runs
    adb_cmd = 'adb -s ' + serial + ' shell ls /sdcard/'
    ret, stdout, stderr = run_cmd(adb_cmd)
    output_files = re.findall(ENCAPP_OUTPUT_FILE_NAME_RE, stdout, re.MULTILINE)
    for file in output_files:
        if file == '':
            continue
        # remove the output
        adb_cmd = 'adb -s ' + serial + ' shell rm /sdcard/' + file
        run_cmd(adb_cmd)

    return model, serial


def wait_for_exit(serial):
    adb_cmd = f'adb -s {serial} shell pidof com.facebook.encapp'
    pid = -1
    current = 1
    while (current != -1):
        if pid == -1:
            ret, stdout, stderr = run_cmd_silent(adb_cmd)
            pid = -1
            if len(stdout) > 0:
                pid = int(stdout)
        time.sleep(1)
        ret, stdout, stderr = run_cmd_silent(adb_cmd)
        current = -2
        if len(stdout) > 0:
            current = int(stdout)
        else:
            current = -1
    print(f'Exit from {pid}')


def install_app(serial):
    script_path = os.path.realpath(__file__)
    path, __ = os.path.split(script_path)

    run_cmd_silent(f'adb -s {serial} install -g '
                   f'{path}/../app/build/outputs/apk/debug/'
                   'com.facebook.encapp-v1.0-debug.apk')


def run_test(workdir, json_path, json_name,
             input_files, result_json, serial, options):
    run_cmd_silent(f'adb -s {serial} push {json_name} /sdcard/')

    additional = ''
    if options.codec is not None and len(options.codec) > 0:
        additional = f'{additional} -e enc {options.codec}'

    if options.input is not None and len(options.input) > 0:
        additional = f'{additional} -e file {options.input}'

    if options is not None and len(options.input_res) > 0:
        additional = f'{additional} -e ref_res {options.input_res}'

    if options is not None and len(options.input_fps) > 0:
        additional = f'{additional} -e ref_fps {options.input_fps}'

    if options is not None and len(options.output_fps) > 0:
        additional = f'{additional} -e fps {options.output_fps}'

    if options is not None and len(options.output_res) > 0:
        additional = f'{additional} -e res {options.output_res}'

    run_cmd(f'adb -s {serial} shell am start -W {additional} -e test '
            f'/sdcard/{json_name} {ACTIVITY}')
    wait_for_exit(serial)
    adb_cmd = 'adb -s ' + serial + ' shell ls /sdcard/'
    ret, stdout, stderr = run_cmd_silent(adb_cmd)
    output_files = re.findall(ENCAPP_OUTPUT_FILE_NAME_RE, stdout,
                              re.MULTILINE)

    base_file_name = os.path.basename(json_path).rsplit('.', 1)[0]
    sub_dir = '_'.join([base_file_name, 'files'])
    output_dir = f'{workdir}/{sub_dir}/'
    run_cmd(f'mkdir {output_dir}')

    for file in output_files:
        if file == '':
            print('No file found')
            continue
        # pull the output file
        print(f'pull {file} to {output_dir}')

        adb_cmd = f'adb -s {serial} pull /sdcard/{file} {output_dir}'
        run_cmd_silent(adb_cmd)

        # remove the json file on the device too
        adb_cmd = f'adb -s {serial} shell rm /sdcard/{file}'
        run_cmd_silent(adb_cmd)
        if file.endswith('.json'):
            path, tmpname = os.path.split(file)
            result_json.append(f'{output_dir}/{tmpname}')

    adb_cmd = f'adb -s {serial} shell rm /sdcard/{json_name}'
    run_cmd(adb_cmd)
    return result_json


def run_encode_tests(test_def, json_path, model, serial, test_desc,
                     workdir, options):
    result_json = []
    if options.no_install is not None and options.no_install:
        print('Skip install of apk!')
    else:
        install_app(serial)

    if test_def is None:
        raise Exception('No test files')

    path, filename = os.path.split(json_path)
    # remove old encapp files on device (!)
    run_cmd(f'adb -s {serial} rm /sdcard/encapp_*')
    # run_cmd(f'adb -s {serial} push {json_path} /sdcard/')

    json_folder = os.path.dirname(json_path)
    inputfile = ''
    tests = test_def.get('tests')
    print(f'tests {tests}')
    if isinstance(tests, type(None)):
        tests = [test_def]
    counter = 1
    all_input_files = []
    # push media files to the device
    for test in tests:
        print(f'push data for test = {test}')
        if options is not None and len(options.input) > 0:
            all_input_files.append(inputfile)
            inputfile = f'/sdcard/{os.path.basename(options.input)}'
            ret, stdout, stderr = run_cmd_silent(
                f'adb -s {serial} shell ls {inputfile}')
            if len(stderr) > 0:
                run_cmd_silent(f'adb -s {serial} push {options.input} '
                               '/sdcard/')
        else:
            input_files = test.get(KEY_NAME_INPUT_FILES)
            if input_files is not None:
                for file in input_files:
                    if len(json_folder) > 0 and not os.path.isabs(file):
                        path = f'{json_folder}/{file}'
                    else:
                        path = f'{file}'
                    all_input_files.append(f'/sdcard/{os.path.basename(path)}')
                    if exists(path):
                        run_cmd(f'adb -s {serial} push {path} /sdcard/')
                    else:
                        print(f'Media file is missing: {path}')
                        exit(0)

    # run test(s)
    if not options.no_split:
        for test in tests:
            json_name = f'{filename}_{counter}.json'
            counter += 1
            with open(json_name, "w") as outfile:
                json.dump(test, outfile)
            run_test(workdir, json_path, json_name, input_files,
                     result_json, serial, options)
            os.remove(json_name)
    else:
        run_test(workdir, json_folder, filename, input_files,
                 result_json, serial, options)

    if len(all_input_files) > 0:
        for file in all_input_files:
            run_cmd(f'adb -s {serial} shell rm {file}')

    return result_json


def list_codecs(serial, model, install):
    if install:
        install_app(serial)

    adb_cmd = f'adb -s {serial} shell am start '\
              f'-e ui_hold_sec 3 '\
              f'-e list_codecs a {ACTIVITY}'

    run_cmd_silent(adb_cmd)
    wait_for_exit(serial)
    filename = f'codecs_{model}.txt'
    adb_cmd = f'adb -s {serial} pull /sdcard/codecs.txt {filename}'
    run_cmd(adb_cmd)
    with open(filename, 'r') as codec_file:
        lines = codec_file.readlines()
        for line in lines:
            print(line.split('\n')[0])
        print(f'File is available in current dir as {filename}')


def get_options(argv):
    parser = argparse.ArgumentParser(description=__doc__)

    parser.add_argument('test', nargs='*', help='Test cases in JSON format.')
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument('--config', help='Generate a sample config \
                         file in json format')
    parser.add_argument('-l', '--list_codecs', action='store_true',
                        help='List codecs the devices support')
    parser.add_argument('--desc', default='encapp', help='Test description')
    parser.add_argument('-o', '--output', help='Name output directory')
    parser.add_argument('--no_install', action='store_true',
                        help='Do not install apk')

    parser.add_argument('--codec', help='Override used codec', default='')
    parser.add_argument('--input', help='Override input file', default='')
    parser.add_argument('--input_res', help='Override input file', default='')
    parser.add_argument('--input_fps', help='Override input fps', default='')
    parser.add_argument('--output_fps', help='Override output fps', default='')
    parser.add_argument('--output_res', help='Override output resolution',
                        default='')
    parser.add_argument('--no_split', action='store_true')

    options = parser.parse_args(argv[1:])

    if options.serial is None and 'ANDROID_SERIAL' in os.environ:
        # read serial number from ANDROID_SERIAL env variable
        options.serial = os.environ['ANDROID_SERIAL']

    return options


def main(argv):
    options = get_options(argv)
    if options.config is not None:
        if options.config.endswith('.json') is False:
            print('Error: the config file should have .json extension')
        with open(options.config, 'w') as fp:
            json.dump(sample_config_json_data, fp, indent=4)
    else:
        model, serial = get_device_info(options.serial)
        if type(model) is dict:
            if 'model' in model:
                model = model.get('model')
            else:
                model = list(model.values())[0]
        print(f'model = {model}')
        if options.list_codecs is True:
            list_codecs(serial, model, not options.no_install)
        else:
            # get date and time and format it
            now = datetime.now()
            dt_string = now.strftime('%Y-%m-%d_%H_%M')
            workdir = (
                f"{options.desc.replace(' ', '_')}_{model}_{dt_string}")
            if options.output is not None:
                workdir = options.output
            os.system('mkdir -p ' + workdir)
            for test in options.test:
                with open(test, 'r') as fp:
                    tests_json = json.load(fp)
                    run_encode_tests(tests_json,
                                     test,
                                     model,
                                     serial,
                                     options.desc if options.desc is
                                     not None else '',
                                     workdir,
                                     options)


if __name__ == '__main__':
    main(sys.argv)
