#!/usr/bin/env python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
import subprocess
import humanfriendly
import json
import sys
import argparse
import re
import time
import datetime
import shutil

from _version import __version__

SCRIPT_DIR = os.path.abspath(os.path.dirname(__file__))
SCRIPT_ROOT_DIR = os.path.join(SCRIPT_DIR, '..')
sys.path.append(SCRIPT_ROOT_DIR)
import proto.tests_pb2 as tests_definitions  # noqa: E402


APPNAME_MAIN = 'com.facebook.encapp'
ACTIVITY = f'{APPNAME_MAIN}/.MainActivity'
ENCAPP_OUTPUT_FILE_NAME_RE = r'encapp_.*'
RD_RESULT_FILE_NAME = 'rd_results.json'

SCRIPT_PATH = os.path.realpath(__file__)
SCRIPT_DIR, _ = os.path.split(SCRIPT_PATH)
APK_DIR = os.path.join(SCRIPT_DIR, '../app/releases')
APK_NAME_MAIN = f'{APPNAME_MAIN}-v{__version__}-debug.apk'
APK_MAIN = os.path.join(APK_DIR, APK_NAME_MAIN)

DEBUG = False

FUNC_CHOICES = {
    'help': 'show help options',
    'install': 'install apks',
    'uninstall': 'uninstall apks',
    'list': 'list codecs and devices supported',
    'run': 'run codec test case',
}

default_values = {
    'debug': 0,
    'func': 'help',
    'install': False,
    'videofile': None,
    'configfile': None,
    'encoder': None,
    'output': None,
    'bps': None,
}

extra_settings = {
    'videofile': None,
    'configfile': None,
    'encoder': None,
    'output': None,
    'bitrate': None,
    'desc': 'encapp',
    'inp_resolution': None,
    'out_resolution': None,
    'inp_framerate': None,
    'out_framerate': None,
}

RAW_EXTENSION_LIST = ('.yuv', '.rgb', '.raw')
OPERATION_TYPES = ('batch', 'realtime')
PIX_FMT_TYPES = ('yuv420p', 'nv12')
KNOWN_CONFIGURE_TYPES = {
    'codec': str,
    'encode': bool,
    'surface': bool,
    'mime': str,
    'bitrate': int,
    'bitrate-mode': int,
    'durationUs': int,
    'resolution': str,
    'width': int,
    'height': int,
    'color-format': int,
    'color-standard': int,
    'color-range': int,
    'color-transfer': int,
    'color-transfer-request': int,
    'frame-rate': int,
    'i-frame-interval': int,
    'intra-refresh-period': int,
    'latency': int,
    'repeat-previous-frame-after': int,
    'ts-schema': str,
}
KNOWN_RUNTIME_TYPES = {
    'video-bitrate': int,
    'request-sync': None,
    'drop': None,
    'dynamic-framerate': int,
}
TYPE_LIST = (
    'int', 'float', 'str', 'bool', 'null',
)
BITRATE_MODE_VALUES = {
    'cq': 0,
    'vbr': 1,
    'cbr': 2,
    'cbr_fd': 3,
}
FFPROBE_FIELDS = {
    'codec_name': 'codec-name',
    'width': 'width',
    'height': 'height',
    'pix_fmt': 'pix-fmt',
    'color_range': 'color-range',
    'color_space': 'color-space',
    'color_transfer': 'color-transfer',
    'color_primaries': 'color-primaries',
    'r_frame_rate': 'framerate',
    'duration': 'duration',
}
R_FRAME_RATE_MAP = {
    '30/1': 30,
}


def run_cmd(cmd, debug=0):
    ret = True
    try:
        if debug > 0:
            print(cmd, sep=' ')
        process = subprocess.Popen(cmd, shell=True,  # noqa: P204
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE)
        stdout, stderr = process.communicate()
        ret = True if process.returncode == 0 else False
    except Exception:
        ret = False
        print('Failed to run command: ' + cmd)

    return ret, stdout.decode(), stderr.decode()


# returns info (device model and serial number) about the device where the
# test will be run
def get_device_info(serial_inp, debug=0):
    # list all available devices
    adb_cmd = 'adb devices -l'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)
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
    ret, stdout, stderr = run_cmd(adb_cmd, debug)
    output_files = re.findall(ENCAPP_OUTPUT_FILE_NAME_RE, stdout, re.MULTILINE)
    for file in output_files:
        if file == '':
            continue
        # remove the output
        adb_cmd = 'adb -s ' + serial + ' shell rm /sdcard/' + file
        run_cmd(adb_cmd, debug)

    return model, serial


