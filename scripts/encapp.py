#!/usr/bin/env python3

"""Python script to run ENCAPP tests on Android and collect results.
The script will create a directory based on device model and date,
and save encoded video and rate distortion results in the directory
"""

import os
import humanfriendly
import json
import sys
import argparse
import itertools
import re
import shutil
import tempfile
import time
import datetime
from google.protobuf import text_format


import encapp_tool
import encapp_tool.app_utils
import encapp_tool.adb_cmds

SCRIPT_ROOT_DIR = os.path.abspath(os.path.join(
    encapp_tool.app_utils.SCRIPT_DIR, os.pardir))
sys.path.append(SCRIPT_ROOT_DIR)
import proto.tests_pb2 as tests_definitions  # noqa: E402


RD_RESULT_FILE_NAME = 'rd_results.json'

DEBUG = False

FUNC_CHOICES = {
    'help': 'show help options',
    'install': 'install apks',
    'uninstall': 'uninstall apks',
    'list': 'list codecs and devices supported',
    'run': 'run codec test case',
    'kill': 'kill application'
}

default_values = {
    'debug': 0,
    'func': 'help',
    'device_workdir': '/sdcard',
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
PIX_FMT_TYPES = ('yuv420p', 'nv12', 'rgba')
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


def remove_encapp_gen_files(
        serial, device_workdir=default_values['device_workdir'],
        debug=0):
    # remove any files that are generated in previous runs
    regex_str = encapp_tool.adb_cmds.ENCAPP_OUTPUT_FILE_NAME_RE
    encapp_tool.adb_cmds.remove_files_using_regex(
        serial, regex_str, device_workdir, debug)


def wait_for_exit(serial, debug=0):
    pid = -1
    current = 1
    while current != -1:
        current = encapp_tool.adb_cmds.get_app_pid(
            serial, encapp_tool.app_utils.APPNAME_MAIN, debug)
        if current > 0:
            pid = current
        time.sleep(1)
    if pid != -1 and debug > 0:
        print(f'exit from {pid}')
    else:
        print(f'{encapp_tool.app_utils.APPNAME_MAIN} was not active')


def run_encapp_test(protobuf_txt_filepath, serial, device_workdir, debug):
    print(f'running test: {protobuf_txt_filepath}')
    # clean the logcat first
    encapp_tool.adb_cmds.reset_logcat(serial)
    ret, _, stderr = encapp_tool.adb_cmds.run_cmd(
        f'adb -s {serial} shell am start -W '
        f'-e workdir {device_workdir} '
        f'-e test {protobuf_txt_filepath} '
        f'{encapp_tool.app_utils.ACTIVITY}', debug)
    assert ret, f'ERROR: {stderr}'
    wait_for_exit(serial)


def collect_results(local_workdir, protobuf_txt_filepath, serial,
                    device_workdir, debug):
    print(f'collecting result: {protobuf_txt_filepath}')
    adb_cmd = f'adb -s {serial} shell ls {device_workdir}/'
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
    output_files = re.findall(
        encapp_tool.adb_cmds.ENCAPP_OUTPUT_FILE_NAME_RE, stdout,
        re.MULTILINE)
    # prepare the local working directory to pull the files in
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)
    result_json = []
    for file in output_files:
        if file == '':
            print('No file found')
            continue
        # pull the output file
        adb_cmd = (f'adb -s {serial} pull {device_workdir}/{file} '
                   f'{local_workdir}')
        encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
        # remove the file on the device
        adb_cmd = f'adb -s {serial} shell rm {device_workdir}/{file}'
        encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
        # append results file (json files) to final results
        if file.endswith('.json'):
            path, tmpname = os.path.split(file)
            result_json.append(os.path.join(local_workdir, tmpname))
    # remove the test file
    adb_cmd = (f'adb -s {serial} shell rm '
               f'{device_workdir}/{protobuf_txt_filepath}')
    encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
    if debug > 0:
        print(f'results collect: {result_json}')
    # get logcat
    log = encapp_tool.adb_cmds.logcat_dump(serial)
    parse_log(log, local_workdir)
    return result_json


def parse_log(log, local_workdir):
    test_reg = re.compile(r'.*Test failed: ([\w\W]*)')
    failed = False
    for line in log.splitlines():
        match = test_reg.search(line)
        if match:
            print('**********\n\nTest case failed:')
            print(f'"{match.group(1)}"\n')
            print('\n\n**********')
            failed = True
    logcat_filepath = f'{local_workdir}/logcat.txt'
    with open(logcat_filepath, 'w') as fd:
        fd.write(log)
    if failed:
        print(f'logcat has been saved to "{logcat_filepath}"')


