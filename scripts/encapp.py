#!/usr/local/bin/python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
import json
import sys
import argparse
import re

from encapp_tests import run_cmd
from encapp_tests import get_device_info
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
KEY_NAME_I_FRAME_SIZES = 'i_frame_sizes'
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
              KEY_NAME_I_FRAME_SIZES:['unlimited'],
              # KEY_NAME_CONFIGURE: [''],
              # KEY_NAME_RUNTIME_PARAMETER: ['']
              KEY_NAME_DURATION: 10,
              KEY_NAME_ENC_LOOP: 0
            }]
    }


TEST_CLASS_NAME = 'com.facebook.encapp.CodecValidationInstrumentedTest'
JUNIT_RUNNER_NAME = \
    'com.facebook.encapp.test/androidx.test.runner.AndroidJUnitRunner'
ENCAPP_OUTPUT_FILE_NAME_RE = r'encapp_.*'
RD_RESULT_FILE_NAME = 'rd_results.json'


def install_app(serial):
    script_path = os.path.realpath(__file__)
    path, __ = os.path.split(script_path)
    run_cmd(f'adb -s {serial} install -g '
            f'{path}/../app/build/outputs/apk/androidTest/debug/'
            'com.facebook.encapp-v1.0-debug-androidTest.apk ')

    run_cmd(f'adb -s {serial} install -g '
            f'{path}/../app/build/outputs/apk/debug/'
            'com.facebook.encapp-v1.0-debug.apk')


def run_encode_tests(test_def, json_path, model, serial, test_desc,
                     workdir, options):
    if options.no_install:
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
    input_files = None
    for test in tests:
        print(f'push data for test = {test}')
        if len(options.input) > 0:
            inputfile = f'/sdcard/{os.path.basename(options.input)}'
            ret, stdout, stderr = run_cmd(
                f'adb -s {serial} shell ls {inputfile}')
            if len(stderr) > 0:
                run_cmd(f'adb -s {serial} push {options.input} /sdcard/')
        else:
            input_files = test.get(KEY_NAME_INPUT_FILES)
            if input_files is not None:
                for file in input_files:
                    if len(json_folder) > 0:
                        path = f'{json_folder}/{file}'
                    else:
                        path = f'{file}'
                    if exists(path):
                        run_cmd(f'adb -s {serial} push {path} /sdcard/')
                    else:
                        print(f'Media file is missing: {path}')
                        exit(0)

        json_name = f'{filename}_{counter}.json'
        counter += 1
        with open(json_name, "w") as outfile:
            json.dump(test, outfile)
        run_cmd(f'adb -s {serial} push {json_name} /sdcard/')
        os.remove(json_name)

        additional = ''
        if len(options.codec) > 0:
            additional = f'{additional} -e enc {options.codec}'

        if len(options.input) > 0:
            additional = f'{additional} -e file {inputfile}'

        if len(options.input_res) > 0:
            additional = f'{additional} -e ref_res {options.input_res}'

        if len(options.input_fps) > 0:
            additional = f'{additional} -e ref_fps {options.input_fps}'

        if len(options.output_fps) > 0:
            additional = f'{additional} -e fps {options.output_fps}'

        if len(options.output_res) > 0:
            additional = f'{additional} -e res {options.output_res}'

        run_cmd(f'adb -s {serial} shell am instrument -w -r {additional} '
                f'-e test /sdcard/{json_name} {JUNIT_RUNNER_NAME}')
        adb_cmd = 'adb -s ' + serial + ' shell ls /sdcard/'
        ret, stdout, stderr = run_cmd(adb_cmd)
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
            run_cmd(adb_cmd)

            # remove the json file on the device too
            adb_cmd = f'adb -s {serial} shell rm /sdcard/{file}'
            run_cmd(adb_cmd)

        adb_cmd = f'adb -s {serial} shell rm /sdcard/{json_name}'
        if input_files is not None:
            for file in input_files:
                base_file_name = os.path.basename(file)
                run_cmd(f'adb -s {serial} shell rm /sdcard/{base_file_name}')
        if len(options.input) > 0:
            base_file_name = os.path.basename(inputfile)
            run_cmd(f'adb -s {serial} shell rm /sdcard/{base_file_name}')
        run_cmd(adb_cmd)


def list_codecs(serial, model, install):
    if install:
        install_app(serial)

    adb_cmd = f'adb -s {serial} shell am instrument -w -r '\
              f'-e ui_hold_sec 1 '\
              f'-e list_codecs a -e class {TEST_CLASS_NAME} '\
              f'{JUNIT_RUNNER_NAME}'

    run_cmd(adb_cmd)
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

    options = parser.parse_args()

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit()

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