def wait_for_exit(serial, debug=0):
    adb_cmd = f'adb -s {serial} shell pidof {APPNAME_MAIN}'
    pid = -1
    current = 1
    while (current != -1):
        if pid == -1:
            ret, stdout, stderr = run_cmd(adb_cmd, debug)
            pid = -1
            if len(stdout) > 0:
                pid = int(stdout)
        time.sleep(1)
        ret, stdout, stderr = run_cmd(adb_cmd, debug)
        current = -2
        if len(stdout) > 0:
            try:
                current = int(stdout)
            except Exception:
                print(f'wait for exit caught exception: {stdout}')
                continue
        else:
            current = -1
    print(f'Exit from {pid}')


def install_app(serial, debug=0):
    run_cmd(f'adb -s {serial} install -g {APK_MAIN}', debug)
    grant_camera_permission(serial, debug)
    grant_storage_permissions(serial, debug)
    run_cmd(f'adb -s {serial} shell am force-stop -n com.facebook.encapp')


def grant_storage_permissions(serial, debug):
    run_cmd(f'adb -s {serial} shell pm grant {APPNAME_MAIN} android.permission.WRITE_EXTERNAL_STORAGE', debug)
    run_cmd(f'adb -s {serial} shell pm grant {APPNAME_MAIN} android.permission.READ_EXTERNAL_STORAGE', debug)
    run_cmd(f'adb -s {serial} shell appops set --uid {APPNAME_MAIN} MANAGE_EXTERNAL_STORAGE allow', debug)

def grant_camera_permission(serial, debug):
    run_cmd(f'adb -s {serial} shell pm grant {APPNAME_MAIN} android.permission.CAMERA', debug)


def install_ok(serial, debug=0):
    package_list = installed_apps(serial, debug)
    if APPNAME_MAIN not in package_list:
        return False
    return True


def uninstall_app(serial, debug=0):
    package_list = installed_apps(serial, debug)
    if APPNAME_MAIN in package_list:
        run_cmd(f'adb -s {serial} uninstall {APPNAME_MAIN}', debug)
    else:
        print(f'warning: {APPNAME_MAIN} not installed')


def parse_pm_list_packages(stdout):
    package_list = []
    for line in stdout.split('\n'):
        # ignore blank lines
        if not line:
            continue
        if line.startswith('package:'):
            package_list.append(line[len('package:'):])
    return package_list


def installed_apps(serial, debug=0):
    ret, stdout, stderr = run_cmd(f'adb -s {serial} shell pm list packages',
                                  debug)
    assert ret, 'error: failed to get installed app list'
    return parse_pm_list_packages(stdout)


def collect_result(workdir, test_name, serial):
    print(f'Collect_result: {test_name}')
    run_cmd(f'adb -s {serial} shell am start -W -e test '
            f'/sdcard/{test_name} {ACTIVITY}')
    wait_for_exit(serial)
    adb_cmd = 'adb -s ' + serial + ' shell ls /sdcard/'
    ret, stdout, stderr = run_cmd(adb_cmd, True)
    output_files = re.findall(ENCAPP_OUTPUT_FILE_NAME_RE, stdout,
                              re.MULTILINE)
    base_file_name = os.path.basename(test_name).rsplit('.run.bin', 1)[0]
    sub_dir = '_'.join([base_file_name, 'files'])
    output_dir = f'{workdir}/{sub_dir}/'
    run_cmd(f'mkdir {output_dir}')
    result_json = []
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
        if file.endswith('.json'):
            path, tmpname = os.path.split(file)
            result_json.append(f'{output_dir}/{tmpname}')

    adb_cmd = f'adb -s {serial} shell rm /sdcard/{test_name}'
    run_cmd(adb_cmd)
    print(f'results collect: {result_json}')
    return result_json


def verify_video_size(videofile, resolution):
    if not os.path.exists(videofile):
        return False

    if video_is_raw(videofile):
        file_size = os.path.getsize(videofile)
        if (resolution != None):
            framesize = int(resolution.split('x')[0]) * int(resolution.split('x')[1]) * 1.5
            if file_size % framesize == 0:
                return True
        return False
    else:
        # in this case the actual encoded size is used.
        return True