def verify_video_size(videofile, resolution):
    assert os.path.exists(videofile) and os.access(videofile, os.R_OK)
    if not video_is_raw(videofile):
        # in this case the actual encoded size is used
        return True
    # video is raw
    file_size = os.path.getsize(videofile)
    if resolution is not None:
        framesize = (int(resolution.split('x')[0]) *
                     int(resolution.split('x')[1]) * 1.5)
        if file_size % framesize == 0:
            return True
    return False


def update_file_paths(test, device_workdir):
    # update subtests
    for subtest in test.parallel.test:
        update_file_paths(subtest, device_workdir)
    # camera tests do not need any input file paths
    if test.input.filepath == 'camera':
        return
    # update main test
    basename = os.path.basename(test.input.filepath)
    test.input.filepath = f'{device_workdir}/{basename}'


def add_media_files(test):
    files_to_push = set()
    if test.input.filepath != 'camera':
        files_to_push.add(test.input.filepath)
    for subtest in test.parallel.test:
        if subtest.input.filepath != 'camera':
            files_to_push |= add_media_files(subtest)
    return files_to_push


def run_codec_tests_file(protobuf_txt_file, model, serial, local_workdir,
                         settings, debug):
    print(f'reading test: {protobuf_txt_file}')
    test_suite = tests_definitions.TestSuite()
    with open(protobuf_txt_file, 'rb') as fd:
        text_format.Merge(fd.read(), test_suite)
        # test_suite.ParseFromString(fd.read())
    print(f'updating test: {protobuf_txt_file}')
    test_suite, files_to_push, protobuf_txt_filepath = update_codec_tests(
        test_suite, local_workdir, settings)
    return run_codec_tests(test_suite, files_to_push, protobuf_txt_filepath,
                           model, serial, local_workdir, settings, debug)


def abort_test(local_workdir, message):
    print('\n*** Test failed ***')
    print(message)
    shutil.rmtree(local_workdir)
    sys.exit(-1)


# produce a list of bitrates from a CLI spec. Options are:
# * (1) a single number (e.g. "100 kbps")
# * (2) a range (e.g. "100k-1M-100k") (start-stop-step)
# * (3) a list of single numbers or ranges (e.g. "100kbps,200kbps")
def parse_bitrate_field(bitrate):
    # parse lists
    if ',' in bitrate:
        bitrate_list = [parse_bitrate_field(it) for it in bitrate.split(',')]
        # append the produced lists
        return list(itertools.chain(*bitrate_list))
    # parse ranges
    if '-' in bitrate:
        bitrate_spec = bitrate.split('-')
        assert len(bitrate_spec) == 3, (
            f'error: invalid bitrate spec: "{bitrate}"')
        start, stop, step = [convert_to_bps(it) for it in bitrate_spec]
        return list(range(start, stop, step))
    # parse single elements
    return [convert_to_bps(bitrate)]


