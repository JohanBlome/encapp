#!/usr/local/bin/python3

import os
import json
import sys
import argparse
import re

from encapp_tests import run_cmd
from encapp_tests import check_device
from datetime import datetime

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
sample_config_json_data = [
    [{
        KEY_NAME_DESCRIPTION: 'sample',
        KEY_NAME_INPUT_FILES: [''],
        KEY_NAME_INPUT_FORMAT: 'mp4',
        KEY_NAME_INPUT_RESOLUTION: '1280x720',
        KEY_NAME_CODECS: ['hevc'],
        KEY_NAME_ENCODE_RESOLUTIONS: ['1280x720'],
        KEY_NAME_RC_MODES: ['cbr'],
        KEY_NAME_BITRATES: [500, 1000, 1500, 2000, 2500],
        KEY_NAME_I_INTERVALS: [2],
        # DEFAULT, MEDIUM, HUGE, UNLIMITED
        KEY_NAME_I_FRAME_SIZES:['unlimited'],        
        KEY_NAME_DURATION: 10,
        KEY_NAME_ENC_LOOP: 0,
        KEY_NAME_CONFIGURE: [''],
        KEY_NAME_RUNTIME_PARAMETER: ['']
    }
]]

JUNIT_RUNNER_NAME = \
    'com.facebook.encapp.test/android.support.test.runner.AndroidJUnitRunner'
ENCAPP_OUTPUT_FILE_NAME_RE = r'encapp_.*'
RD_RESULT_FILE_NAME = 'rd_results.json'

def install_app(serial_no):
    script_path = os.path.realpath(__file__)
    path, __=os.path.split(script_path)
    run_cmd(f"adb -s {serial_no} install -g "
            f"{path}/../app/build/outputs/apk/androidTest/debug/"\
            "com.facebook.encapp-v1.0-debug-androidTest.apk ")

    run_cmd(f"adb -s {serial_no} install -g "
            f"{path}/../app/build/outputs/apk/debug/"\
            "com.facebook.encapp-v1.0-debug.apk")
    

def run_encode_tests(tests, json_path, device_model, serial_no, test_desc, install, workdir):
    if install:
        install_app(serial_no)
    
    if tests is None:
        raise Exception('Test file is empty')
    

    print(f"{tests}")


    with open(workdir+'/config.json', 'w') as fp:
        json.dump(tests, fp, indent=4)

    path, filename=os.path.split(json_path)
    # remove old encapp files on device (!)
    run_cmd(f"adb -s {serial_no} rm /sdcard/encapp_*")

    run_cmd(f"adb -s {serial_no} push {json_path} /sdcard/")
    for test in tests:
        print(f"{test}")
        input_files = test.get(KEY_NAME_INPUT_FILES)
        for fl in input_files:            
            run_cmd(f"adb -s {serial_no} push {fl} /sdcard/")
                

    run_cmd(f"adb -s {serial_no} shell am instrument -w -r -e test "\
            f"/sdcard/{filename} {JUNIT_RUNNER_NAME}")
    adb_cmd = 'adb -s ' + serial_no + ' shell ls /sdcard/'
    ret, stdout, stderr = run_cmd(adb_cmd)
    output_files = re.findall(ENCAPP_OUTPUT_FILE_NAME_RE, stdout, re.MULTILINE)

    base_file_name = os.path.basename(json_path).rsplit(".", 1)[0]
    sub_dir = '_'.join([base_file_name, "files"])
    output_dir = f"{workdir}/{sub_dir}/"
    run_cmd(f"mkdir {output_dir}")
    
    for file in output_files:
        if file == '':
            print("No file found")
            continue
        # pull the output file
        print(f"pull {file} to {output_dir}")
    
        adb_cmd = f'adb -s {serial_no} pull /sdcard/{file} {output_dir}'
        run_cmd(adb_cmd)

        # remove the json file on the device too
        adb_cmd = f'adb -s {serial_no} shell rm /sdcard/{file}'
        run_cmd(adb_cmd)
    
    print("Done")

def list_codecs(serial_no, install):
    if install:
        install_app(serial_no)
    adb_cmd = 'adb -s ' + serial_no + ' shell am instrument  -w -r ' +\
              '-e list_codecs a -e class ' + TEST_CLASS_NAME + \
              ' ' + JUNIT_RUNNER_NAME
    run_cmd(adb_cmd)


def get_options(argv):
    parser = argparse.ArgumentParser(description='A Python script to run \
    ENCAPP tests on Android and collect results. \
    The script will create a directory based on device model and date, \
    and save encoded video and rate distortion results in the directory')
    parser.add_argument('test', nargs='*', help='Test cases in JSON format.')
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument('--config', help='Generate a sample config \
                         file in json format')
    parser.add_argument('-l', '--list_codecs', action='store_true',
                        help='List codecs the devices support')
    parser.add_argument('--desc', default="encapp", help='Test description')
    parser.add_argument('--install', default='true',
                        type=bool,
                        help='Do install apk')
    options = parser.parse_args()

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit()

    return options


def main(argv):
    options = get_options(argv)

    if options.config is not None:
        if options.config.endswith('.json') is False:
            print('Error: the config file should have .json extension')
        with open(options.config, 'w') as fp:
            json.dump(sample_config_json_data, fp, indent=4)
    else:
        device_model, serial_no = check_device(options.serial)
        if options.list_codecs is True:
            list_codecs(serial_no, options.install)
        else:
            # get date and time and format it
            now = datetime.now()
            dt_string = now.strftime('%m-%d-%Y_%H_%M')
            workdir = f'{device_model}_{dt_string}_{options.desc}'
            os.system('mkdir -p ' + workdir)
            for test in options.test:
                with open(test, 'r') as fp:
                    print(f"Load {test}")
                    tests_json = json.load(fp)
                    run_encode_tests(tests_json, 
                                     test, 
                                     device_model,
                                     serial_no,
                                     options.desc if options.desc is not None else '',
                                     options.install,
                                     workdir)


if __name__ == '__main__':
    main(sys.argv)