def update_file_paths(test, new_name):
    path = test.input.filepath
    for para in test.parallel.test:
        update_file_paths(para, new_name)

    if path == 'camera':
        return

    if new_name is not None:
        path = new_name

    test.input.filepath = f'/sdcard/{os.path.basename(path)}'
    path = test.input.filepath


def add_files(test, files_to_push):
    if test.input.filepath != 'camera':
        if not (test.input.filepath in files_to_push):
            files_to_push.append(test.input.filepath)
    for para in test.parallel.test:
        if para.input.filepath != 'camera':
            files_to_push = add_files(para, files_to_push)
    return files_to_push


def run_codec_tests_file(test_def, model, serial, workdir, settings):
    print(f'run test: {test_def}')
    tests = tests_definitions.Tests()
    with open(test_def, "rb") as fd:
        tests.ParseFromString(fd.read())
    return run_codec_tests(tests, model, serial, workdir, settings)

def abort_test(workdir, message):
    print('\n*** Test failed ***')
    print(message)
    shutil.rmtree(workdir)
    exit(0)

def run_codec_tests(tests, model, serial, workdir, settings):
    test_def = settings['configfile']  # todo: check
    print(f'Run test: {test_def}')
    fresh = tests_definitions.Tests()
    files_to_push = []
    for test in tests.test:
        if settings['encoder'] is not None and len(settings['encoder']) > 0:
            test.configure.codec = settings['encoder']
        if (settings['inp_resolution'] is not None and
                len(settings['inp_resolution']) > 0):
            test.input.resolution = settings['inp_resolution']
        if (settings['out_resolution'] is not None and
                len(settings['out_resolution']) > 0):
            test.configure.resolution = settings['out_resolution']
        if settings['inp_framerate'] is not None:
            test.input.framerate = settings['inp_framerate']
        if settings['out_framerate'] is not None:
            test.configure.framerate = settings['out_framerate']

        videofile = settings['videofile']
        if videofile is not None and len(videofile) > 0:
            files_to_push.append(videofile)
            # verify video and resolution
            if not verify_video_size(videofile, test.input.resolution):
                abort_test(workdir, 'Video size is not matching the raw file size')
        else:
            # check for possible parallel files
            files_to_push = add_files(test, files_to_push)

        update_file_paths(test, videofile)


        print(f'files to push: {files_to_push}')
        if settings['bitrate'] is not None and len(settings['bitrate']) > 0:
            # defult is serial calls
            split = settings['bitrate'].split('-')
            if len(split) != 3:
                split = settings['bitrate'].split(',')
                if len(split) != 3:
                    # Single bitrate
                    test.configure.bitrate = str(
                        convert_to_bps(settings['bitrate']))
                    fresh.test.extend([test])
                else:
                    for bitrate in split:
                        ntest = tests_definitions.Test()
                        ntest.CopyFrom(test)
                        ntest.configure.bitrate = str(convert_to_bps(bitrate))
                        fresh.test.extend([ntest])
            else:
                fval = convert_to_bps(split[0])
                tval = convert_to_bps(split[1])
                sval = convert_to_bps(split[2])
                for bitrate in range(fval, tval, sval):
                    ntest = tests_definitions.Test()
                    ntest.CopyFrom(test)
                    ntest.configure.bitrate = str(bitrate)
                    fresh.test.extend([ntest])
        else:
            fresh.test.extend([test])

    print(fresh)
    if test_def is None:
        abort_test(workdir, 'ERROR: no test file name')

    test_file = os.path.basename(test_def)
    testname = f"{test_file[0:test_file.rindex('.')]}.run.bin"
    output = f'{workdir}/{testname}'
    os.system('mkdir -p ' + workdir)
    with open(output, 'wb') as binfile:
        binfile.write(fresh.SerializeToString())
        files_to_push.append(output)

    ok = True
    for filepath in files_to_push:
        if os.path.exists(filepath):
            run_cmd(f'adb -s {serial} push {filepath} /sdcard/')
        else:
            ok = False
            print(f'File: \"{filepath}\" does not exist, check path')

    if not ok:
        abort_test(workdir, 'Check file paths and try again')

    return collect_result(workdir, testname, serial)