# Update a set of tests with the CLI arguments.
# Note that update may include adding new tests (e.g. if bitrate is
# defined as a (from, to, step) tuple instead of a single value).
def update_codec_tests(test_suite, local_workdir, settings):
    # 1. update the tests with the CLI parameters
    updated_test_suite = tests_definitions.TestSuite()
    tests_id = None
    for test in test_suite.test:
        # save the main test id
        if tests_id is None:
            tests_id = test.common.id
        # update values from CLI options
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
        # update the video file
        videofile = settings['videofile']
        if videofile is not None and len(videofile) > 0:
            # we want to replace the video (input.filepath) everywhere
            if (not os.path.exists(videofile) or
                    not os.access(videofile, os.R_OK)):
                abort_test(local_workdir,
                           f'file {videofile} does not exist/is not readable')
            # verify video resolution
            if not verify_video_size(videofile, test.input.resolution):
                abort_test(local_workdir,
                           f'file {videofile} does not match '
                           f'{test.input.resolution}')
        # update the bitrate
        if settings['bitrate'] is not None and len(settings['bitrate']) > 0:
            bitrate_list = parse_bitrate_field(settings['bitrate'])
            for bitrate in bitrate_list:
                # create a new test with the new bitrate
                ntest = tests_definitions.Test()
                ntest.CopyFrom(test)
                ntest.common.id = test.common.id + f'.{bitrate}'
                ntest.configure.bitrate = str(bitrate)
                updated_test_suite.test.extend([ntest])

    # 2. get a list of all the media files that will need to be pushed
    files_to_push = set()
    for test in updated_test_suite.test:
        files_to_push |= add_media_files(test)

    # 3. save the media files
    if not os.path.exists(local_workdir):
        os.mkdir(local_workdir)
    for filepath in files_to_push:
        # https://stackoverflow.com/a/30359308
        shutil.copy2(filepath, local_workdir + '/')

    # 4. update all the file paths to the remote workdir
    for test in updated_test_suite.test:
        update_file_paths(test, settings['device_workdir'])

    # 5. save the full protobuf text file(s)
    if False:  # one pbtxt file per subtest
        for test in updated_test_suite.test:
            output_dir = f'{local_workdir}/{test.common.id}'
            if not os.path.exists(output_dir):
                os.mkdir(output_dir)
            filename = f'{output_dir}/{test.common.id}.pbtxt'
            with open(filename, 'w') as f:
                f.write(text_format.MessageToString(test))
            files_to_push |= {filename}
    else:  # one pbtxt for all tests
        protobuf_txt_filepath = f'{local_workdir}/{tests_id}.pbtxt'
        with open(protobuf_txt_filepath, 'w') as f:
            f.write(text_format.MessageToString(updated_test_suite))
        files_to_push |= {protobuf_txt_filepath}

    # print(f'files to push: {files_to_push}')
    return updated_test_suite, files_to_push, protobuf_txt_filepath


def push_file_to_device(filepath, serial, device_workdir, debug):
    if not os.path.exists(filepath):
        print(f'ERROR: file "{filepath}" does not exist, check path')
        return False
    encapp_tool.adb_cmds.run_cmd(
        f'adb -s {serial} push {filepath} {device_workdir}/', debug)
    return True


def run_codec_tests(test_suite, files_to_push, protobuf_txt_filepath, model,
                    serial, local_workdir, settings, debug):
    print(f'running {protobuf_txt_filepath} ({len(test_suite.test)} test(s))')
    os.makedirs(local_workdir, exist_ok=True)

    # push all the files to the device workdir
    device_workdir = settings['device_workdir']
    for filepath in files_to_push:
        if not push_file_to_device(filepath, serial, device_workdir, debug):
            abort_test(local_workdir, 'Check file paths and try again')

    # run the test(s)
    if False:  # one pbtxt file per subtest
        for test in test_suite.test:
            protobuf_txt_filepath = f'{device_workdir}/{test.common.id}.pbtxt'
            run_encapp_test(protobuf_txt_filepath, serial, device_workdir,
                            debug)
    else:  # one pbtxt for all tests
        basename = os.path.basename(protobuf_txt_filepath)
        protobuf_txt_filepath = f'{device_workdir}/{basename}'
        run_encapp_test(protobuf_txt_filepath, serial, device_workdir,
                        debug)

    # collect the test results
    return collect_results(local_workdir, protobuf_txt_filepath, serial,
                           settings['device_workdir'], debug)


def list_codecs(serial, model, device_workdir, debug=0):
    adb_cmd = (f'adb -s {serial} shell am start '
               f'-e workdir {device_workdir} '
               '-e ui_hold_sec 3 '
               f'-e list_codecs a {encapp_tool.app_utils.ACTIVITY}')

    encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
    wait_for_exit(serial, debug)
    filename = f'codecs_{model}.txt'
    adb_cmd = f'adb -s {serial} pull {device_workdir}/codecs.txt {filename}'
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(adb_cmd, debug)
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
    # support for integers
    if isinstance(value, int):
        return value
    # support for strings containing only integers
    if value.isnumeric():
        return int(value)
    # remove spaces
    val = value.replace(' ', '')
    # support for SI units (at least 'k' and 'M')
    mul = 1
    index = val.rfind('k')
    if index > 0:
        mul = int(1e3)
    else:
        index = val.rfind('M')
        if index > 0:
            mul = int(1e6)
        else:
            # not a valid number
            raise AssertionError(f'invalid bitrate: {value}')
    return int(value[0:index]) * mul


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


def check_protobuf_txt_file(protobuf_txt_file, local_workdir, debug):
    # ensure the protobuf text file exists and is readable
    if protobuf_txt_file is None:
        abort_test(local_workdir, 'ERROR: need a test file name')
    if (not os.path.exists(protobuf_txt_file) or
            not os.path.isfile(protobuf_txt_file) or
            not os.access(protobuf_txt_file, os.R_OK)):
        abort_test(local_workdir, 'ERROR: invalid test file name')
    # use a temp file for the binary output
    _, protobuf_bin_file = tempfile.mkstemp(dir='/tmp')
    cmd = (f'protoc -I {protobuf_txt_file} --encode="TestSuite" '
           f'{protobuf_bin_file}')
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    assert ret == 0, f'ERROR: {stderr}'


def codec_test(settings, model, serial, debug):
    print(f'codec test: {settings}')
    # get the local working directory (at the host)
    if settings['output'] is not None:
        local_workdir = settings['output']
    else:
        now = datetime.datetime.now()
        dt_string = now.strftime('%Y%m%d_%H%M%S')
        local_workdir = (f'{settings["desc"].replace(" ", "_")}'
                         f'_{model}_{dt_string}')

    # check the protobuf text is correct
    protobuf_txt_file = settings['configfile']
    check_protobuf_txt_file(protobuf_txt_file, local_workdir, debug)

    # run the codec test
    return run_codec_tests_file(protobuf_txt_file,
                                model,
                                serial,
                                local_workdir,
                                settings,
                                debug)


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
        metavar='%s' % (' | '.join('{}: {}'.format(k, v) for k, v in
                                   FUNC_CHOICES.items())),
        help='function arg',)
    parser.add_argument(
        '-i', type=str, dest='videofile',
        default=default_values['videofile'],
        metavar='input-video-file',
        help='input video file',)
    parser.add_argument(
        '--device-workdir', type=str, dest='device_workdir',
        default=default_values['device_workdir'],
        metavar='device_workdir',
        help='work (storage) directory on device',)
    parser.add_argument(
        '-c', '--codec', type=str, dest='codec',
        default=default_values['encoder'],
        metavar='encoder',
        help='override encoder in config',)
    parser.add_argument(
        '-r', '--bitrate', type=str, dest='bitrate',
        default=default_values['bps'],
        metavar='input-video-bitrate',
        help='input video bitrate. Can be a single number '
        '(e.g. "100 kbps"), a list (e.g. "100kbps,200kbps") or a range '
        '(e.g. "100k-1M-100k") (start-stop-step)',)
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
    options.desc = 'testing'
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
    ret, stdout, stderr = encapp_tool.adb_cmds.run_cmd(cmd, debug)
    assert ret, f'error: failed to analyze file {videofile}'
    videofile_config = parse_ffprobe_output(stdout)
    videofile_config['filepath'] = videofile
    return videofile_config


def verify_app_version(json_files):
    for fl in json_files:
        with open(fl) as f:
            data = json.load(f)
            version = data['encapp_version']
            if encapp_tool.__version__ != version:
                print(f'Warning, version missmatch between script '
                      f'({encapp_tool.__version__}) '
                      f'and application ({version})')


def main(argv):
    options = get_options(argv)
    if options.version:
        print('version: %s' % encapp_tool.__version__)
        sys.exit(0)

    videofile_config = {}
    if (options.videofile is not None and
            options.videofile != 'camera'):
        videofile_config = get_video_info(options.videofile)  # noqa: F841

    # get model and serial number
    model, serial = encapp_tool.adb_cmds.get_device_info(
        options.serial, options.debug)
    remove_encapp_gen_files(serial, options.device_workdir, options.debug)

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
        encapp_tool.app_utils.install_app(serial, options.debug)

    # uninstall app
    if options.func == 'uninstall':
        encapp_tool.app_utils.uninstall_app(serial, options.debug)
        return

    if options.func == 'kill':
        print('Force stop')
        encapp_tool.app_utils.force_stop(serial, options.debug)
        return

    # ensure the app is correctly installed
    assert encapp_tool.app_utils.install_ok(serial, options.debug), (
        'Apps not installed in %s' % serial)

    # run function
    if options.func == 'list':
        list_codecs(serial, model, options.device_workdir, options.debug)

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
        settings['device_workdir'] = options.device_workdir

        result = codec_test(settings, model, serial, options.debug)
        verify_app_version(result)


if __name__ == '__main__':
    try:
        main(sys.argv)
    except AssertionError as ae:
        print(ae, file=sys.stderr)
        if DEBUG:
            raise
        sys.exit(1)