def list_codecs(serial, model, debug=0):
    adb_cmd = f'adb -s {serial} shell am start '\
              f'-e ui_hold_sec 3 '\
              f'-e list_codecs a {ACTIVITY}'

    run_cmd(adb_cmd, debug)
    wait_for_exit(serial, debug)
    filename = f'codecs_{model}.txt'
    adb_cmd = f'adb -s {serial} pull /sdcard/codecs.txt {filename}'
    ret, stdout, stderr = run_cmd(adb_cmd, debug)
    assert ret, 'error getting codec list: "%s"' % stdout

    with open(filename, 'r') as codec_file:
        lines = codec_file.readlines()
        for line in lines:
            print(line.split('\n')[0])
        print(f'File is available in current dir as {filename}')


def read_json_file(configfile, debug):
    # read input file
    with open(configfile, 'r') as fp:
        if debug > 0:
            print(f'configfile: {configfile}')
        input_config = json.load(fp)
    return input_config


def is_int(s):
    if isinstance(s, int):
        return True
    return (s[1:].isdigit() if s[0] in ('-', '+') else s.isdigit())


def convert_to_bps(value):
    if isinstance(value, str):
        mul = 1
        index = value.rfind('k')
        if index == -1:
            index = value.rfind('M')
            if index > 0:
                mul = 1000000
        elif index > 0:
            mul = 1000
        return int(value[0:index]) * mul
    else:
        return int(value)


# convert a value (in either time or frame units) into frame units
def convert_to_frames(value, fps=30):
    if is_int(value):
        # value is already fps
        return int(value)
    # check if it can be parsed as a duration (time)
    try:
        sec = humanfriendly.parse_timespan(value)
    except humanfriendly.InvalidTimespan:
        print('error: invalid frame value "%s"' % value)
        sys.exit(-1)
    return int(sec * fps)


def convert_test(path):
    output = f"{path[0:path.rindex('.')]}.bin"
    root = f"{SCRIPT_DIR[0:SCRIPT_DIR.rindex('/')]}"
    cmd = (f'protoc -I / --encode="Tests" {root}/proto/tests.proto '
           f'< {path} > {output}')
    print(f'cmd: {cmd}')
    run_cmd(cmd)
    return output


def codec_test(settings, model, serial):
    print(f'codec test: {settings}')
    # convert the human-friendly input into a valid apk input
    test_config = convert_test(settings['configfile'])

    # get date and time and format it
    now = datetime.datetime.now()
    dt_string = now.strftime('%Y-%m-%d_%H_%M')

    # get working directory at the host
    if settings['output'] is not None:
        workdir = settings['output']
    else:
        workdir = f"{settings['desc'].replace(' ', '_')}_{model}_{dt_string}"

    # run the codec test
    return run_codec_tests_file(test_config,
                                model,
                                serial,
                                workdir,
                                settings)


def get_options(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        '-v', '--version', action='store_true',
        dest='version', default=False,
        help='Print version',)
    parser.add_argument(
        '-d', '--debug', action='count',
        dest='debug', default=default_values['debug'],
        help='Increase verbosity (use multiple times for more)',)
    parser.add_argument(
        '--quiet', action='store_const',
        dest='debug', const=-1,
        help='Zero verbosity',)
    parser.add_argument('--serial', help='Android device serial number')
    parser.add_argument(
        '--install', action='store_const',
        dest='install', const=True,
        default=default_values['install'],
        help='Do install apk',)
    parser.add_argument(
        '--no-install', action='store_const',
        dest='install', const=False,
        help='Do not install apk',)
    parser.add_argument(
        'func', type=str, nargs='?',
        default=default_values['func'],
        choices=FUNC_CHOICES.keys(),
        metavar='%s' % (' | '.join("{}: {}".format(k, v) for k, v in
                                   FUNC_CHOICES.items())),
        help='function arg',)
    parser.add_argument(
        '-i', type=str, dest='videofile',
        default=default_values['videofile'],
        metavar='input-video-file',
        help='input video file',)
    parser.add_argument(
        '-c', '--codec', type=str, dest='codec',
        default=default_values['encoder'],
        metavar='encoder',
        help='override encoder in config',)
    parser.add_argument(
        '-r', '--bitrate', type=str, dest='bitrate',
        default=default_values['bps'],
        metavar='input-video-bitrate',
        help='input video bitrate, either as a single number, '
        '\"100 kbps\" or a lst 100kbps,200kbps or a range '
        '100k-1M-100k (start-stop-step)',)
    parser.add_argument(
        'configfile', type=str, nargs='?',
        default=default_values['configfile'],
        metavar='input-config-file',
        help='input configuration file',)
    parser.add_argument(
        'output', type=str, nargs='?',
        default=default_values['output'],
        metavar='output',
        help='output dir or file',)

    options = parser.parse_args(argv[1:])
    options.desc = "testing"
    if options.version:
        return options

    # implement help
    if options.func == 'help':
        parser.print_help()
        sys.exit(0)

    if options.serial is None and 'ANDROID_SERIAL' in os.environ:
        # read serial number from ANDROID_SERIAL env variable
        options.serial = os.environ['ANDROID_SERIAL']

    global DEBUG
    DEBUG = options.debug > 0
    return options


def video_is_raw(videofile):
    extension = os.path.splitext(videofile)[1]
    return extension in RAW_EXTENSION_LIST


def parse_ffprobe_output(stdout):
    videofile_config = {}
    for line in stdout.split('\n'):
        if not line:
            # ignore empty lines
            continue
        if line in ('[STREAM]', '[/STREAM]'):
            # ignore start/end of stream
            continue
        key, value = line.split('=')
        # store interesting fields
        if key in FFPROBE_FIELDS.keys():
            # process some values
            if key == 'r_frame_rate':
                value = R_FRAME_RATE_MAP[value]
            elif key == 'width' or key == 'height':
                value = int(value)
            elif key == 'duration':
                value = float(value)
            key = FFPROBE_FIELDS[key]
            videofile_config[key] = value
    return videofile_config


def get_video_info(videofile, debug=0):
    assert os.path.exists(videofile), (
        'input video file (%s) does not exist' % videofile)
    assert os.path.isfile(videofile), (
        'input video file (%s) is not a file' % videofile)
    assert os.access(videofile, os.R_OK), (
        'input video file (%s) is not readable' % videofile)
    if video_is_raw(videofile):
        return {}
    # check using ffprobe
    cmd = f'ffprobe -v quiet -select_streams v -show_streams {videofile}'
    ret, stdout, stderr = run_cmd(cmd, debug)
    assert ret, f'error: failed to analyze file {videofile}'
    videofile_config = parse_ffprobe_output(stdout)
    videofile_config['filepath'] = videofile
    return videofile_config


def verify_app_version(json_files):
    for fl in json_files:
        with open(fl) as f:
            data = json.load(f)
            version = data['encapp_version']
            if __version__ != version:
                print(f'Warning, version missmatch between script '
                      f'({__version__}) and application ({version})')


def main(argv):
    options = get_options(argv)
    if options.version:
        print('version: %s' % __version__)
        sys.exit(0)

    videofile_config = {}
    if (options.videofile is not None and
            options.videofile != 'camera'):
        videofile_config = get_video_info(options.videofile)  # noqa: F841

    # get model and serial number
    model, serial = get_device_info(options.serial, options.debug)
    # TODO(chema): fix this
    if type(model) is dict:
        if 'model' in model:
            model = model.get('model')
        else:
            model = list(model.values())[0]
    if options.debug > 0:
        print(f'model = {model}')

    # install app
    if options.func == 'install' or options.install:
        install_app(serial, options.debug)

    # uninstall app
    if options.func == 'uninstall':
        uninstall_app(serial, options.debug)
        sys.exit(0)

    # ensure the app is correctly installed
    assert install_ok(serial, options.debug), (
        'Apps not installed in %s' % serial)

    # run function
    if options.func == 'list':
        list_codecs(serial, model, options.debug)

    elif options.func == 'run':
        # ensure there is an input configuration
        assert options.configfile is not None, (
            'error: need a valid input configuration file')

        settings = extra_settings
        settings['configfile'] = options.configfile
        settings['videofile'] = options.videofile
        settings['encoder'] = options.codec
        settings['encoder'] = options.codec
        settings['output'] = options.output
        settings['bitrate'] = options.bitrate
        settings['desc'] = options.desc

        result = codec_test(settings, model, serial)
        verify_app_version(result)


if __name__ == '__main__':
    try:
        main(sys.argv)
    except AssertionError as ae:
        print(ae, file=sys.stderr)
        if DEBUG:
            raise
        sys.exit(1)
